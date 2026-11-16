package io.github.anilbeesetti.nextlib.media3ext.ffextractor;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;
import java.io.IOException;
import java.io.EOFException;
import java.util.HashMap;
import java.util.Map;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary;

/**
 * Experimental RealMedia extractor. Uses the caller's ExtractorInput for ALL I/O, including
 * FFmpeg's index reads and seeks; it never opens its own network connection.
 * Register alongside DefaultExtractorsFactory, and use NextRenderersFactory for Real codecs.
 */
@UnstableApi
public final class FfmpegExtractor implements Extractor {
  private final Map<Integer, TrackOutput> tracks = new HashMap<>();
  private ExtractorOutput output;
  private FfmpegDemuxer demuxer;
  private long initialSeekUs;

  @Override public boolean sniff(ExtractorInput input) throws IOException {
    byte[] signature = new byte[4];
    try {
      return input.peekFully(signature, 0, 4, true)
          && signature[0] == '.' && signature[1] == 'R'
          && signature[2] == 'M' && signature[3] == 'F';
    } catch (EOFException e) { return false; }
  }

  @Override public void init(ExtractorOutput output) { this.output = output; }

  @Override public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    if (demuxer == null) {
      if (!FfmpegLibrary.isAvailable()) throw new IOException("Nextlib native library unavailable");
      demuxer = new FfmpegDemuxer(input.getLength(), initialSeekUs);
    }
    FfmpegDemuxer.Event event = demuxer.awaitEvent();
    if (event instanceof FfmpegDemuxer.Read) {
      FfmpegDemuxer.Read read = (FfmpegDemuxer.Read) event;
      if (input.getPosition() != read.position) {
        seekPosition.position = read.position;
        return RESULT_SEEK;
      }
      // Leave the event pending on IOException, so Media3 can retry the same input position.
      read.result = input.read(read.data, 0, read.data.length);
    } else if (event instanceof FfmpegDemuxer.Track) {
      FfmpegDemuxer.Track track = (FfmpegDemuxer.Track) event;
      TrackOutput trackOutput = output.track(track.id, track.type);
      trackOutput.format(track.format);
      tracks.put(track.id, trackOutput);
    } else if (event instanceof FfmpegDemuxer.Ready) {
      long durationUs = ((FfmpegDemuxer.Ready) event).durationUs;
      output.endTracks();
      output.seekMap(new SeekMap() {
        @Override public boolean isSeekable() { return durationUs > 0 && input.getLength() != C.LENGTH_UNSET; }
        @Override public long getDurationUs() { return durationUs; }
        @Override public SeekPoints getSeekPoints(long timeUs) {
          // FFmpeg chooses the indexed keyframe; RESULT_SEEK supplies the actual byte position.
          return new SeekPoints(new SeekPoint(timeUs, 0));
        }
      });
    } else if (event instanceof FfmpegDemuxer.Sample) {
      FfmpegDemuxer.Sample sample = (FfmpegDemuxer.Sample) event;
      TrackOutput track = tracks.get(sample.track);
      if (track != null && demuxer.isCurrent(sample.generation)) {
        track.sampleData(new ParsableByteArray(sample.data), sample.data.length);
        track.sampleMetadata(sample.timeUs, sample.flags, sample.data.length, 0, null);
      }
    } else if (event instanceof FfmpegDemuxer.End) {
      boolean current = demuxer.isCurrent(((FfmpegDemuxer.End) event).generation);
      demuxer.complete(event);
      return current ? RESULT_END_OF_INPUT : RESULT_CONTINUE;
    }
    demuxer.complete(event);
    return RESULT_CONTINUE;
  }

  @Override public void seek(long position, long timeUs) {
    initialSeekUs = timeUs;
    if (demuxer != null) demuxer.seek(timeUs);
  }

  @Override public void release() {
    if (demuxer != null) {
      demuxer.close();
      demuxer = null;
    }
    tracks.clear();
  }
}
