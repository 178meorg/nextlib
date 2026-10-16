package io.github.anilbeesetti.nextlib.media3ext.realmedia;

import java.io.IOException;

/** Test-only synchronous AVIO contract. No FFmpeg structures cross JNI. */
interface ProbeInput extends AutoCloseable {
    int read(byte[] buffer, int offset, int length) throws IOException;
    long seek(long absolutePosition) throws IOException;
    long getLength();
    boolean isSeekable();
    boolean isCancelled();
    void close() throws IOException;
}
