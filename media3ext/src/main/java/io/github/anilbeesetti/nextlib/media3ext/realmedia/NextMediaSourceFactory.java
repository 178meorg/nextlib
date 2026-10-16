package io.github.anilbeesetti.nextlib.media3ext.realmedia;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.upstream.CmcdConfiguration;
import androidx.media3.exoplayer.source.CompositeMediaSource;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.Loader;
import androidx.media3.extractor.text.SubtitleParser;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;

/**
 * Adds header-gated RealMedia to an application's existing source factory.
 * Keep application subtitle/ASS wrappers outside this factory. HLS/DASH/SS use the delegate
 * directly; progressive inputs use one four-byte Media3 probe before selecting the implementation.
 */
@UnstableApi
public final class NextMediaSourceFactory implements MediaSource.Factory {
    private final DataSource.Factory inputs;
    private final MediaSource.Factory delegate;
    private LoadErrorHandlingPolicy errors = new DefaultLoadErrorHandlingPolicy();

    public NextMediaSourceFactory(DataSource.Factory inputs, MediaSource.Factory delegate) {
        this.inputs = Objects.requireNonNull(inputs); this.delegate = Objects.requireNonNull(delegate);
    }
    @Override public NextMediaSourceFactory setCmcdConfigurationFactory(CmcdConfiguration.Factory factory) {
        delegate.setCmcdConfigurationFactory(factory); return this;
    }
    @SuppressWarnings("deprecation")
    @Override public NextMediaSourceFactory experimentalParseSubtitlesDuringExtraction(boolean parse) {
        delegate.experimentalParseSubtitlesDuringExtraction(parse); return this;
    }
    @Override public NextMediaSourceFactory experimentalSetCodecsToParseWithinGopSampleDependencies(int codecs) {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs); return this;
    }
    @Override public NextMediaSourceFactory setDrmSessionManagerProvider(DrmSessionManagerProvider provider) {
        delegate.setDrmSessionManagerProvider(provider); return this;
    }
    @Override public NextMediaSourceFactory setLoadErrorHandlingPolicy(LoadErrorHandlingPolicy policy) {
        errors = Objects.requireNonNull(policy); delegate.setLoadErrorHandlingPolicy(policy); return this;
    }
    @Override public NextMediaSourceFactory setSubtitleParserFactory(SubtitleParser.Factory factory) {
        delegate.setSubtitleParserFactory(factory); return this;
    }
    @Override public int[] getSupportedTypes() {
        int[] types = delegate.getSupportedTypes();
        for (int type : types) if (type == C.CONTENT_TYPE_OTHER) return types;
        int[] extended = java.util.Arrays.copyOf(types, types.length + 1);
        extended[types.length] = C.CONTENT_TYPE_OTHER; return extended;
    }
    @Override public MediaSource createMediaSource(MediaItem item) {
        MediaItem.LocalConfiguration local = Objects.requireNonNull(item.localConfiguration);
        if (Util.inferContentTypeForUriAndMimeType(local.uri, local.mimeType) != C.CONTENT_TYPE_OTHER)
            return delegate.createMediaSource(item);
        return new SniffingSource(item, inputs, delegate, errors);
    }

    private static final class SniffingSource extends CompositeMediaSource<Integer>
            implements Loader.Callback<SniffingSource.Probe> {
        private final MediaItem item;
        private final DataSource.Factory inputs;
        private final MediaSource.Factory delegate;
        private final LoadErrorHandlingPolicy errors;
        private final Loader loader = new Loader("NextLib:RealMediaHeader");
        @Nullable private MediaSource child;
        @Nullable private TransferListener listener;
        SniffingSource(MediaItem item, DataSource.Factory inputs, MediaSource.Factory delegate,
                       LoadErrorHandlingPolicy errors) {
            this.item = item; this.inputs = inputs; this.delegate = delegate; this.errors = errors;
        }
        @Override public MediaItem getMediaItem() { return item; }
        @Override protected void prepareSourceInternal(@Nullable TransferListener listener) {
            super.prepareSourceInternal(listener); this.listener = listener;
            loader.startLoading(new Probe(), this, errors.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA));
        }
        @Override public void maybeThrowSourceInfoRefreshError() throws IOException {
            loader.maybeThrowError(); super.maybeThrowSourceInfoRefreshError();
        }
        @Override protected void onChildSourceInfoRefreshed(Integer id, MediaSource source, Timeline timeline) {
            refreshSourceInfo(timeline);
        }
        @Override public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startUs) {
            return Objects.requireNonNull(child).createPeriod(id, allocator, startUs);
        }
        @Override public void releasePeriod(MediaPeriod period) { Objects.requireNonNull(child).releasePeriod(period); }
        @Override protected void releaseSourceInternal() {
            loader.release(); super.releaseSourceInternal(); child = null; listener = null;
        }
        @Override public void onLoadCompleted(Probe probe, long elapsedMs, long durationMs) {
            errors.onLoadTaskConcluded(probe.id);
            child = probe.realMedia ? new RealMediaSource(item, inputs, errors) : delegate.createMediaSource(item);
            prepareChildSource(0, child);
        }
        @Override public void onLoadCanceled(Probe probe, long elapsedMs, long durationMs, boolean released) {
            errors.onLoadTaskConcluded(probe.id);
        }
        @Override public Loader.LoadErrorAction onLoadError(Probe probe, long elapsedMs, long durationMs,
                                                          IOException error, int count) {
            LoadEventInfo event = new LoadEventInfo(probe.id, probe.spec, elapsedMs);
            long delay = errors.getRetryDelayMsFor(new LoadErrorHandlingPolicy.LoadErrorInfo(event,
                    new MediaLoadData(C.DATA_TYPE_MEDIA), error, count));
            if (delay == C.TIME_UNSET || count > errors.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA)) {
                errors.onLoadTaskConcluded(probe.id); return Loader.DONT_RETRY_FATAL;
            }
            return Loader.createRetryAction(false, delay);
        }
        final class Probe implements Loader.Loadable {
            final long id = LoadEventInfo.getNewId();
            final DataSpec spec;
            volatile boolean cancelled;
            boolean realMedia;
            Probe() {
                MediaItem.LocalConfiguration local = Objects.requireNonNull(item.localConfiguration);
                spec = new DataSpec.Builder().setUri(local.uri).setKey(local.customCacheKey).build();
            }
            @Override public void cancelLoad() { cancelled = true; }
            @Override public void load() throws IOException {
                DataSource source = inputs.createDataSource();
                TransferListener transfer = listener;
                if (transfer != null) source.addTransferListener(transfer);
                try {
                    source.open(spec);
                    byte[] header = new byte[4];
                    int size = 0;
                    while (!cancelled && size < header.length) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Header probe cancelled");
                        int count = source.read(header, size, header.length - size);
                        if (count == C.RESULT_END_OF_INPUT) break;
                        if (count == 0) throw new IOException("DataSource returned an empty header read");
                        size += count;
                    }
                    realMedia = size == 4 && header[0] == '.' && header[1] == 'R' && header[2] == 'M' && header[3] == 'F';
                } finally { source.close(); }
            }
        }
    }
}
