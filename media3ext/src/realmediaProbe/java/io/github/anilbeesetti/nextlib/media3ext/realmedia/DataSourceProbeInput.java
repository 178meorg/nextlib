package io.github.anilbeesetti.nextlib.media3ext.realmedia;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.io.IOException;
import java.io.InterruptedIOException;

/** Fallback prototype: a loader owns the DataSource and can synchronously reopen it. */
@UnstableApi
final class DataSourceProbeInput implements ProbeInput {
    private final DataSource source;
    private final DataSpec original;
    private final boolean randomAccess;
    private long length;
    private long position;
    private boolean opened;
    private volatile boolean cancelled;
    int reopens;

    DataSourceProbeInput(DataSource.Factory factory, DataSpec original, boolean randomAccess)
            throws IOException {
        this.source = factory.createDataSource();
        this.original = original;
        this.randomAccess = randomAccess;
        open(0);
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("RealMedia probe cancelled");
    }

    private void open(long target) throws IOException {
        checkCancelled();
        // subrange retains the original URI, headers, cache key and request flags.
        DataSpec request = original.subrange(target);
        opened = true; // close even when open throws part way through acquiring a connection.
        try {
            long remaining = source.open(request);
            if (remaining != C.LENGTH_UNSET) length = Math.addExact(target, remaining);
            else if (target == 0) length = C.LENGTH_UNSET;
            position = target;
            checkCancelled();
        } catch (IOException | RuntimeException failure) {
            try { closeSource(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private void closeSource() throws IOException {
        if (opened) {
            opened = false;
            source.close();
        }
    }

    @Override public int read(byte[] buffer, int offset, int count) throws IOException {
        checkCancelled();
        int result = source.read(buffer, offset, count);
        if (result > 0) position += result;
        checkCancelled();
        return result;
    }

    @Override public long seek(long target) throws IOException {
        checkCancelled();
        if (!randomAccess || target < 0 || (length != C.LENGTH_UNSET && target > length))
            throw new IOException("Input cannot seek to " + target);
        if (target != position) {
            closeSource();
            reopens++;
            open(target);
        }
        return position;
    }

    void cancel() { cancelled = true; }
    @Override public long getLength() { return length; }
    @Override public boolean isSeekable() { return randomAccess; }
    @Override public boolean isCancelled() { return cancelled || Thread.currentThread().isInterrupted(); }
    @Override public void close() throws IOException { closeSource(); }
}
