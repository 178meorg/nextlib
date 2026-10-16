package io.github.anilbeesetti.nextlib.media3ext.realmedia;

import androidx.annotation.Nullable;
import androidx.annotation.Keep;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;
import java.io.InterruptedIOException;

/** A loader owns this input; FFmpeg never opens a URI or creates a network connection. */
@UnstableApi
@Keep
final class Media3AvioInput implements AutoCloseable {
    static final long SEEK_BYTE_BUDGET = 8L * 1024 * 1024;
    private static final long SEEK_TIME_BUDGET_MS = 30_000;
    private final DataSource source;
    private final DataSpec original;
    private long length = C.LENGTH_UNSET;
    private long position;
    private boolean opened;
    private volatile boolean cancelled;
    private long bytesTransferred;
    private long budgetEnd = Long.MAX_VALUE;
    private long budgetDeadlineMs = Long.MAX_VALUE;

    static final class SeekBudgetExceeded extends IOException {
        SeekBudgetExceeded() { super("RealMedia seek exceeded its bounded input budget"); }
    }
    private static final class BudgetExceeded extends RuntimeException {}

    Media3AvioInput(DataSource.Factory factory, DataSpec spec, @Nullable TransferListener listener) throws IOException {
        original = spec;
        source = factory.createDataSource();
        if (listener != null) source.addTransferListener(listener);
        source.addTransferListener(new TransferListener() {
            @Override public void onTransferInitializing(DataSource source, DataSpec spec, boolean network) {}
            @Override public void onTransferStart(DataSource source, DataSpec spec, boolean network) {}
            @Override public void onTransferEnd(DataSource source, DataSpec spec, boolean network) {}
            @Override public void onBytesTransferred(DataSource source, DataSpec spec, boolean network, int count) {
                bytesTransferred += count;
                if (bytesTransferred > budgetEnd || android.os.SystemClock.elapsedRealtime() > budgetDeadlineMs)
                    throw new BudgetExceeded();
            }
        });
        open(0);
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("RealMedia load cancelled");
    }
    private void open(long target) throws IOException {
        checkCancelled();
        checkBudget();
        opened = true;
        try {
            long remaining = source.open(original.subrange(target));
            if (remaining != C.LENGTH_UNSET) {
                if (remaining > Long.MAX_VALUE - target) throw new IOException("Invalid input length");
                length = target + remaining;
            }
            position = target;
            checkCancelled();
            checkBudget();
        } catch (BudgetExceeded exceeded) {
            close(); throw new SeekBudgetExceeded();
        } catch (IOException | RuntimeException error) {
            try { close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }
    // Methods below are called synchronously from the owning loader's AVIO callbacks.
    public int read(byte[] data, int offset, int count) throws IOException {
        checkCancelled();
        checkBudget();
        try {
            int result = source.read(data, offset, count);
            if (result > 0) position += result;
            checkCancelled();
            checkBudget();
            return result;
        } catch (BudgetExceeded exceeded) { throw new SeekBudgetExceeded(); }
    }
    public long seek(long target) throws IOException {
        checkCancelled();
        if (target < 0 || (length != C.LENGTH_UNSET && target > length))
            throw new IOException("RealMedia seek outside input: " + target);
        if (target != position) { close(); open(target); }
        return position;
    }
    public long getLength() { return length; }
    public boolean isSeekable() { return length != C.LENGTH_UNSET; }
    public boolean isCancelled() { return cancelled || Thread.currentThread().isInterrupted(); }
    void cancel() { cancelled = true; }
    private void checkBudget() throws SeekBudgetExceeded {
        if (bytesTransferred > budgetEnd || android.os.SystemClock.elapsedRealtime() > budgetDeadlineMs)
            throw new SeekBudgetExceeded();
    }
    void beginSeekBudget() {
        budgetEnd = bytesTransferred + SEEK_BYTE_BUDGET;
        budgetDeadlineMs = android.os.SystemClock.elapsedRealtime() + SEEK_TIME_BUDGET_MS;
    }
    void endSeekBudget() { budgetEnd = budgetDeadlineMs = Long.MAX_VALUE; }
    long bytesTransferred() { return bytesTransferred; }
    @Override public void close() throws IOException {
        if (opened) { opened = false; source.close(); }
    }
}
