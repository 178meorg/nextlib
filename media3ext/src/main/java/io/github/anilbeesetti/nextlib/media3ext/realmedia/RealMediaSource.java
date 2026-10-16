package io.github.anilbeesetti.nextlib.media3ext.realmedia;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.source.BaseMediaSource;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import java.util.HashSet;
import java.util.Set;

/** The single fallback selected after the direct Extractor/AVIO prototype failed. */
@UnstableApi
final class RealMediaSource extends BaseMediaSource {
    private final MediaItem item;
    private final DataSource.Factory inputs;
    private final LoadErrorHandlingPolicy errors;
    private final Set<RealMediaPeriod> periods = new HashSet<>();
    @Nullable private TransferListener transferListener;

    RealMediaSource(MediaItem item, DataSource.Factory inputs, LoadErrorHandlingPolicy errors) {
        this.item = item; this.inputs = inputs; this.errors = errors;
    }
    @Override public MediaItem getMediaItem() { return item; }
    @Override protected void prepareSourceInternal(@Nullable TransferListener listener) {
        transferListener = listener;
        refreshSourceInfo(new SinglePeriodTimeline(C.TIME_UNSET, false, false, false, null, item));
    }
    @Override public void maybeThrowSourceInfoRefreshError() {}
    @Override public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
        RealMediaPeriod period = new RealMediaPeriod(item, inputs, transferListener, allocator, errors,
                createEventDispatcher(id), info -> refreshSourceInfo(new SinglePeriodTimeline(
                        info.durationUs, info.seekable, false, false, null, item)));
        periods.add(period);
        return period;
    }
    @Override public void releasePeriod(MediaPeriod mediaPeriod) {
        RealMediaPeriod period = (RealMediaPeriod) mediaPeriod;
        periods.remove(period); period.release();
    }
    @Override protected void releaseSourceInternal() {
        for (RealMediaPeriod period : periods) period.release();
        periods.clear(); transferListener = null;
    }
}
