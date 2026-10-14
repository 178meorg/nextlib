package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import androidx.media3.common.util.UnstableApi;
import java.util.List;

/** Converts Media3 VC-1 start-code data to FFmpeg extradata without dropping header bytes. */
@UnstableApi
final class Vc1InitializationData {
    private Vc1InitializationData() {}

    static byte[] build(List<byte[]> initializationData) throws FfmpegDecoderException {
        long length = 0;
        for (byte[] data : initializationData) length += data.length;
        // FFmpeg's VC-1 decoder requires at least 16 bytes and both header types.
        if (length < 16 || length > Integer.MAX_VALUE) {
            throw new FfmpegDecoderException("Missing or invalid VC-1 initialization data");
        }
        byte[] extraData = new byte[(int) length];
        int offset = 0;
        for (byte[] data : initializationData) {
            System.arraycopy(data, 0, extraData, offset, data.length);
            offset += data.length;
        }
        // Media3's Matroska extractor starts at the sequence header. Preserve entry points,
        // escape bytes and any remaining data; FFmpeg parses and validates the header payloads.
        boolean sequenceHeader = false;
        boolean entryPoint = false;
        for (int i = 0; i + 4 < extraData.length; i++) {
            if (extraData[i] == 0 && extraData[i + 1] == 0 && extraData[i + 2] == 1) {
                int payloadEnd = i + 4;
                while (payloadEnd + 3 < extraData.length
                        && !(extraData[payloadEnd] == 0 && extraData[payloadEnd + 1] == 0
                        && extraData[payloadEnd + 2] == 1)) payloadEnd++;
                if (payloadEnd == i + 4 && payloadEnd + 3 < extraData.length) continue;
                if (extraData[i + 3] == 0x0f) sequenceHeader = true;
                if (extraData[i + 3] == 0x0e && sequenceHeader) entryPoint = true;
            }
        }
        if (!sequenceHeader || !entryPoint) {
            throw new FfmpegDecoderException("VC-1 initialization data requires sequence and entry-point headers");
        }
        return extraData;
    }
}
