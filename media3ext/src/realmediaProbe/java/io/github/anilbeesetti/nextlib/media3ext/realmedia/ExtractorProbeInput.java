package io.github.anilbeesetti.nextlib.media3ext.realmedia;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ExtractorInput;
import java.io.IOException;

/** Direct ExtractorInput prototype. Outside-buffer seeks must return to the Media3 loader. */
@UnstableApi
final class ExtractorProbeInput implements ProbeInput {
    static final class ReopenRequired extends IOException {
        final long position;
        ReopenRequired(long position) {
            super("Extractor.read must return RESULT_SEEK before input can reopen at " + position);
            this.position = position;
        }
    }
    private final ExtractorInput input;
    ExtractorProbeInput(ExtractorInput input) { this.input = input; }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        return input.read(buffer, offset, length);
    }
    @Override public long seek(long position) throws IOException {
        if (position != input.getPosition()) throw new ReopenRequired(position);
        return position;
    }
    @Override public long getLength() { return input.getLength(); }
    @Override public boolean isSeekable() { return true; }
    @Override public boolean isCancelled() { return Thread.currentThread().isInterrupted(); }
    @Override public void close() { /* The Media3 loader owns this input. */ }
}
