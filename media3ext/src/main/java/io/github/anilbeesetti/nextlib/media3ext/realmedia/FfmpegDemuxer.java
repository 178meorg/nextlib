package io.github.anilbeesetti.nextlib.media3ext.realmedia;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.UnstableApi;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** RealMedia-only libavformat session, confined to the Media3 loader thread. */
@Keep
@UnstableApi
final class FfmpegDemuxer implements AutoCloseable {
    @Keep static final class DemuxException extends IOException {
        DemuxException(String message) { super(message); }
    }
    static final class Track {
        final int streamIndex;
        final Format format;
        final long startTimeUs;
        Track(int index, Format format, long startTimeUs) {
            streamIndex = index; this.format = format; this.startTimeUs = startTimeUs;
        }
    }
    static final class Info {
        final List<Track> tracks;
        final long durationUs, originUs;
        final boolean seekable;
        Info(List<Track> tracks, long duration, long origin, boolean seekable) {
            this.tracks = Collections.unmodifiableList(new ArrayList<>(tracks));
            durationUs = duration; originUs = origin; this.seekable = seekable;
        }
    }
    @Keep
    static final class Packet {
        final int streamIndex;
        final long ptsUs, dtsUs, durationUs;
        final boolean keyFrame;
        final byte[] data;
        Packet(int stream, long pts, long dts, long duration, boolean key, byte[] data) {
            streamIndex = stream; ptsUs = pts; dtsUs = dts; durationUs = duration;
            keyFrame = key; this.data = data;
        }
    }

    private final Thread owner = Thread.currentThread();
    private final List<Track> tracks = new ArrayList<>();
    private long durationUs = C.TIME_UNSET, originUs;
    private boolean seekable;
    private long context;
    final Info info;

    FfmpegDemuxer(Media3AvioInput input) throws IOException {
        if (!FfmpegLibrary.isAvailable()) throw new IOException("NextLib native library unavailable");
        try {
            boolean allowIndex = true;
            input.beginSeekBudget();
            try { context = nativeOpen(input, this, true); }
            catch (Media3AvioInput.SeekBudgetExceeded expensiveIndex) {
                input.endSeekBudget(); input.seek(0); tracks.clear();
                allowIndex = false;
                context = nativeOpen(input, this, false);
            } finally { input.endSeekBudget(); }
            if (context == 0) throw new IOException("Could not create RealMedia demuxer");
            boolean verified = seekable && durationUs != C.TIME_UNSET && durationUs > 0;
            if (verified && durationUs > 0 && durationUs != C.TIME_UNSET) {
                RealMediaTimestampMapper times = new RealMediaTimestampMapper(originUs);
                try {
                    verifySeek(input, times.toFfmpegTimeUs(Math.max(0, durationUs / 2 - 2_000_000)));
                    verifySeek(input, times.toFfmpegTimeUs(Math.max(0, durationUs - 2_000_000)));
                } catch (Media3AvioInput.SeekBudgetExceeded | DemuxException unsupported) {
                    androidx.media3.common.util.Log.d("NextLibRealMedia", "Seek probe: " + unsupported.getMessage());
                    verified = false;
                } finally { input.endSeekBudget(); }
                // A callback failure poisons the old native operation. Reopen instead of resuming it.
                nativeClose(context); context = 0;
                input.seek(0); tracks.clear();
                context = nativeOpen(input, this, allowIndex);
                if (context == 0) throw new IOException("Could not reopen RealMedia after seek probe");
            }
            info = new Info(tracks, durationUs, originUs, seekable && verified);
        } catch (IOException | RuntimeException | Error failure) {
            if (context != 0) { nativeClose(context); context = 0; }
            throw failure;
        }
    }
    private void verifySeek(Media3AvioInput input, long targetUs) throws IOException {
        input.beginSeekBudget();
        try { nativeSeek(context, targetUs); }
        catch (DemuxException error) { throw new DemuxException("target_us=" + targetUs + ", " + error.getMessage()); }
        boolean video = false;
        for (Track track : tracks) video |= androidx.media3.common.MimeTypes.isVideo(track.format.sampleMimeType);
        for (int i = 0; i < 1024; i++) {
            Packet packet = nativeRead(context);
            if (packet == null) break;
            for (Track track : tracks) if (track.streamIndex == packet.streamIndex && packet.keyFrame &&
                    (!video || androidx.media3.common.MimeTypes.isVideo(track.format.sampleMimeType))) {
                input.endSeekBudget(); return;
            }
        }
        throw new DemuxException("Seek probe could not reach a keyframe within its packet budget");
    }
    @Keep private void addTrack(int index, String codec, byte[] extra, int width, int height,
                                int sampleRate, int channels, int blockAlign, long startTimeUs) throws IOException {
        try {
            tracks.add(new Track(index, RealMediaCodecConfig.format("rm:" + index, codec, extra,
                    width, height, sampleRate, channels, blockAlign), startTimeUs));
        } catch (IllegalArgumentException error) {
            throw ParserException.createForUnsupportedContainerFeature(error.getMessage());
        }
    }
    @Keep private void setHeader(long duration, long origin, boolean canSeek) throws IOException {
        if (tracks.isEmpty()) throw ParserException.createForUnsupportedContainerFeature("No supported RealMedia tracks");
        durationUs = duration; originUs = origin; seekable = canSeek;
    }
    private void checkOwner() {
        if (Thread.currentThread() != owner || context == 0) throw new IllegalStateException("Invalid demuxer lifetime/thread");
    }
    @Nullable Packet read() throws IOException { checkOwner(); return nativeRead(context); }
    void seek(long ffmpegTimeUs) throws IOException { checkOwner(); nativeSeek(context, ffmpegTimeUs); }
    @Override public void close() {
        if (context == 0) return;
        checkOwner(); nativeClose(context); context = 0;
    }
    private static native long nativeOpen(Media3AvioInput input, FfmpegDemuxer receiver, boolean allowIndex) throws IOException;
    @Nullable private static native Packet nativeRead(long context) throws IOException;
    private static native void nativeSeek(long context, long timeUs) throws IOException;
    private static native void nativeClose(long context);
}
