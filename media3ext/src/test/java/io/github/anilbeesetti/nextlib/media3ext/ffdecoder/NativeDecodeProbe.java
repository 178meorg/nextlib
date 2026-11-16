package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import androidx.media3.common.Format;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;

/** Runs the production JNI decoder methods without Android Surface rendering. */
public final class NativeDecodeProbe implements AutoCloseable {
  private final Format format;
  private final FfmpegVideoDecoder video;
  private final FfmpegAudioDecoder audio;
  public int decoded;

  public NativeDecodeProbe(Format format) throws Exception {
    this.format = format;
    if (format.sampleMimeType.startsWith("video/")) {
      video = new FfmpegVideoDecoder(2, 2, 1024 * 1024, 1, format);
      audio = null;
    } else {
      video = null;
      audio = new FfmpegAudioDecoder(format, 2, 2, 1024 * 1024, false);
    }
  }

  public void decode(byte[] bytes, long timeUs, boolean reset) throws Exception {
    DecoderInputBuffer input = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT, 64);
    input.ensureSpaceForWrite(bytes.length);
    input.data.put(bytes);
    input.flip();
    input.timeUs = timeUs;
    input.format = format;
    FfmpegDecoderException error;
    if (video != null) {
      VideoDecoderOutputBuffer output = new VideoDecoderOutputBuffer(b -> {});
      error = video.decode(input, output, reset);
      if (!output.shouldBeSkipped && error == null) {
        if (output.width != format.width || output.height != format.height || output.data == null)
          throw new AssertionError("Invalid decoded video frame");
        decoded++;
      }
    } else {
      SimpleDecoderOutputBuffer output = new SimpleDecoderOutputBuffer(b -> {});
      error = audio.decode(input, output, reset);
      if (!output.shouldBeSkipped && error == null) {
        if (output.data == null || output.data.remaining() == 0) throw new AssertionError("Empty PCM");
        decoded++;
      }
    }
    if (error != null) throw error;
  }

  @Override public void close() {
    if (video != null) video.release();
    if (audio != null) audio.release();
  }
}
