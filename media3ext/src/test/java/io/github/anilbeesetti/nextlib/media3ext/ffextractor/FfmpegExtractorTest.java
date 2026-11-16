package io.github.anilbeesetti.nextlib.media3ext.ffextractor;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.*;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NativeDecodeProbe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class FfmpegExtractorTest {
  private static boolean loaded;
  @Before public void loadNative() {
    String library = System.getProperty("nextlib.host.library");
    assumeNotNull(library);
    if (loaded) return;
    System.load(library);
    loaded = true;
    FfmpegLibrary.setLibraries(); // The host JNI library above is already loaded in this sandbox.
  }

  @Test public void sniffIsSignatureBasedAndDoesNotConsumeInput() throws Exception {
    FfmpegExtractor extractor = new FfmpegExtractor();
    for (byte[] data : new byte[][] {{}, {'.'}, {'.','R','M'}, {'f','t','y','p'}, {0,0,1,0}}) {
      assertFalse(extractor.sniff(input(data, 0)));
    }
    ExtractorInput input = input(new byte[] {'.','R','M','F',0,0}, 0);
    assertTrue(extractor.sniff(input));
    assertEquals(0, input.getPosition());
    assertTrue(new NextExtractorsFactory().createExtractors().length > 1);
    assertTrue(new NextExtractorsFactory().createExtractors()[0] instanceof FfmpegExtractor);
  }

  @Test(timeout = 30000) public void realSamplesProduceVideoAndAudioWithCodecParameters() throws Exception {
    for (String name : new String[] {"rv10.rm", "rv30-clip.rm", "rv40-clip.rm"}) {
      try (Harness h = new Harness(sample(name))) {
        h.readSamples(100);
        assertTrue(name, h.output.formats.values().stream().anyMatch(f -> f.sampleMimeType.startsWith("video/")));
        assertTrue(name, h.output.formats.values().stream().anyMatch(f -> f.sampleMimeType.startsWith("audio/")));
        assertTrue(h.output.samples > 0);
        for (Format format : h.output.formats.values()) {
          if (format.sampleMimeType.contains("/x-nextlib-")) assertEquals(2, format.initializationData.size());
          else assertTrue(format.initializationData.size() <= 1);
          if (format.sampleMimeType.startsWith("video/")) assertTrue(format.width > 0 && format.height > 0);
        }
      }
    }
  }

  @Test(timeout = 30000) public void aacInRealMediaHasOnlyAudioSpecificConfig() throws Exception {
    try (Harness h = new Harness(sample("aac.rm"))) {
      h.readSamples(100);
      Format aac = h.output.formats.values().iterator().next();
      assertEquals("audio/mp4a-latm", aac.sampleMimeType);
      assertEquals(1, aac.initializationData.size());
      androidx.media3.extractor.AacUtil.Config config =
          androidx.media3.extractor.AacUtil.parseAudioSpecificConfig(aac.initializationData.get(0));
      assertEquals(aac.channelCount, config.channelCount);
      assertEquals(aac.sampleRate, config.sampleRateHz);
    }
  }

  @Test(timeout = 30000) public void extractedPacketsDecodeWithProductionJni() throws Exception {
    for (String name : new String[] {"rv10.rm", "rv30-clip.rm", "rv40-clip.rm"}) {
      try (Harness h = new Harness(sample(name))) {
        h.output.decode = true;
        h.readSamples(300);
        for (NativeDecodeProbe probe : h.output.decoders.values()) assertTrue(name, probe.decoded > 0);
        h.seek(2_000_000);
        h.readSamples(300);
        for (NativeDecodeProbe probe : h.output.decoders.values()) assertTrue(name, probe.decoded > 0);
      }
    }
  }

  @Test(timeout = 30000) public void seeksForwardBackwardAndAfterEofWithoutStaleSamples() throws Exception {
    try (Harness h = new Harness(sample("rv10.rm"))) {
      h.readSamples(40);
      h.seek(5_000_000);
      h.readSamples(20);
      assertTrue(h.output.times.toString(), h.output.times.stream().anyMatch(t -> t >= 4_000_000));
      h.seek(0);
      h.readSamples(20);
      assertTrue(h.output.times.toString(), h.output.times.stream().anyMatch(t -> t < 1_000_000));
      while (h.step() != Extractor.RESULT_END_OF_INPUT) {}
      h.seek(0);
      h.readSamples(20);
      assertTrue(h.output.times.stream().anyMatch(t -> t < 1_000_000));
    }
  }

  @Test(timeout = 30000) public void rapidSeeksKeepOnlyLatestTarget() throws Exception {
    try (Harness h = new Harness(sample("rv40.rm"))) {
      h.readSamples(40);
      h.seek(8_000_000);
      h.seek(3_000_000);
      h.seek(0);
      h.readSamples(30);
      assertTrue(h.output.times.toString(), h.output.times.stream().anyMatch(t -> t < 1_000_000));
    }
  }

  @Test(timeout = 10000) public void inputFailureCanBeRetriedWithoutLosingNativeRead() throws Exception {
    try (Harness h = new Harness(sample("rv10.rm"))) {
      ExtractorInput original = h.input;
      boolean[] failOnce = {true};
      h.input = new DefaultExtractorInput((data, offset, length) -> {
        if (failOnce[0]) { failOnce[0] = false; throw new IOException("Injected network error"); }
        return original.read(data, offset, length);
      }, 0, h.bytes.length);
      try { h.step(); fail("Expected injected error"); }
      catch (IOException expected) { assertEquals("Injected network error", expected.getMessage()); }
      h.readSamples(100);
      assertTrue(h.output.samples >= 100);
    }
  }

  @Test(timeout = 10000) public void unknownLengthStillPlaysButIsNotAdvertisedSeekable() throws Exception {
    try (Harness h = new Harness(sample("rv10.rm"), false)) {
      h.readSamples(100);
      assertTrue(h.output.samples >= 100);
      assertFalse(h.output.seekable);
    }
  }

  @Test(timeout = 10000) public void releaseWhileWorkerNeedsInputDoesNotHang() throws Exception {
    Harness h = new Harness(sample("rv40.rm"));
    h.step();
    h.close();
    assertFalse(Thread.getAllStackTraces().keySet().stream()
        .anyMatch(t -> t.isAlive() && t.getName().equals("Nextlib-RM-demux")));
  }

  @Test(timeout = 10000) public void malformedHeaderReportsIOException() throws Exception {
    try (Harness h = new Harness(new byte[] {'.','R','M','F',0,0,0,0})) {
      try {
        while (h.step() != Extractor.RESULT_END_OF_INPUT) {}
        fail("Malformed input must fail");
      } catch (IOException expected) { assertNotNull(expected.getMessage()); }
    }
  }

  private static byte[] sample(String name) throws IOException {
    return Files.readAllBytes(Path.of(System.getProperty("nextlib.sample.dir"), name));
  }
  private static ExtractorInput input(byte[] bytes, long position) {
    return input(bytes, position, true);
  }
  private static ExtractorInput input(byte[] bytes, long position, boolean knownLength) {
    int[] cursor = {(int)position};
    return new DefaultExtractorInput((target, offset, length) -> {
      if (cursor[0] >= bytes.length) return -1;
      // Small reads exercise AVIO buffering and partial ExtractorInput reads.
      int count = Math.min(997, Math.min(length, bytes.length - cursor[0]));
      System.arraycopy(bytes, cursor[0], target, offset, count);
      cursor[0] += count;
      return count;
    }, position, knownLength ? bytes.length : -1);
  }
  private static final class Harness implements AutoCloseable {
    final byte[] bytes;
    final boolean knownLength;
    final FfmpegExtractor extractor = new FfmpegExtractor();
    final Output output = new Output();
    ExtractorInput input;
    Harness(byte[] bytes) { this(bytes, true); }
    Harness(byte[] bytes, boolean knownLength) {
      this.bytes = bytes; this.knownLength = knownLength;
      input = input(bytes,0,knownLength); extractor.init(output);
    }
    int step() throws IOException {
      PositionHolder holder = new PositionHolder();
      int result = extractor.read(input, holder);
      if (result == Extractor.RESULT_SEEK) {
        assertTrue(holder.position >= 0 && holder.position <= bytes.length);
        input = input(bytes, holder.position, knownLength);
      }
      return result;
    }
    void readSamples(int count) throws IOException {
      int target = output.samples + count;
      int iterations = 0;
      while (output.samples < target && step() != Extractor.RESULT_END_OF_INPUT) {
        assertTrue("No progress", ++iterations < 100000);
      }
    }
    void seek(long timeUs) {
      output.times.clear();
      output.reset.addAll(output.decoders.keySet());
      for (NativeDecodeProbe probe : output.decoders.values()) probe.decoded = 0;
      extractor.seek(0, timeUs); input = input(bytes, 0, knownLength);
    }
    @Override public void close() { extractor.release(); for (NativeDecodeProbe probe : output.decoders.values()) probe.close(); }
  }
  private static final class Output implements ExtractorOutput {
    final Map<Integer,Format> formats = new HashMap<>();
    final List<Long> times = new ArrayList<>();
    int samples;
    boolean decode, seekable;
    final Set<Integer> reset = new HashSet<>();
    final Map<Integer,NativeDecodeProbe> decoders = new HashMap<>();
    @Override public TrackOutput track(int id, int type) {
      return new TrackOutput() {
        byte[] pending;
        @Override public void format(Format format) {
          formats.put(id,format);
          if (decode) try { decoders.put(id, new NativeDecodeProbe(format)); }
          catch (Exception e) { throw new AssertionError(e); }
        }
        @Override public int sampleData(DataReader input, int length, boolean allowEnd, int part) { throw new AssertionError(); }
        @Override public void sampleData(ParsableByteArray data, int length, int part) { pending = new byte[length]; data.readBytes(pending, 0, length); }
        @Override public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData crypto) {
          assertTrue(size > 0); samples++; times.add(timeUs);
          if (decode) try { decoders.get(id).decode(pending, timeUs, reset.remove(id)); }
          catch (Exception e) { throw new AssertionError(e); }
        }
      };
    }
    @Override public void endTracks() {}
    @Override public void seekMap(SeekMap map) { assertTrue(map.getDurationUs() > 0); seekable = map.isSeekable(); }
  }
}
