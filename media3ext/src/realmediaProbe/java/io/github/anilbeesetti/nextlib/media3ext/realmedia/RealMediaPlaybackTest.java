package io.github.anilbeesetti.nextlib.media3ext.realmedia;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;

/** Exercises the public factory, real sample queues, Java decoders, AudioTrack and Surface output. */
@UnstableApi
public class RealMediaPlaybackTest {
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static File copy(String asset) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        File file = new File(context.getCacheDir(), "playback-" + asset);
        try (InputStream input = context.getAssets().open("realmedia/" + asset);
             FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        return file;
    }
    private interface Condition { boolean satisfied(); }
    private static void await(String what, AtomicReference<PlaybackException> failure, Condition condition) {
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        while (!condition.satisfied()) {
            if (failure.get() != null) throw new AssertionError(what, failure.get());
            if (SystemClock.elapsedRealtime() > deadline) fail("Timed out: " + what);
            SystemClock.sleep(20);
        }
    }

    private static void scenario(String name, int width, int height) throws Exception {
        scenario(name, width, height, null, null);
    }
    private static void scenario(String name, int width, int height, Uri overrideUri,
                                 DataSource.Factory overrideFactory) throws Exception {
        scenario(name, width, height, overrideUri, overrideFactory, 0, () -> {}, 0);
    }
    private static void scenario(String name, int width, int height, Uri overrideUri,
                                 DataSource.Factory overrideFactory, long resumeMs,
                                 Runnable beforeSeek, int randomSeeks) throws Exception {
        scenario(name, width, height, overrideUri, overrideFactory, resumeMs, beforeSeek, randomSeeks, false);
    }
    private static void scenario(String name, int width, int height, Uri overrideUri,
                                 DataSource.Factory overrideFactory, long resumeMs,
                                 Runnable beforeSeek, int randomSeeks, boolean wholeFile) throws Exception {
        File file = copy(name);
        AtomicReference<ExoPlayer> player = new AtomicReference<>();
        AtomicReference<PlaybackException> failure = new AtomicReference<>();
        AtomicInteger state = new AtomicInteger();
        AtomicInteger images = new AtomicInteger();
        AtomicLong framePts = new AtomicLong(-1), position = new AtomicLong(), duration = new AtomicLong();
        AtomicReference<Boolean> seekable = new AtomicReference<>(false);
        if (width <= 0 || height <= 0) {
            try (android.media.MediaMetadataRetriever metadata = new android.media.MediaMetadataRetriever()) {
                metadata.setDataSource(file.getAbsolutePath());
                width = Integer.parseInt(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                height = Integer.parseInt(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            }
        }
        try (ImageReader output = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)) {
            main(() -> {
                output.setOnImageAvailableListener(reader -> {
                    try (Image image = reader.acquireLatestImage()) { if (image != null) images.incrementAndGet(); }
                }, new Handler(Looper.getMainLooper()));
                Context context = InstrumentationRegistry.getInstrumentation().getContext();
                DataSource.Factory data = overrideFactory == null ? new DefaultDataSource.Factory(context) : overrideFactory;
                NextRenderersFactory renderers = new NextRenderersFactory(context);
                renderers.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
                ExoPlayer instance = new ExoPlayer.Builder(context, renderers)
                        .setMediaSourceFactory(new NextMediaSourceFactory(data,
                                new DefaultMediaSourceFactory(data, new DefaultExtractorsFactory())))
                        .build();
                instance.setVolume(0);
                instance.setVideoSurface(output.getSurface());
                instance.setVideoFrameMetadataListener((pts, releaseNs, format, mediaFormat) -> framePts.set(pts));
                instance.addListener(new Player.Listener() {
                    @Override public void onPlayerError(PlaybackException error) { failure.set(error); }
                    @Override public void onPlaybackStateChanged(int next) {
                        state.set(next);
                        duration.set(instance.getDuration());
                        seekable.set(instance.isCurrentMediaItemSeekable());
                    }
                    @Override public void onTimelineChanged(androidx.media3.common.Timeline timeline, int reason) {
                        duration.set(instance.getDuration());
                        seekable.set(instance.isCurrentMediaItemSeekable());
                    }
                });
                player.set(instance);
                instance.setMediaItem(MediaItem.fromUri(overrideUri == null ? Uri.fromFile(file) : overrideUri));
                if (resumeMs > 0) instance.seekTo(resumeMs);
                instance.prepare(); instance.play();
            });
            try {
                await("first decoded Surface image", failure, () -> images.get() > 0 && state.get() == Player.STATE_READY);
                if (resumeMs > 0) assertTrue("Resume must decode near the requested position",
                        framePts.get() >= resumeMs * 1000 - 100_000);
                await("validated indexed fixture publishes a seekable timeline", failure, () -> seekable.get());
                if (wholeFile) {
                    main(() -> player.get().setPlaybackSpeed(8));
                    long deadline = SystemClock.elapsedRealtime() + 600_000;
                    int observations = 0;
                    while (state.get() != Player.STATE_ENDED) {
                        if (failure.get() != null) throw new AssertionError("Whole file playback", failure.get());
                        if (SystemClock.elapsedRealtime() > deadline) fail("Whole file playback timed out");
                        main(() -> {
                            position.set(player.get().getCurrentPosition());
                            // Both values are observed on the main thread while playing, before release.
                            if (state.get() == Player.STATE_READY && position.get() > 5000
                                    && position.get() < duration.get() - 3000)
                                assertTrue("Video must remain near the audio media clock: " + framePts.get() + "/" + position.get(),
                                        Math.abs(framePts.get() / 1000 - position.get()) < 1500);
                        });
                        observations++;
                        SystemClock.sleep(200);
                    }
                    assertTrue("Observe sustained playback", observations > 100);
                    android.util.Log.i("NextLibRealMediaProbe", name + " whole_file_playback=PASS speed=8 duration_ms="
                            + duration.get() + " observations=" + observations + " images=" + images.get());
                    return;
                }
                main(() -> { player.get().pause(); position.set(player.get().getCurrentPosition()); });
                long paused = position.get();
                SystemClock.sleep(250);
                main(() -> position.set(player.get().getCurrentPosition()));
                assertTrue("Pause must stop the media clock", Math.abs(position.get() - paused) < 150);

                main(() -> { player.get().setPlaybackSpeed(2); player.get().play(); });
                long targetMs = Math.min(30_000, duration.get() / 2);
                int before = images.get();
                beforeSeek.run();
                main(() -> player.get().seekTo(targetMs));
                await("post-seek decoded picture near target", failure, () -> images.get() > before
                        && framePts.get() >= targetMs * 1000 - 100_000
                        && framePts.get() <= targetMs * 1000 + 2_000_000);
                main(() -> position.set(player.get().getCurrentPosition()));
                assertTrue(Math.abs(position.get() - targetMs) < 2000);

                java.util.Random random = new java.util.Random(0x524d5642);
                for (int i = 0; i < randomSeeks; i++) {
                    long randomMs = (long) (random.nextDouble() * Math.max(1, duration.get() - 3000));
                    int previous = images.get();
                    main(() -> player.get().seekTo(randomMs));
                    await("random seek " + i + " decoded Surface image", failure,
                            () -> images.get() > previous && framePts.get() >= randomMs * 1000 - 100_000
                                    && framePts.get() <= randomMs * 1000 + 2_000_000);
                    main(() -> position.set(player.get().getCurrentPosition()));
                    assertTrue("Random seek media clock", Math.abs(position.get() - randomMs) < 2000);
                }

                main(() -> {
                    player.get().setPlaybackSpeed(1);
                    player.get().seekTo(Math.max(0, duration.get() - 500));
                });
                await("EOS after draining delayed video", failure, () -> state.get() == Player.STATE_ENDED);
                assertNull(failure.get());
                android.util.Log.i("NextLibRealMediaProbe", name + " public_factory_playback=PASS images=" + images.get());
            } finally { main(() -> player.get().release()); }
        } finally { assertTrue(!file.exists() || file.delete()); }
    }

    @Test public void rv30CookPauseSpeedSeekSurfaceAndEos() throws Exception { scenario("rv30-hi1.rm", 320, 240); }
    @Test public void rv40CookPauseSpeedSeekSurfaceAndEos() throws Exception { scenario("rv40-blade.rm", 640, 480); }
    @Test public void mp4RetainsDefaultExtractionAndPlayback() throws Exception { scenario("regression.mp4", 0, 0); }
    @Test public void mkvRetainsDefaultExtractionAndPlayback() throws Exception { scenario("regression.mkv", 0, 0); }
    @Test public void publicFactorySurvivesOneHundredDecodedSeeksAndResume() throws Exception {
        scenario("rv40-blade.rm", 640, 480, null, null, 15_000, () -> {}, 100);
    }
    @Test public void hlsUsesTheExistingFactoryWithoutOpeningTheProbe() {
        AtomicInteger calls = new AtomicInteger();
        androidx.media3.exoplayer.source.MediaSource expected =
                (androidx.media3.exoplayer.source.MediaSource) java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[] {androidx.media3.exoplayer.source.MediaSource.class},
                        (proxy, method, args) -> null);
        androidx.media3.exoplayer.source.MediaSource.Factory delegate =
                (androidx.media3.exoplayer.source.MediaSource.Factory) java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[] {androidx.media3.exoplayer.source.MediaSource.Factory.class},
                        (proxy, method, args) -> {
                            calls.incrementAndGet();
                            return method.getName().equals("createMediaSource") ? expected : proxy;
                        });
        NextMediaSourceFactory factory = new NextMediaSourceFactory(
                () -> { throw new AssertionError("HLS must not open the RealMedia probe"); }, delegate);
        assertSame(expected, factory.createMediaSource(MediaItem.fromUri("https://example.invalid/playlist.m3u8")));
        assertEquals(1, calls.get());
        androidx.media3.exoplayer.upstream.CmcdConfiguration.Factory cmcd =
                (androidx.media3.exoplayer.upstream.CmcdConfiguration.Factory) java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[] {androidx.media3.exoplayer.upstream.CmcdConfiguration.Factory.class},
                        (proxy, method, args) -> null);
        assertSame(factory, factory.setCmcdConfigurationFactory(cmcd));
        assertSame(factory, factory.experimentalParseSubtitlesDuringExtraction(false));
        assertSame(factory, factory.experimentalSetCodecsToParseWithinGopSampleDependencies(1));
        assertEquals("Delegate configuration must survive the RealMedia wrapper", 4, calls.get());
    }
    @Test public void midstreamInputErrorRetriesThroughMedia3() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        java.util.concurrent.atomic.AtomicBoolean failNext = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger failures = new AtomicInteger();
        DataSource.Factory factory = () -> new DataSource() {
            final DataSource delegate = new DefaultDataSource.Factory(context).createDataSource();
            @Override public void addTransferListener(androidx.media3.datasource.TransferListener listener) {
                delegate.addTransferListener(listener);
            }
            @Override public long open(androidx.media3.datasource.DataSpec spec) throws java.io.IOException { return delegate.open(spec); }
            @Override public int read(byte[] data, int offset, int length) throws java.io.IOException {
                if (failNext.compareAndSet(true, false)) {
                    failures.incrementAndGet(); throw new java.io.IOException("Injected midstream input failure");
                }
                return delegate.read(data, offset, length);
            }
            @Override public Uri getUri() { return delegate.getUri(); }
            @Override public void close() throws java.io.IOException { delegate.close(); }
        };
        scenario("rv30-hi1.rm", 320, 240, null, factory, 0, () -> failNext.set(true), 0);
        assertEquals("One original I/O error must recover without rebuilding the player", 1, failures.get());
    }
    private static byte[] bytes(String asset) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("realmedia/" + asset)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[32768]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
    private static DefaultHttpDataSource.Factory httpFactory() {
        return new DefaultHttpDataSource.Factory().setConnectTimeoutMs(3000).setReadTimeoutMs(3000)
                .setDefaultRequestProperties(java.util.Collections.singletonMap("Authorization", "Bearer playback-probe"));
    }
    private static void assertAuthentication(RealMediaPrototypeTest.HttpFixture http) {
        assertTrue(http.requests.size() > 2);
        for (java.util.Map<String, String> request : http.requests)
            assertEquals("Bearer playback-probe", request.get("Authorization"));
    }
    @Test public void httpCacheAndAuthenticationRetainThePlaybackPath() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        File directory = new File(context.getCacheDir(), "realmedia-cache-" + System.nanoTime());
        SimpleCache cache = new SimpleCache(directory, new LeastRecentlyUsedCacheEvictor(16 * 1024 * 1024),
                new StandaloneDatabaseProvider(context));
        try (RealMediaPrototypeTest.HttpFixture http = new RealMediaPrototypeTest.HttpFixture(bytes("rv30-hi1.rm"), false)) {
            try {
                CacheDataSource.Factory data = new CacheDataSource.Factory().setCache(cache)
                        .setUpstreamDataSourceFactory(httpFactory());
                scenario("rv30-hi1.rm", 320, 240, http.uri(), data);
                assertAuthentication(http);
                assertTrue(cache.getCacheSpace() > 0);
            } finally { cache.release(); }
        } finally {
            // Test cache belongs exclusively to this scenario.
            SimpleCache.delete(directory, new StandaloneDatabaseProvider(context));
        }
    }
    @Test public void httpsUsesTheApplicationsTlsDataSource() throws Exception {
        java.security.KeyStore keys = java.security.KeyStore.getInstance("PKCS12");
        char[] password = "nextlib-test-only".toCharArray();
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("realmedia/test-tls.p12")) {
            keys.load(input, password);
        }
        javax.net.ssl.KeyManagerFactory km = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, password);
        javax.net.ssl.TrustManagerFactory tm = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        javax.net.ssl.SSLContext tls = javax.net.ssl.SSLContext.getInstance("TLS");
        tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        javax.net.ssl.SSLSocketFactory previous = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory();
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
        try (RealMediaPrototypeTest.HttpFixture http = new RealMediaPrototypeTest.HttpFixture(bytes("rv40-blade.rm"), false, tls)) {
            scenario("rv40-blade.rm", 640, 480, http.uri(), httpFactory());
            assertAuthentication(http);
        } finally { javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(previous); }
    }
    @Test public void userFilePublicFactoryPlayback() throws Exception {
        Assume.assumeTrue("Optional private fixture", "true".equals(
                InstrumentationRegistry.getArguments().getString("realmediaUserSample")));
        scenario("user-sample.rmvb", 704, 396);
    }
    @Test public void userFileSequentialPlaybackKeepsAudioVideoClocksAligned() throws Exception {
        Assume.assumeTrue("Optional sustained playback", "true".equals(
                InstrumentationRegistry.getArguments().getString("realmediaLongPlayback")));
        scenario("user-sample.rmvb", 704, 396, null, null, 0, () -> {}, 0, true);
    }
}
