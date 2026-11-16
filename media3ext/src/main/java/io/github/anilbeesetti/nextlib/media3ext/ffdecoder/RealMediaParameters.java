package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;

/** Additional codec parameters emitted by FfmpegExtractor, separate from FFmpeg extradata. */
@UnstableApi
final class RealMediaParameters {
  private RealMediaParameters() {}
  static int get(Format format, int index) {
    if (format.sampleMimeType == null || !format.sampleMimeType.contains("/x-nextlib-")
        || format.initializationData.size() < 2 || format.initializationData.get(1).length != 12) return 0;
    return ByteBuffer.wrap(format.initializationData.get(1)).getInt(index * 4);
  }
}
