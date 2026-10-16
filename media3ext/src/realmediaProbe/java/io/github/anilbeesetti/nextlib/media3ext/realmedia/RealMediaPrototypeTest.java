package io.github.anilbeesetti.nextlib.media3ext.realmedia;

import static org.junit.Assert.*;

import android.net.Uri;
import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Assume;

/** Gate tests, not a claim that an Extractor or a playable MediaSource has shipped. */
@UnstableApi
public class RealMediaPrototypeTest {
    private static final String TAG = "NextLibRealMediaProbe";
    private static final DataSpec SPEC = new DataSpec.Builder()
            .setUri("https://probe.invalid/file.rm")
            .setKey("application-cache-key")
            .setHttpRequestHeaders(Collections.singletonMap("Authorization", "Bearer test-probe"))
            .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build();

    private static byte[] sample(String name) throws IOException {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("realmedia/" + name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static final class MemoryFactory implements DataSource.Factory {
        final byte[] bytes;
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger opens = new AtomicInteger();
        final List<DataSpec> requests = Collections.synchronizedList(new ArrayList<>());
        int chunk = 32768;
        IOException readFailure;
        long failAt = Long.MAX_VALUE;
        CountDownLatch blocked;

        MemoryFactory(byte[] bytes) { this.bytes = bytes; }
        @Override public DataSource createDataSource() {
            return new BaseDataSource(false) {
                int position;
                boolean started;
                DataSpec spec;
                @Override public long open(DataSpec spec) throws IOException {
                    transferInitializing(spec);
                    this.spec = spec;
                    if (spec.position > bytes.length) throw new IOException("Range outside input");
                    position = (int) spec.position;
                    requests.add(spec);
                    opens.incrementAndGet(); active.incrementAndGet(); started = true;
                    transferStarted(spec);
                    return bytes.length - position;
                }
                @Override public int read(byte[] output, int offset, int length) throws IOException {
                    if (length == 0) return 0;
                    if (blocked != null) {
                        blocked.countDown();
                        try { new CountDownLatch(1).await(); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new InterruptedIOException("Interrupted test DataSource");
                        }
                    }
                    if (position >= failAt && readFailure != null) throw readFailure;
                    if (position == bytes.length) return C.RESULT_END_OF_INPUT;
                    int count = Math.min(Math.min(length, chunk), bytes.length - position);
                    System.arraycopy(bytes, position, output, offset, count);
                    position += count; bytesTransferred(count);
                    return count;
                }
                @Override public Uri getUri() { return spec == null ? null : spec.uri; }
                @Override public void close() {
                    if (started) {
                        started = false; active.decrementAndGet(); transferEnded();
                    }
                }
            };
        }
    }

    @Test public void extractorCannotSynchronouslyReadTailIndex() throws Exception {
        MemoryFactory factory = new MemoryFactory(sample("rv30-hi1.rm"));
        DataSource source = factory.createDataSource();
        try {
            long length = source.open(SPEC);
            ExtractorProbeInput input = new ExtractorProbeInput(new DefaultExtractorInput(source, 0, length));
            ExtractorProbeInput.ReopenRequired failure = assertThrows(
                    ExtractorProbeInput.ReopenRequired.class, () -> new RealMediaProbe(input));
            assertTrue(failure.position > 32768);
            Log.i(TAG, "extractor_gate=FAIL synchronous_tail_index_position=" + failure.position);
        } finally { source.close(); }
        assertEquals(0, factory.active.get());
        assertEquals(0, RealMediaProbe.liveSessions());
    }

    @Test public void productionNativeDecoderReuseIsMeasuredAgainstReference() throws Exception {
        for (String name : new String[]{"rv30-hi1.rm", "rv40-blade.rm", "cook.rm", "cook-51.rma"}) {
            MemoryFactory factory = new MemoryFactory(sample(name));
            // Exercise short reads as well as normal AVIO buffering.
            factory.chunk = 997;
            try (DataSourceProbeInput input = new DataSourceProbeInput(factory, SPEC, true);
                 RealMediaProbe probe = new RealMediaProbe(input)) {
                JSONObject result = new JSONObject(probe.compareDecoders());
                JSONArray tracks = result.getJSONArray("tracks");
                assertTrue(tracks.length() > 0);
                for (int i = 0; i < tracks.length(); i++) {
                    JSONObject track = tracks.getJSONObject(i);
                    if (track.getString("codec").startsWith("rv")) {
                        assertTrue(track.getBoolean("full_params_drain_flush_matches"));
                        // The current SimpleDecoder-based Java class never sends EOS to FFmpeg.
                        assertEquals(track.getInt("reference_frames") - 1, track.getInt("legacy_frames"));
                        assertEquals(1, track.getInt("reference_eos_frames"));
                    } else {
                        assertEquals("cook", track.getString("codec"));
                        assertFalse(track.getBoolean("baseline_initializer_accepts_params"));
                        assertTrue(track.getBoolean("full_params_pcm_flush_matches"));
                        assertTrue(track.getLong("pcm_bytes") > 0);
                    }
                }
                Log.i(TAG, name + " decoder=" + result);
            }
            assertEquals(0, factory.active.get());
            for (DataSpec request : factory.requests) {
                assertEquals(SPEC.httpRequestHeaders, request.httpRequestHeaders);
                assertEquals(SPEC.key, request.key);
                assertEquals(SPEC.flags, request.flags);
            }
        }
        assertEquals(0, RealMediaProbe.liveSessions());
    }

    @Test public void avioHandlesBufferedRewindAndReopensForOutsideSeek() throws Exception {
        MemoryFactory factory = new MemoryFactory(sample("rv30-hi1.rm"));
        try (DataSourceProbeInput input = new DataSourceProbeInput(factory, SPEC, true);
             RealMediaProbe probe = new RealMediaProbe(input)) {
            int before = input.reopens;
            probe.checkBufferedSeeks();
            assertEquals(before + 1, input.reopens);
            assertEquals(1024 * 1024, factory.requests.get(factory.requests.size() - 1).position);
        }
        assertEquals(0, factory.active.get());
        assertEquals(0, RealMediaProbe.liveSessions());
    }

    @Test public void fallbackCompletes100RandomSeeksAndChecksDecodedPixels() throws Exception {
        for (String name : new String[]{"rv30-hi1.rm", "rv40-blade.rm"}) {
            MemoryFactory factory = new MemoryFactory(sample(name));
            try (DataSourceProbeInput input = new DataSourceProbeInput(factory, SPEC, true);
                 RealMediaProbe probe = new RealMediaProbe(input)) {
                JSONObject result = new JSONObject(probe.seekAndDecode(100));
                assertEquals(100, result.getInt("pixel_matches"));
                assertTrue(result.getLong("max_bytes") < 4 * 1024 * 1024);
                assertTrue(input.reopens >= 100);
                Log.i(TAG, name + " fallback_seek=" + result + " media3_reopens=" + input.reopens);
            }
            assertEquals(0, factory.active.get());
        }
        assertEquals(0, RealMediaProbe.liveSessions());
    }

    private static List<long[]> packets(MemoryFactory factory, boolean randomAccess) throws IOException {
        List<long[]> packets = new ArrayList<>();
        try (DataSourceProbeInput input = new DataSourceProbeInput(factory, SPEC, randomAccess);
             RealMediaProbe probe = new RealMediaProbe(input)) {
            long[] packet;
            while ((packet = probe.readPacket()) != null) packets.add(packet);
        }
        return packets;
    }

    @Test public void unindexedInputHasIdenticalSequentialPackets() throws Exception {
        byte[] original = sample("rv30-hi1.rm");
        byte[] noIndex = original.clone();
        // This fixture has a v0 PROP header directly after the 18-byte RMF header.
        assertArrayEquals("PROP".getBytes(StandardCharsets.US_ASCII), Arrays.copyOfRange(noIndex, 18, 22));
        Arrays.fill(noIndex, 18 + 38, 18 + 42, (byte) 0); // PROP.index_offset
        MemoryFactory indexed = new MemoryFactory(original);
        MemoryFactory unindexed = new MemoryFactory(noIndex);
        List<long[]> reference = packets(indexed, true);
        List<long[]> actual = packets(unindexed, false);
        assertEquals(reference.size(), actual.size());
        for (int i = 0; i < actual.size(); i++) assertArrayEquals(reference.get(i), actual.get(i));
        assertEquals(1, unindexed.opens.get());
        assertEquals(0, unindexed.active.get());
        Log.i(TAG, "unindexed_sequential_packets=" + actual.size() + " seek_claim=false");
    }

    @Test public void originalNetworkErrorSurvivesJniAndRecoveryReopensContext() throws Exception {
        MemoryFactory factory = new MemoryFactory(sample("rv30-hi1.rm"));
        IOException expected = new IOException("injected application DataSource failure");
        factory.failAt = 0; factory.readFailure = expected;
        try (DataSourceProbeInput input = new DataSourceProbeInput(factory, SPEC, true)) {
            assertSame(expected, assertThrows(IOException.class, () -> new RealMediaProbe(input)));
        }
        assertEquals(0, RealMediaProbe.liveSessions());
        factory.failAt = Long.MAX_VALUE;
        List<long[]> reference = packets(factory, true);
        // Read error after header/index initialization and several packets.
        try (DataSourceProbeInput input = new DataSourceProbeInput(factory, SPEC, false);
             RealMediaProbe probe = new RealMediaProbe(input)) {
            factory.failAt = 65536;
            assertSame(expected, assertThrows(IOException.class, () -> {
                while (probe.readPacket() != null) { /* consume until injected failure */ }
            }));
        }
        factory.failAt = Long.MAX_VALUE;
        List<long[]> recovered = packets(factory, true);
        assertEquals(reference.size(), recovered.size());
        for (int i = 0; i < recovered.size(); i++) assertArrayEquals(reference.get(i), recovered.get(i));
        assertEquals(0, factory.active.get());
        assertEquals(0, RealMediaProbe.liveSessions());
    }

    @Test public void interruptedBlockedReadReleasesInputAndNativeSession() throws Exception {
        MemoryFactory factory = new MemoryFactory(sample("rv30-hi1.rm"));
        factory.blocked = new CountDownLatch(1);
        AtomicReference<DataSourceProbeInput> current = new AtomicReference<>();
        FutureTask<IOException> work = new FutureTask<>(() -> {
            try (DataSourceProbeInput input = new DataSourceProbeInput(factory, SPEC, true)) {
                current.set(input);
                return assertThrows(IOException.class, () -> new RealMediaProbe(input));
            }
        });
        Thread loader = new Thread(work, "RealMediaProbe:Loader");
        loader.start();
        try {
            assertTrue(factory.blocked.await(5, TimeUnit.SECONDS));
            current.get().cancel();
            loader.interrupt();
            assertTrue(work.get(2, TimeUnit.SECONDS) instanceof InterruptedIOException);
        } finally {
            loader.interrupt();
            loader.join(2000);
        }
        assertFalse(loader.isAlive());
        assertEquals(0, factory.active.get());
        assertEquals(0, RealMediaProbe.liveSessions());
    }

    /** Tiny real HTTP server: observes Range and authentication; all requests go through Media3. */
    static final class HttpFixture implements AutoCloseable {
        final ServerSocket server;
        final boolean secure;
        final byte[] bytes;
        final List<Map<String, String>> requests = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;
        final boolean stall;
        volatile Socket active;
        volatile boolean closed;
        HttpFixture(byte[] bytes, boolean stall) throws IOException {
            this(bytes, stall, null);
        }
        HttpFixture(byte[] bytes, boolean stall, javax.net.ssl.SSLContext tls) throws IOException {
            secure = tls != null;
            server = tls == null ? new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
                    : tls.getServerSocketFactory().createServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
            this.bytes = bytes; this.stall = stall;
            thread = new Thread(this::serve, "RealMediaProbe:Http");
            thread.start();
        }
        Uri uri() { return Uri.parse((secure ? "https" : "http") + "://127.0.0.1:" + server.getLocalPort() + "/sample.rm"); }
        void serve() {
            try {
                while (!closed) {
                    try (Socket socket = server.accept()) {
                        active = socket;
                        socket.setSoTimeout(2000);
                        ByteArrayOutputStream header = new ByteArrayOutputStream();
                        int next;
                        String text;
                        do {
                            next = socket.getInputStream().read();
                            if (next == -1) throw new IOException("incomplete HTTP request");
                            header.write(next);
                            text = header.toString("US-ASCII");
                        } while (!text.endsWith("\r\n\r\n") && header.size() < 16384);
                        Map<String, String> fields = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                        for (String line : text.split("\r\n")) {
                            int colon = line.indexOf(':');
                            if (colon > 0) fields.put(line.substring(0, colon), line.substring(colon + 1).trim());
                        }
                        requests.add(fields);
                        String range = fields.get("Range");
                        int start = range == null ? 0 : Integer.parseInt(range.substring(6).split("-")[0]);
                        String response = "HTTP/1.1 " + (range == null ? "200 OK" : "206 Partial Content") + "\r\n"
                                + "Content-Type: application/vnd.rn-realmedia\r\nConnection: close\r\n"
                                + "Content-Length: " + (bytes.length - start) + "\r\n"
                                + (range == null ? "" : "Content-Range: bytes " + start + "-" + (bytes.length - 1)
                                + "/" + bytes.length + "\r\n") + "\r\n";
                        socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                        if (stall) {
                            socket.getOutputStream().flush();
                            while (socket.getInputStream().read() != -1) { /* wait for cancellation/timeout close */ }
                        } else {
                            try { socket.getOutputStream().write(bytes, start, bytes.length - start); }
                            catch (IOException clientReopenedRange) { /* AVIO can reopen before draining response. */ }
                        }
                        active = null;
                    }
                }
            } catch (Throwable error) { if (!closed) failure.set(error); }
        }
        @Override public void close() throws Exception {
            closed = true;
            server.close();
            Socket socket = active;
            if (socket != null) socket.close();
            thread.join(3000);
            assertFalse("HTTP fixture leaked its thread", thread.isAlive());
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }

    @Test public void httpRangeHeadersAndTrafficUseApplicationDataSource() throws Exception {
        try (HttpFixture http = new HttpFixture(sample("rv40-blade.rm"), false)) {
            AtomicInteger transferred = new AtomicInteger();
            DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(2000).setReadTimeoutMs(2000)
                    .setTransferListener(new TransferListener() {
                        @Override public void onTransferInitializing(DataSource source, DataSpec spec, boolean network) {}
                        @Override public void onTransferStart(DataSource source, DataSpec spec, boolean network) {}
                        @Override public void onBytesTransferred(DataSource source, DataSpec spec, boolean network, int bytes) {
                            transferred.addAndGet(bytes);
                        }
                        @Override public void onTransferEnd(DataSource source, DataSpec spec, boolean network) {}
                    });
            try (DataSourceProbeInput input = new DataSourceProbeInput(factory,
                    SPEC.buildUpon().setUri(http.uri()).build(), true);
                 RealMediaProbe probe = new RealMediaProbe(input)) {
                JSONObject result = new JSONObject(probe.seekAndDecode(100));
                assertEquals(100, result.getInt("pixel_matches"));
                assertTrue(input.reopens >= 100);
                Log.i(TAG, "http_fallback_seek=" + result + " counted_bytes=" + transferred.get());
            }
            assertTrue(transferred.get() > 0);
            assertTrue(http.requests.size() >= 100);
            boolean rangeSeen = false;
            for (Map<String, String> request : http.requests) {
                assertEquals("Bearer test-probe", request.get("Authorization"));
                rangeSeen |= request.containsKey("Range");
            }
            assertTrue(rangeSeen);
        }
        assertEquals(0, RealMediaProbe.liveSessions());
    }

    @Test public void stalledHttpReadHasBoundedTimeoutAndClosesNativeResources() throws Exception {
        long start = android.os.SystemClock.elapsedRealtime();
        try (HttpFixture http = new HttpFixture(sample("rv30-hi1.rm"), true)) {
            DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(1000).setReadTimeoutMs(300);
            try (DataSourceProbeInput input = new DataSourceProbeInput(factory,
                    SPEC.buildUpon().setUri(http.uri()).build(), true)) {
                IOException failure = assertThrows(IOException.class, () -> new RealMediaProbe(input));
                assertTrue(failure instanceof androidx.media3.datasource.HttpDataSource.HttpDataSourceException);
            }
        }
        assertTrue(android.os.SystemClock.elapsedRealtime() - start < 5000);
        assertEquals(0, RealMediaProbe.liveSessions());
    }

    @Test public void repeatedOpenCloseDoesNotRetainNativeSessions() throws Exception {
        MemoryFactory factory = new MemoryFactory(sample("rv30-hi1.rm"));
        for (int i = 0; i < 100; i++) {
            try (DataSourceProbeInput input = new DataSourceProbeInput(factory, SPEC, true);
                 RealMediaProbe probe = new RealMediaProbe(input)) {
                assertEquals(1, RealMediaProbe.liveSessions());
                assertNotNull(probe.readPacket());
                probe.close(); probe.close();
                assertEquals(0, RealMediaProbe.liveSessions());
            }
            assertEquals(0, factory.active.get());
        }
    }

    @Test public void userSampleLocalFileDecodeAnd100Seeks() throws Exception {
        Assume.assumeTrue("Enable with --local-sample in run_realmedia_probe.py",
                "true".equals(InstrumentationRegistry.getArguments().getString("realmediaUserSample")));
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getContext();
        java.io.File file = new java.io.File(context.getCacheDir(), "nextlib-realmedia-user-sample.rmvb");
        try {
            try (InputStream input = context.getAssets().open("realmedia/user-sample.rmvb");
                 java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            DataSource.Factory factory = new DefaultDataSource.Factory(context);
            DataSpec spec = new DataSpec.Builder().setUri(Uri.fromFile(file)).build();
            try (DataSourceProbeInput input = new DataSourceProbeInput(factory, spec, true);
                 RealMediaProbe probe = new RealMediaProbe(input)) {
                JSONObject result = new JSONObject(probe.compareDecoders());
                JSONArray tracks = result.getJSONArray("tracks");
                for (int i = 0; i < tracks.length(); i++) {
                    JSONObject track = tracks.getJSONObject(i);
                    if (track.getString("codec").startsWith("rv"))
                        assertTrue(track.getBoolean("full_params_drain_flush_matches"));
                    else
                        assertTrue(track.getBoolean("full_params_pcm_flush_matches"));
                }
                Log.i(TAG, "user_sample decoder=" + result);
            }
            try (DataSourceProbeInput input = new DataSourceProbeInput(factory, spec, true);
                 RealMediaProbe probe = new RealMediaProbe(input)) {
                JSONObject result = new JSONObject(probe.seekAndDecode(100));
                assertEquals(100, result.getInt("pixel_matches"));
                Log.i(TAG, "user_sample fallback_seek=" + result + " media3_reopens=" + input.reopens);
            }
        } finally { assertTrue(!file.exists() || file.delete()); }
        assertEquals(0, RealMediaProbe.liveSessions());
    }
}
