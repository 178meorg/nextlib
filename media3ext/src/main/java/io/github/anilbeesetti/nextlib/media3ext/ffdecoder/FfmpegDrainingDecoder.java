package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.DecoderOutputBuffer;
import java.util.ArrayDeque;

/** Bounded FFmpeg scheduling: send each packet once, receive all frames, then drain at EOS. */
@UnstableApi
abstract class FfmpegDrainingDecoder<I extends DecoderInputBuffer, O extends DecoderOutputBuffer>
        implements Decoder<I, O, FfmpegDecoderException> {
    protected static final int OUTPUT_UNAVAILABLE = 0;
    protected static final int OUTPUT_FRAME = 1;
    protected static final int OUTPUT_END_OF_STREAM = 2;

    private final Object lock = new Object();
    private final ArrayDeque<I> availableInputs = new ArrayDeque<>();
    private final ArrayDeque<I> queuedInputs = new ArrayDeque<>();
    private final ArrayDeque<O> availableOutputs = new ArrayDeque<>();
    private final ArrayDeque<O> queuedOutputs = new ArrayDeque<>();
    private final Thread thread;
    @Nullable private I dequeuedInput;
    @Nullable private FfmpegDecoderException exception;
    private boolean released;
    private boolean resetRequired;
    private long generation;
    private long outputStartTimeUs = C.TIME_UNSET;
    private int skippedOutputs;

    protected FfmpegDrainingDecoder(I[] inputs, O[] outputs) {
        Assertions.checkArgument(inputs.length > 0 && outputs.length > 0);
        for (int i = 0; i < inputs.length; i++) availableInputs.add(inputs[i] = createInputBuffer());
        for (int i = 0; i < outputs.length; i++) availableOutputs.add(outputs[i] = createOutputBuffer());
        thread = new Thread(this::run, "ExoPlayer:SimpleDecoder");
        thread.start();
    }

    protected abstract I createInputBuffer();
    protected abstract O createOutputBuffer();
    protected abstract FfmpegDecoderException createUnexpectedDecodeException(Throwable error);
    /** Returns false when malformed input was skipped. EOS must be sent as a null native packet. */
    protected abstract boolean sendInputBuffer(I input, boolean reset) throws FfmpegDecoderException;
    protected abstract int receiveOutputBuffer(O output) throws FfmpegDecoderException;

    protected final void setInitialInputBufferSize(int size) {
        synchronized (lock) {
            Assertions.checkState(queuedInputs.isEmpty() && dequeuedInput == null);
            for (I input : availableInputs) input.ensureSpaceForWrite(size);
        }
    }

    @Override public final void setOutputStartTimeUs(long timeUs) {
        synchronized (lock) { outputStartTimeUs = timeUs; }
    }

    @Override @Nullable public final I dequeueInputBuffer() throws FfmpegDecoderException {
        synchronized (lock) {
            maybeThrow();
            Assertions.checkState(!released && dequeuedInput == null);
            return dequeuedInput = availableInputs.pollFirst();
        }
    }

    @Override public final void queueInputBuffer(I input) throws FfmpegDecoderException {
        synchronized (lock) {
            maybeThrow();
            Assertions.checkArgument(input == dequeuedInput);
            queuedInputs.addLast(input);
            dequeuedInput = null;
            lock.notifyAll();
        }
    }

    @Override @Nullable public final O dequeueOutputBuffer() throws FfmpegDecoderException {
        synchronized (lock) { maybeThrow(); return queuedOutputs.pollFirst(); }
    }

    protected void releaseOutputBuffer(O output) {
        synchronized (lock) {
            output.clear();
            availableOutputs.addLast(output);
            lock.notifyAll();
        }
    }

    private void recycleInput(I input) {
        input.clear();
        availableInputs.addLast(input);
    }

    @Override public final void flush() {
        synchronized (lock) {
            generation++;
            resetRequired = true;
            skippedOutputs = 0;
            if (dequeuedInput != null) { recycleInput(dequeuedInput); dequeuedInput = null; }
            while (!queuedInputs.isEmpty()) recycleInput(queuedInputs.removeFirst());
            while (!queuedOutputs.isEmpty()) queuedOutputs.removeFirst().release();
            lock.notifyAll();
        }
    }

    @Override public void release() {
        synchronized (lock) { released = true; lock.notifyAll(); }
        boolean interrupted = false;
        // Native frames cannot be freed while the decode thread still uses the context.
        while (thread.isAlive()) {
            try { thread.join(); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void maybeThrow() throws FfmpegDecoderException {
        if (exception != null) throw exception;
    }

    private void run() {
        I pending = null;
        long pendingGeneration = -1;
        boolean sent = false, reset = false, produced = false, firstSample = false;
        try {
            while (true) {
                O output;
                synchronized (lock) {
                    while (true) {
                        if (pending != null && pendingGeneration != generation) {
                            recycleInput(pending);
                            pending = null;
                            sent = false;
                            firstSample = false;
                        }
                        if (released) return;
                        if ((pending != null || !queuedInputs.isEmpty()) && !availableOutputs.isEmpty()) break;
                        lock.wait();
                    }
                    if (pending == null) {
                        pending = queuedInputs.removeFirst();
                        pendingGeneration = generation;
                        reset = resetRequired;
                        resetRequired = false;
                        sent = false;
                        produced = false;
                        firstSample = (!reset && firstSample) || pending.isFirstSample();
                    }
                    output = availableOutputs.removeFirst();
                }

                int result;
                try {
                    boolean accepted = sent || sendInputBuffer(pending, reset);
                    sent = true;
                    result = accepted ? receiveOutputBuffer(output) : OUTPUT_UNAVAILABLE;
                    if (pending.isEndOfStream() && result == OUTPUT_UNAVAILABLE)
                        throw new FfmpegDecoderException("FFmpeg requested input after EOS");
                    if (!pending.isEndOfStream() && result == OUTPUT_END_OF_STREAM)
                        throw new FfmpegDecoderException("FFmpeg ended before EOS input");
                } catch (FfmpegDecoderException | RuntimeException | OutOfMemoryError error) {
                    synchronized (lock) {
                        output.release();
                        recycleInput(pending);
                        pending = null;
                        exception = error instanceof FfmpegDecoderException
                                ? (FfmpegDecoderException) error : createUnexpectedDecodeException(error);
                    }
                    return;
                }

                synchronized (lock) {
                    if (pendingGeneration != generation || released) {
                        output.release();
                        recycleInput(pending);
                        pending = null;
                        firstSample = false;
                    } else if (result == OUTPUT_UNAVAILABLE) {
                        output.release();
                        if (!produced) skippedOutputs++;
                        recycleInput(pending);
                        pending = null;
                    } else if (result == OUTPUT_FRAME) {
                        produced = true;
                        if (output.shouldBeSkipped || (outputStartTimeUs != C.TIME_UNSET && output.timeUs < outputStartTimeUs)) {
                            skippedOutputs++;
                            output.release();
                        } else {
                            if (firstSample) { output.addFlag(C.BUFFER_FLAG_FIRST_SAMPLE); firstSample = false; }
                            output.skippedOutputBufferCount = skippedOutputs;
                            skippedOutputs = 0;
                            queuedOutputs.addLast(output);
                        }
                    } else if (result == OUTPUT_END_OF_STREAM) {
                        output.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
                        queuedOutputs.addLast(output);
                        recycleInput(pending);
                        pending = null;
                    } else {
                        output.release();
                        recycleInput(pending);
                        pending = null;
                        exception = new FfmpegDecoderException("Unexpected FFmpeg receive status: " + result);
                        return;
                    }
                }
            }
        } catch (InterruptedException error) {
            synchronized (lock) { exception = createUnexpectedDecodeException(error); }
            Thread.currentThread().interrupt();
        } finally {
            synchronized (lock) { if (pending != null) recycleInput(pending); }
        }
    }
}
