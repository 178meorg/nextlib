package io.github.anilbeesetti.nextlib.media3ext.ffextractor;

import android.net.Uri;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.text.SubtitleParser;
import java.util.List;
import java.util.Map;

/** Adds signature-restricted RM/RMVB support to an existing extractor factory. */
@UnstableApi
public final class NextExtractorsFactory implements ExtractorsFactory {
  private final ExtractorsFactory upstream;

  public NextExtractorsFactory() { this(new DefaultExtractorsFactory()); }
  public NextExtractorsFactory(ExtractorsFactory upstream) { this.upstream = upstream; }

  @Override public Extractor[] createExtractors() { return prepend(upstream.createExtractors()); }
  @Override public Extractor[] createExtractors(Uri uri, Map<String, List<String>> headers) {
    return prepend(upstream.createExtractors(uri, headers));
  }
  @Override public NextExtractorsFactory setSubtitleParserFactory(SubtitleParser.Factory factory) {
    upstream.setSubtitleParserFactory(factory);
    return this;
  }
  @Override public NextExtractorsFactory experimentalSetTextTrackTranscodingEnabled(boolean enabled) {
    upstream.experimentalSetTextTrackTranscodingEnabled(enabled);
    return this;
  }
  private static Extractor[] prepend(Extractor[] defaults) {
    Extractor[] result = new Extractor[defaults.length + 1];
    // A four-byte .RMF check is cheap. Run it before MP3/other sniffers that may scan kilobytes.
    result[0] = new FfmpegExtractor();
    System.arraycopy(defaults, 0, result, 1, defaults.length);
    return result;
  }
}
