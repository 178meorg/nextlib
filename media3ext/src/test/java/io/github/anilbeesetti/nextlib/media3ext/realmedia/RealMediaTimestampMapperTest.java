package io.github.anilbeesetti.nextlib.media3ext.realmedia;

import static org.junit.Assert.*;
import androidx.media3.common.C;
import org.junit.Test;

public class RealMediaTimestampMapperTest {
    @Test public void tracksShareOriginAndKeepNegativeTimesAndDecodeOrder() {
        RealMediaTimestampMapper mapper = new RealMediaTimestampMapper(10_000_000);
        assertEquals(-1000, mapper.map(0, 9_999_000, C.TIME_UNSET, 1000));
        assertEquals(100_000, mapper.map(1, 10_100_000, C.TIME_UNSET, 20_000));
        assertEquals(80_000, mapper.map(1, 10_080_000, 10_120_000, 20_000));
        assertEquals(10_080_000, mapper.toFfmpegTimeUs(80_000));
    }
    @Test public void missingTimesUseDtsThenCadenceAndResetAfterSeek() {
        RealMediaTimestampMapper mapper = new RealMediaTimestampMapper(-2000);
        assertEquals(5000, mapper.map(0, C.TIME_UNSET, 3000, 1000));
        assertEquals(6000, mapper.map(0, C.TIME_UNSET, C.TIME_UNSET, 1000));
        mapper.seek(5000);
        assertEquals(5000, mapper.map(0, C.TIME_UNSET, C.TIME_UNSET, 1000));
    }
    @Test public void missingTimesRetainTrackStartOffsetsAndUseSeekAnchorAfterReset() {
        RealMediaTimestampMapper mapper = new RealMediaTimestampMapper(10_000_000);
        mapper.setStreamStartTimeUs(0, 9_999_000);
        mapper.setStreamStartTimeUs(1, 10_100_000);
        assertEquals(-1000, mapper.map(0, C.TIME_UNSET, C.TIME_UNSET, 1000));
        assertEquals(100_000, mapper.map(1, C.TIME_UNSET, C.TIME_UNSET, 20_000));
        assertEquals(120_000, mapper.map(1, C.TIME_UNSET, C.TIME_UNSET, 20_000));
        mapper.seek(500_000);
        assertEquals(500_000, mapper.map(1, C.TIME_UNSET, C.TIME_UNSET, 20_000));
        assertEquals(10_500_000, mapper.toFfmpegTimeUs(500_000));
    }
}
