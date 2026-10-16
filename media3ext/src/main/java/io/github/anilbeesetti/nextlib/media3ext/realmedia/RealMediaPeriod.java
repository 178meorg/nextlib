package io.github.anilbeesetti.nextlib.media3ext.realmedia;

import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.SampleQueue;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.Loader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashMap;
import java.util.Map;

/** Keeps sample storage, load control, renderer timing and retries on Media3's existing machinery. */
@UnstableApi
final class RealMediaPeriod implements MediaPeriod, Loader.Callback<RealMediaPeriod.ReadTask> {
    interface InfoListener { void onInfo(FfmpegDemuxer.Info info); }
    private static final long PREROLL_US = 2_000_000;
    private final DataSource.Factory inputs;
    @Nullable private final TransferListener transferListener;
    private final DataSpec spec;
    private final Allocator allocator;
    private final LoadErrorHandlingPolicy errorPolicy;
    private final MediaSourceEventListener.EventDispatcher events;
    private final InfoListener infoListener;
    private final Handler handler = Util.createHandlerForCurrentLooper();
    private final Loader loader = new Loader("NextLib:RealMedia");
    private final ConditionVariable gate = new ConditionVariable();
    @Nullable private Callback callback;
    @Nullable private ReadTask active;
    @Nullable private volatile FfmpegDemuxer.Info info;
    private SampleQueue[] queues = new SampleQueue[0];
    private TrackGroupArray groups = TrackGroupArray.EMPTY;
    private boolean[] enabled = new boolean[0];
    private boolean prepared, released, finished;
    private long pendingResetUs = 0;
    private long lastSeekUs, playbackPositionUs;
    private long discontinuityUs = C.TIME_UNSET;

    RealMediaPeriod(MediaItem item, DataSource.Factory inputs, @Nullable TransferListener listener,
                    Allocator allocator, LoadErrorHandlingPolicy errors,
                    MediaSourceEventListener.EventDispatcher events, InfoListener infoListener) {
        this.inputs = inputs; transferListener = listener; this.allocator = allocator;
        errorPolicy = errors; this.events = events; this.infoListener = infoListener;
        MediaItem.LocalConfiguration local = java.util.Objects.requireNonNull(item.localConfiguration);
        spec = new DataSpec.Builder().setUri(local.uri).setKey(local.customCacheKey)
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build();
    }
    @Override public void prepare(Callback callback, long positionUs) {
        this.callback = callback; pendingResetUs = lastSeekUs = playbackPositionUs = Math.max(0, positionUs);
        startLoading();
    }
    private void startLoading() {
        if (released || finished || loader.isLoading()) return;
        active = new ReadTask(Math.max(0, pendingResetUs == C.TIME_UNSET ? lastSeekUs : pendingResetUs));
        loader.startLoading(active, this, errorPolicy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA));
    }
    private void prepareQueues(ReadTask task, FfmpegDemuxer.Info header) {
        if (released || task != active || task.cancelled) return;
        if (!prepared) {
            queues = new SampleQueue[header.tracks.size()];
            enabled = new boolean[queues.length];
            TrackGroup[] tracks = new TrackGroup[queues.length];
            for (int i = 0; i < queues.length; i++) {
                FfmpegDemuxer.Track track = header.tracks.get(i);
                queues[i] = SampleQueue.createWithoutDrm(allocator);
                queues[i].format(track.format);
                tracks[i] = new TrackGroup("rm:" + track.streamIndex, track.format);
            }
            groups = new TrackGroupArray(tracks);
            info = header;
            if (!header.seekable && task.targetUs > 0) task.targetUs = lastSeekUs = playbackPositionUs = 0;
            pendingResetUs = C.TIME_UNSET;
            prepared = true;
            infoListener.onInfo(header);
            java.util.Objects.requireNonNull(callback).onPrepared(this);
        } else {
            pendingResetUs = C.TIME_UNSET;
            requestLoading();
        }
    }
    private void requestLoading() { if (!released && callback != null) callback.onContinueLoadingRequested(this); }
    private void resetQueues() { for (SampleQueue queue : queues) queue.reset(); }
    @Override public void maybeThrowPrepareError() throws IOException { loader.maybeThrowError(); }
    @Override public TrackGroupArray getTrackGroups() { return groups; }
    @Override public long selectTracks(ExoTrackSelection[] selections, boolean[] retain,
                                      SampleStream[] streams, boolean[] reset, long positionUs) {
        for (int i = 0; i < streams.length; i++) {
            if (streams[i] != null && (selections[i] == null || !retain[i])) {
                enabled[((Stream) streams[i]).track] = false; streams[i] = null;
            }
            if (streams[i] == null && selections[i] != null) {
                int track = groups.indexOf(selections[i].getTrackGroup());
                if (track < 0) throw new IllegalArgumentException("Unknown RealMedia track");
                streams[i] = new Stream(track); enabled[track] = true; reset[i] = true;
            }
        }
        return info != null && !info.seekable ? lastSeekUs : positionUs;
    }
    @Override public void discardBuffer(long positionUs, boolean toKeyframe) {
        if (pendingResetUs != C.TIME_UNSET) return;
        for (int i = 0; i < queues.length; i++) queues[i].discardTo(positionUs, toKeyframe, enabled[i]);
    }
    @Override public long readDiscontinuity() {
        if (pendingResetUs != C.TIME_UNSET) return C.TIME_UNSET;
        long result = discontinuityUs; discontinuityUs = C.TIME_UNSET; return result;
    }
    @Override public long seekToUs(long positionUs) {
        if (info != null && !info.seekable && positionUs != 0) return lastSeekUs;
        pendingResetUs = lastSeekUs = playbackPositionUs = Math.max(0, positionUs);
        finished = false; discontinuityUs = C.TIME_UNSET;
        if (loader.isLoading()) loader.cancelLoading();
        else { loader.clearFatalError(); resetQueues(); requestLoading(); }
        return lastSeekUs;
    }
    @Override public long getAdjustedSeekPositionUs(long positionUs, SeekParameters parameters) {
        return info != null && !info.seekable ? 0 : positionUs;
    }
    @Override public long getBufferedPositionUs() {
        if (finished) return C.TIME_END_OF_SOURCE;
        if (pendingResetUs != C.TIME_UNSET) return pendingResetUs;
        long buffered = Long.MAX_VALUE;
        for (int i = 0; i < queues.length; i++) if (enabled[i])
            buffered = Math.min(buffered, queues[i].getLargestQueuedTimestampUs());
        return buffered == Long.MAX_VALUE || buffered == Long.MIN_VALUE ? lastSeekUs : Math.max(lastSeekUs, buffered);
    }
    @Override public long getNextLoadPositionUs() { return getBufferedPositionUs(); }
    @Override public boolean continueLoading(LoadingInfo loadingInfo) {
        playbackPositionUs = loadingInfo.playbackPositionUs;
        if (released || finished || loader.hasFatalError()) return false;
        boolean wasOpen = gate.open();
        if (!loader.isLoading()) { startLoading(); return true; }
        return wasOpen;
    }
    @Override public boolean isLoading() { return loader.isLoading() && gate.isOpen(); }
    @Override public void reevaluateBuffer(long positionUs) {}
    void release() {
        if (released) return;
        released = true; gate.open(); handler.removeCallbacksAndMessages(null);
        loader.release(() -> { for (SampleQueue queue : queues) queue.release(); });
    }
    @Override public void onLoadStarted(ReadTask task, long elapsedMs, long durationMs, int retryCount) {
        events.loadStarted(new LoadEventInfo(task.id, spec, elapsedMs), C.DATA_TYPE_MEDIA,
                C.TRACK_TYPE_UNKNOWN, null, C.SELECTION_REASON_UNKNOWN, null, task.targetUs,
                info == null ? C.TIME_UNSET : info.durationUs, retryCount);
    }
    @Override public void onLoadCompleted(ReadTask task, long elapsedMs, long durationMs) {
        finished = true; pendingResetUs = C.TIME_UNSET;
        if (info != null && info.durationUs == C.TIME_UNSET && task.endUs > 0) {
            info = new FfmpegDemuxer.Info(info.tracks, task.endUs, info.originUs, false);
            infoListener.onInfo(info);
        }
        errorPolicy.onLoadTaskConcluded(task.id);
        events.loadCompleted(task.event(elapsedMs, durationMs), C.DATA_TYPE_MEDIA,
                C.TRACK_TYPE_UNKNOWN, null, C.SELECTION_REASON_UNKNOWN, null, task.targetUs,
                info == null ? C.TIME_UNSET : info.durationUs);
        requestLoading();
    }
    @Override public void onLoadCanceled(ReadTask task, long elapsedMs, long durationMs, boolean loaderReleased) {
        errorPolicy.onLoadTaskConcluded(task.id);
        events.loadCanceled(task.event(elapsedMs, durationMs), C.DATA_TYPE_MEDIA,
                C.TRACK_TYPE_UNKNOWN, null, C.SELECTION_REASON_UNKNOWN, null, task.targetUs,
                info == null ? C.TIME_UNSET : info.durationUs);
        if (!loaderReleased && !released) { resetQueues(); requestLoading(); }
    }
    @Override public Loader.LoadErrorAction onLoadError(ReadTask task, long elapsedMs, long durationMs,
                                                       IOException error, int errorCount) {
        MediaLoadData data = new MediaLoadData(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_UNKNOWN, null,
                C.SELECTION_REASON_UNKNOWN, null, Util.usToMs(task.targetUs),
                info == null ? C.TIME_UNSET : Util.usToMs(info.durationUs));
        long delay = errorPolicy.getRetryDelayMsFor(new LoadErrorHandlingPolicy.LoadErrorInfo(
                task.event(elapsedMs, durationMs), data, error, errorCount));
        boolean fatal = error instanceof ParserException || error instanceof FfmpegDemuxer.DemuxException
                || error instanceof Media3AvioInput.SeekBudgetExceeded || delay == C.TIME_UNSET
                || (prepared && info != null && !info.seekable && playbackPositionUs > 0)
                || errorCount > errorPolicy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA);
        events.loadError(task.event(elapsedMs, durationMs), C.DATA_TYPE_MEDIA, C.TRACK_TYPE_UNKNOWN,
                null, C.SELECTION_REASON_UNKNOWN, null, task.targetUs,
                info == null ? C.TIME_UNSET : info.durationUs, error, fatal);
        if (fatal) { errorPolicy.onLoadTaskConcluded(task.id); return Loader.DONT_RETRY_FATAL; }
        if (prepared) {
            task.targetUs = pendingResetUs = lastSeekUs = Math.max(lastSeekUs, playbackPositionUs);
            discontinuityUs = task.targetUs;
            resetQueues();
        }
        return Loader.createRetryAction(false, delay);
    }
    private final class Stream implements SampleStream {
        final int track;
        Stream(int track) { this.track = track; }
        @Override public boolean isReady() { return pendingResetUs == C.TIME_UNSET && queues[track].isReady(finished); }
        @Override public void maybeThrowError() throws IOException { loader.maybeThrowError(); queues[track].maybeThrowError(); }
        @Override public int readData(FormatHolder holder, DecoderInputBuffer buffer, int flags) {
            if (pendingResetUs != C.TIME_UNSET) return C.RESULT_NOTHING_READ;
            return queues[track].read(holder, buffer, flags, finished);
        }
        @Override public int skipData(long timeUs) {
            if (pendingResetUs != C.TIME_UNSET) return 0;
            int count = queues[track].getSkipCount(timeUs, finished); queues[track].skip(count); return count;
        }
    }
    final class ReadTask implements Loader.Loadable {
        final long id = LoadEventInfo.getNewId();
        volatile boolean cancelled;
        volatile long targetUs;
        @Nullable volatile Media3AvioInput input;
        long bytes, endUs;
        ReadTask(long targetUs) { this.targetUs = targetUs; }
        @Override public void cancelLoad() { cancelled = true; if (input != null) input.cancel(); gate.open(); }
        LoadEventInfo event(long elapsedMs, long durationMs) {
            return new LoadEventInfo(id, spec, spec.uri, java.util.Collections.emptyMap(), elapsedMs, durationMs, bytes);
        }
        @Override public void load() throws IOException {
            try (Media3AvioInput source = new Media3AvioInput(inputs, spec, transferListener)) {
                input = source;
                if (cancelled) source.cancel();
                try (FfmpegDemuxer demuxer = new FfmpegDemuxer(source)) {
                    FfmpegDemuxer.Info header = demuxer.info;
                    FfmpegDemuxer.Info previous = info;
                    if (previous != null) {
                        if (previous.tracks.size() != header.tracks.size()) throw new FfmpegDemuxer.DemuxException("RealMedia tracks changed");
                        for (int i = 0; i < previous.tracks.size(); i++)
                            if (!previous.tracks.get(i).format.equals(header.tracks.get(i).format))
                                throw new FfmpegDemuxer.DemuxException("RealMedia track format changed");
                    }
                    gate.close();
                    handler.post(() -> prepareQueues(this, header));
                    gate.block();
                    if (cancelled) return;
                    RealMediaTimestampMapper timestamps = new RealMediaTimestampMapper(header.originUs);
                    for (FfmpegDemuxer.Track track : header.tracks)
                        timestamps.setStreamStartTimeUs(track.streamIndex, track.startTimeUs);
                    long prerollUs = Math.max(0, targetUs - PREROLL_US);
                    if (targetUs > 0) {
                        if (!header.seekable) throw new FfmpegDemuxer.DemuxException("Input has no bounded seek index");
                        source.beginSeekBudget();
                        demuxer.seek(timestamps.toFfmpegTimeUs(prerollUs));
                        timestamps.seek(prerollUs);
                    }
                    Map<Integer, Integer> indices = new HashMap<>();
                    boolean[] reachedTarget = new boolean[queues.length];
                    for (int i = 0; i < header.tracks.size(); i++) indices.put(header.tracks.get(i).streamIndex, i);
                    long intervalStart = source.bytesTransferred();
                    int intervalPackets = 0;
                    while (!cancelled) {
                        gate.block();
                        if (cancelled) break;
                        FfmpegDemuxer.Packet packet = demuxer.read();
                        if (packet == null) break;
                        Integer index = indices.get(packet.streamIndex);
                        if (index == null) continue;
                        long timeUs = timestamps.map(packet.streamIndex, packet.ptsUs, packet.dtsUs, packet.durationUs);
                        endUs = Math.max(endUs, timeUs + Math.max(0, packet.durationUs));
                        boolean audio = MimeTypes.isAudio(header.tracks.get(index).format.sampleMimeType);
                        queues[index].sampleData(new ParsableByteArray(packet.data), packet.data.length);
                        queues[index].sampleMetadata(timeUs, packet.keyFrame || audio ? C.BUFFER_FLAG_KEY_FRAME : 0,
                                packet.data.length, 0, null);
                        reachedTarget[index] |= timeUs >= targetUs + 100_000;
                        boolean allReached = true;
                        for (boolean reached : reachedTarget) allReached &= reached;
                        if (allReached) source.endSeekBudget();
                        if (++intervalPackets >= 128 || source.bytesTransferred() - intervalStart >= 256 * 1024) {
                            intervalPackets = 0; intervalStart = source.bytesTransferred();
                            gate.close(); handler.post(RealMediaPeriod.this::requestLoading);
                        }
                    }
                } finally { bytes += source.bytesTransferred(); input = null; }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new InterruptedIOException("RealMedia load interrupted");
            }
        }
    }
}
