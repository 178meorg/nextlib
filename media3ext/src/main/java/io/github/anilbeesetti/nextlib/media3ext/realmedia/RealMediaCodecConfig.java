package io.github.anilbeesetti.nextlib.media3ext.realmedia;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;

/** Only the primitive codec parameters proven necessary by the RealMedia differential probes. */
@UnstableApi
public final class RealMediaCodecConfig {
    public static final String VIDEO_RV30 = "video/x-rv30";
    public static final String VIDEO_RV40 = "video/x-rv40";
    public static final String AUDIO_COOK = "audio/x-cook";

    private RealMediaCodecConfig() {}

    public static Format format(String id, String codec, byte[] extraData, int width, int height,
                                int sampleRate, int channels, int blockAlign) {
        String mime;
        switch (codec) {
            case "rv30": mime = VIDEO_RV30; break;
            case "rv40": mime = VIDEO_RV40; break;
            case "cook": mime = AUDIO_COOK; break;
            case "aac": mime = MimeTypes.AUDIO_AAC; break;
            default: throw new IllegalArgumentException("Unsupported RealMedia codec: " + codec);
        }
        Format.Builder builder = new Format.Builder().setId(id).setSampleMimeType(mime);
        byte[] extra = extraData.clone();
        if (codec.equals("rv30") || codec.equals("rv40")) {
            if (width <= 0 || height <= 0 || extra.length < 2)
                throw new IllegalArgumentException("Missing RealVideo dimensions or extradata");
            builder.setWidth(width).setHeight(height).setInitializationData(Collections.singletonList(extra));
        } else {
            if (sampleRate <= 0 || channels <= 0 || extra.length == 0)
                throw new IllegalArgumentException("Missing RealAudio initialization parameters");
            builder.setSampleRate(sampleRate).setChannelCount(channels);
            if (codec.equals("cook")) {
                if (blockAlign <= 0 || extra.length < 8)
                    throw new IllegalArgumentException("Cook requires block_align and extradata");
                builder.setInitializationData(Arrays.asList(extra, ByteBuffer.allocate(4).putInt(blockAlign).array()));
            } else builder.setInitializationData(Collections.singletonList(extra));
        }
        return builder.build();
    }

    public static int cookBlockAlign(Format format) {
        if (!AUDIO_COOK.equals(format.sampleMimeType)) return 0;
        if (format.initializationData.size() != 2 || format.initializationData.get(1).length != 4)
            throw new IllegalArgumentException("Missing Cook block_align");
        int value = ByteBuffer.wrap(format.initializationData.get(1)).getInt();
        if (value <= 0) throw new IllegalArgumentException("Invalid Cook block_align");
        return value;
    }
}
