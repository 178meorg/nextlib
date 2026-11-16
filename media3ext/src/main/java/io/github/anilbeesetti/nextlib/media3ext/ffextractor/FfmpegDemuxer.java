package io.github.anilbeesetti.nextlib.media3ext.ffextractor;

import androidx.annotation.Keep;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;

/** One worker owns the native context. One outstanding event bounds prefetch and memory use. */
@Keep
@UnstableApi
final class FfmpegDemuxer implements AutoCloseable {
  static class Event { boolean complete; }
  static final class Read extends Event {
    final long position; final byte[] data; int result;
    Read(long position, byte[] data) { this.position = position; this.data = data; }
  }
  static final class Track extends Event {
    final int id, type; final Format format;
    Track(int id, int type, Format format) { this.id = id; this.type = type; this.format = format; }
  }
  static final class Ready extends Event {
    final long durationUs;
    Ready(long durationUs) { this.durationUs = durationUs; }
  }
  static final class Sample extends Event {
    final int track, flags, generation; final long timeUs; final byte[] data;
    Sample(int track, int flags, long timeUs, byte[] data, int generation) {
      this.track = track; this.flags = flags; this.timeUs = timeUs;
      this.data = data; this.generation = generation;
    }
  }
  static final class End extends Event {
    final int generation;
    End(int generation) { this.generation = generation; }
  }

  private final Thread worker;
  private Event pending;
  private IOException failure;
  private boolean closed;
  private long requestedSeekUs = C.TIME_UNSET;
  private int generation;
  private int operationGeneration;

  FfmpegDemuxer(long length, long initialSeekUs) {
    if (initialSeekUs > 0) requestedSeekUs = initialSeekUs;
    worker = new Thread(() -> run(length), "Nextlib-RM-demux");
    worker.start();
  }

  synchronized Event awaitEvent() throws IOException {
    while (pending == null && failure == null && !closed) await();
    if (failure != null) throw failure;
    if (closed) throw new InterruptedIOException("Demuxer released");
    return pending;
  }

  synchronized void complete(Event event) {
    event.complete = true;
    if (pending == event) pending = null;
    notifyAll();
  }

  private synchronized void exchange(Event event) throws IOException {
    if (closed) throw new InterruptedIOException("Demuxer released");
    pending = event;
    notifyAll();
    while (!event.complete && !closed) await();
    if (closed) throw new InterruptedIOException("Demuxer released");
  }

  synchronized void seek(long timeUs) {
    requestedSeekUs = timeUs;
    generation++;
    notifyAll();
  }

  synchronized boolean isCurrent(int value) { return generation == value; }

  private void await() throws InterruptedIOException {
    try { wait(); } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Demuxer interrupted");
    }
  }

  private void run(long length) {
    long handle = 0;
    try {
      handle = nativeOpen(length);
      if (handle == 0) throw new IOException("Native demuxer initialization failed");
      boolean ended = false;
      while (true) {
        long seekUs;
        synchronized (this) {
          while (ended && requestedSeekUs == C.TIME_UNSET && !closed) await();
          if (closed) break;
          seekUs = requestedSeekUs;
          requestedSeekUs = C.TIME_UNSET;
          operationGeneration = generation;
        }
        if (seekUs != C.TIME_UNSET) {
          nativeSeek(handle, seekUs);
          ended = false;
        }
        if (!nativeRead(handle)) {
          exchange(new End(operationGeneration));
          ended = true;
        }
      }
    } catch (Throwable e) {
      synchronized (this) {
        failure = e instanceof IOException ? (IOException) e : new IOException(e);
        notifyAll();
      }
    } finally {
      if (handle != 0) nativeClose(handle);
    }
  }

  // JNI callbacks run on worker; Media3's loader thread performs all input/output operations.
  private int readAt(long position, byte[] bytes) throws IOException {
    Read read = new Read(position, bytes);
    exchange(read);
    return read.result;
  }

  private void onTrack(int id, String mime, int width, int height, int rate, int channels,
      byte[] extra, int blockAlign, int bits, int codecTag) throws IOException {
    boolean video = mime.startsWith("video/");
    Format.Builder format = new Format.Builder().setId(id).setSampleMimeType(mime);
    if (mime.startsWith("video/x-nextlib-") || mime.startsWith("audio/x-nextlib-")) {
      byte[] parameters = ByteBuffer.allocate(12).putInt(blockAlign).putInt(bits).putInt(codecTag).array();
      format.setInitializationData(Arrays.asList(extra, parameters));
    } else {
      // Standard MIME types may be handled by MediaCodec. Every initializationData entry becomes
      // a csd-N buffer: Nextlib's private parameters must NEVER become an AAC csd-1 buffer.
      format.setInitializationData(extra.length == 0 ? Collections.emptyList() : Collections.singletonList(extra));
    }
    if (video) format.setWidth(width).setHeight(height);
    else format.setSampleRate(rate).setChannelCount(channels);
    exchange(new Track(id, video ? C.TRACK_TYPE_VIDEO : C.TRACK_TYPE_AUDIO, format.build()));
  }

  private void onReady(long durationUs) throws IOException { exchange(new Ready(durationUs)); }
  private void onSample(int track, byte[] data, long timeUs, boolean key) throws IOException {
    exchange(new Sample(track, key ? C.BUFFER_FLAG_KEY_FRAME : 0, timeUs, data, operationGeneration));
  }

  @Override public void close() {
    synchronized (this) { closed = true; notifyAll(); }
    // No native network calls: the worker can only block in the interruptible exchange above.
    boolean interrupted = false;
    while (worker.isAlive()) {
      try { worker.join(); } catch (InterruptedException e) { interrupted = true; }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private native long nativeOpen(long length) throws IOException;
  private native boolean nativeRead(long handle) throws IOException;
  private native void nativeSeek(long handle, long timeUs) throws IOException;
  private native void nativeClose(long handle);
}
