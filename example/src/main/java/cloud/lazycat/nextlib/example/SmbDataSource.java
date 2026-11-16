package cloud.lazycat.nextlib.example;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import java.io.EOFException;
import java.io.IOException;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;

/** Media3 byte-range requests map to SMB random access, including FFmpeg's index reads. */
@UnstableApi
final class SmbDataSource extends BaseDataSource {
    private final SmbServers.Server server;
    private SmbFile file;
    private SmbRandomAccessFile input;
    private Uri uri;
    private long remaining;
    private boolean opened;
    // Extractor sniffing can ask for a single byte. Never turn each peek into an SMB round trip.
    private final byte[] readAhead = new byte[64 * 1024];
    private int bufferPosition, bufferLength;
    SmbDataSource(SmbServers.Server server) { super(true); this.server = server; }

    @Override public long open(DataSpec spec) throws IOException {
        transferInitializing(spec);
        if (!"smb".equals(spec.uri.getScheme()) || !server.id.equals(spec.uri.getAuthority()))
            throw new IOException("SMB server does not match this playback session");
        uri = spec.uri;
        bufferPosition = bufferLength = 0;
        try {
            file = server.file(uri.getPath(), false);
            input = file.openRandomAccess("r");
            long length = input.length();
            if (spec.position > length)
                throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
            input.seek(spec.position);
            remaining = spec.length == C.LENGTH_UNSET ? length - spec.position : Math.min(spec.length, length - spec.position);
            opened = true;
            transferStarted(spec);
            return remaining;
        } catch (IOException | RuntimeException error) {
            try { close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        if (input == null) throw new IOException("SMB source is not open");
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
        if (bufferPosition == bufferLength) {
            int fetched = input.read(readAhead, 0, (int)Math.min(readAhead.length, remaining));
            if (fetched < 0) throw new EOFException("SMB file ended before the reported length");
            if (fetched == 0) throw new IOException("SMB read made no progress");
            bufferPosition = 0;
            bufferLength = fetched;
            bytesTransferred(fetched);
        }
        int count = Math.min(length, bufferLength - bufferPosition);
        System.arraycopy(readAhead, bufferPosition, buffer, offset, count);
        bufferPosition += count;
        remaining -= count;
        return count;
    }
    @Override public Uri getUri() { return uri; }
    @Override public void close() throws IOException {
        try { if (input != null) input.close(); }
        finally {
            input = null;
            if (file != null) file.close();
            file = null; uri = null; remaining = 0;
            bufferPosition = bufferLength = 0;
            if (opened) { opened = false; transferEnded(); }
        }
    }
}
