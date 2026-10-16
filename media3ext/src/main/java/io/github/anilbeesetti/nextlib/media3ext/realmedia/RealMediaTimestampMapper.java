package io.github.anilbeesetti.nextlib.media3ext.realmedia;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import java.util.HashMap;
import java.util.Map;

/** One immutable origin for every track, with missing-time fallback kept separate from reordering. */
@UnstableApi
final class RealMediaTimestampMapper {
    private final long originUs;
    private long fallbackAnchorUs;
    private boolean afterSeek;
    private final Map<Integer, Long> streamStartUs = new HashMap<>();
    private final Map<Integer, Long> nextTimeUs = new HashMap<>();
    RealMediaTimestampMapper(long originUs) {
        this.originUs = originUs == C.TIME_UNSET ? 0 : originUs;
        fallbackAnchorUs = this.originUs;
    }
    void setStreamStartTimeUs(int stream, long startUs) {
        if (startUs != C.TIME_UNSET) streamStartUs.put(stream, startUs);
    }

    long map(int stream, long ptsUs, long dtsUs, long durationUs) {
        long raw;
        if (ptsUs != C.TIME_UNSET) raw = ptsUs;
        else if (dtsUs != C.TIME_UNSET) raw = dtsUs;
        else if (nextTimeUs.containsKey(stream)) raw = nextTimeUs.get(stream);
        else {
            Long start = streamStartUs.get(stream);
            raw = start == null ? fallbackAnchorUs : afterSeek ? Math.max(start, fallbackAnchorUs) : start;
        }
        if (durationUs > 0 && durationUs != C.TIME_UNSET) nextTimeUs.put(stream, add(raw, durationUs));
        else nextTimeUs.put(stream, raw);
        // Do not clamp negatives or force PTS monotonicity: packets remain in decode order.
        long result = raw - originUs;
        if (((raw ^ originUs) & (raw ^ result)) < 0) throw new ArithmeticException("Timestamp overflow");
        return result;
    }
    long toFfmpegTimeUs(long timeUs) { return add(timeUs, originUs); }
    void seek(long timeUs) { nextTimeUs.clear(); fallbackAnchorUs = toFfmpegTimeUs(timeUs); afterSeek = true; }
    private static long add(long a, long b) {
        long result = a + b;
        if (((a ^ result) & (b ^ result)) < 0) throw new ArithmeticException("Timestamp overflow");
        return result;
    }
}
