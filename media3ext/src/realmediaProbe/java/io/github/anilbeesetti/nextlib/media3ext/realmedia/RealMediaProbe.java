package io.github.anilbeesetti.nextlib.media3ext.realmedia;

import java.io.IOException;

/** Test-only demux session: every native call stays on its owning loader thread. */
final class RealMediaProbe implements AutoCloseable {
    static { System.loadLibrary("realmedia_probe"); }
    private final Thread owner = Thread.currentThread();
    private long context;

    RealMediaProbe(ProbeInput input) throws IOException { context = nativeOpen(input); }
    private void checkOwner() {
        if (owner != Thread.currentThread() || context == 0)
            throw new IllegalStateException("Probe is closed or called off its loader thread");
    }
    String compareDecoders() throws IOException { checkOwner(); return nativeCompare(context); }
    String seekAndDecode(int count) throws IOException { checkOwner(); return nativeSeek(context, count); }
    long[] readPacket() throws IOException { checkOwner(); return nativeRead(context); }
    void checkBufferedSeeks() throws IOException { checkOwner(); nativeBufferCheck(context); }
    @Override public void close() {
        if (context == 0) return;
        checkOwner();
        nativeClose(context);
        context = 0;
    }
    private static native long nativeOpen(ProbeInput input) throws IOException;
    private static native String nativeCompare(long context) throws IOException;
    private static native String nativeSeek(long context, int count) throws IOException;
    private static native long[] nativeRead(long context) throws IOException;
    private static native void nativeBufferCheck(long context) throws IOException;
    private static native void nativeClose(long context);
    static native int liveSessions();
}
