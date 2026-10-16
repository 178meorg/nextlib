package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import static org.junit.Assert.*;

import androidx.media3.common.C;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.util.ArrayDeque;
import org.junit.Test;

public class FfmpegDrainingDecoderTest {
    private static final class Fake extends FfmpegDrainingDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer> {
        final ArrayDeque<Long> frames = new ArrayDeque<>();
        boolean eos;
        int sent;
        Fake() { super(new DecoderInputBuffer[2], new SimpleDecoderOutputBuffer[1]); }
        @Override public String getName() { return "fake"; }
        @Override protected DecoderInputBuffer createInputBuffer() { return new DecoderInputBuffer(1); }
        @Override protected SimpleDecoderOutputBuffer createOutputBuffer() {
            return new SimpleDecoderOutputBuffer(this::releaseOutputBuffer);
        }
        @Override protected FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
            return new FfmpegDecoderException("fake", error);
        }
        @Override protected boolean sendInputBuffer(DecoderInputBuffer input, boolean reset) {
            if (reset) { frames.clear(); eos = false; }
            if (input.isEndOfStream()) { frames.addLast(300L); eos = true; }
            else { frames.addLast(input.timeUs - 10); frames.addLast(input.timeUs + 10); sent++; }
            return true;
        }
        @Override protected int receiveOutputBuffer(SimpleDecoderOutputBuffer output) {
            if (!frames.isEmpty()) { output.init(frames.removeFirst(), 4); return OUTPUT_FRAME; }
            return eos ? OUTPUT_END_OF_STREAM : OUTPUT_UNAVAILABLE;
        }
    }

    private static DecoderInputBuffer input(Fake decoder) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        DecoderInputBuffer input;
        while ((input = decoder.dequeueInputBuffer()) == null) {
            if (System.nanoTime() > deadline) fail("input wait timed out");
            Thread.yield();
        }
        return input;
    }

    private static SimpleDecoderOutputBuffer output(Fake decoder) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        SimpleDecoderOutputBuffer output;
        while ((output = decoder.dequeueOutputBuffer()) == null) {
            if (System.nanoTime() > deadline) fail("output wait timed out");
            Thread.yield();
        }
        return output;
    }

    @Test public void drainsEveryFrameAndDelayedEosWithOneOutputBuffer() throws Exception {
        Fake decoder = new Fake();
        try {
            DecoderInputBuffer packet = input(decoder);
            packet.timeUs = 100; decoder.queueInputBuffer(packet);
            DecoderInputBuffer eos = input(decoder);
            eos.addFlag(C.BUFFER_FLAG_END_OF_STREAM); decoder.queueInputBuffer(eos);
            for (long expected : new long[]{90, 110, 300}) {
                SimpleDecoderOutputBuffer frame = output(decoder);
                assertFalse(frame.isEndOfStream()); assertEquals(expected, frame.timeUs); frame.release();
            }
            SimpleDecoderOutputBuffer end = output(decoder);
            assertTrue(end.isEndOfStream()); end.release();
            assertEquals(1, decoder.sent);
            assertNull(decoder.dequeueOutputBuffer());
        } finally { decoder.release(); }
    }

    @Test public void seekDropsOldGenerationEvenWhenRendererHoldsOnlyOutputBuffer() throws Exception {
        Fake decoder = new Fake();
        try {
            DecoderInputBuffer packet = input(decoder); packet.timeUs = 100; decoder.queueInputBuffer(packet);
            SimpleDecoderOutputBuffer held = output(decoder);
            decoder.flush(); decoder.setOutputStartTimeUs(1000);
            DecoderInputBuffer replacement = input(decoder); replacement.timeUs = 1010;
            decoder.queueInputBuffer(replacement);
            held.release();
            SimpleDecoderOutputBuffer first = output(decoder);
            assertEquals(1000, first.timeUs); first.release();
            SimpleDecoderOutputBuffer second = output(decoder);
            assertEquals(1020, second.timeUs); second.release();
        } finally { decoder.release(); }
    }

    @Test public void prerollUsesDecodedFrameTimeRatherThanCompressedPacketTime() throws Exception {
        Fake decoder = new Fake();
        try {
            decoder.setOutputStartTimeUs(100);
            DecoderInputBuffer packet = input(decoder); packet.timeUs = 100; decoder.queueInputBuffer(packet);
            SimpleDecoderOutputBuffer frame = output(decoder);
            assertEquals(110, frame.timeUs);
            assertEquals(1, frame.skippedOutputBufferCount);
            frame.release();
        } finally { decoder.release(); }
    }
}
