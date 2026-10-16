package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import io.github.anilbeesetti.nextlib.media3ext.realmedia.RealMediaCodecConfig;

/**
 * Ffmpeg Video decoder.
 */
@UnstableApi
final class FfmpegVideoDecoder extends
        FfmpegDrainingDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer> {

    // LINT.IfChange
    private static final int VIDEO_DECODER_SUCCESS = 0;
    private static final int VIDEO_DECODER_ERROR_INVALID_DATA = -1;
    private static final int VIDEO_DECODER_ERROR_OTHER = -2;
    private static final int VIDEO_DECODER_END_OF_STREAM = 2;
    // LINT.ThenChange(../../../../../../../jni/ffmpeg_jni.cc)

    private final String codecName;
    private long nativeContext;
    // Initialized by createOutputBuffer() during the SimpleDecoder constructor.
    // Keep every buffer, including outputs held by a renderer during decoder reinitialization.
    private List<VideoDecoderOutputBuffer> outputBuffers;
    @Nullable
    private final byte[] extraData;
    private final int rotationDegrees;
    @Nullable private Format outputFormat;

    @C.VideoOutputMode
    private volatile int outputMode;

    /**
     * Creates a Ffmpeg video Decoder.
     *
     * @param numInputBuffers        Number of input buffers.
     * @param numOutputBuffers       Number of output buffers.
     * @param initialInputBufferSize The initial size of each input buffer, in bytes.
     * @param threads                Number of threads libgav1 will use to decode.
     * @throws FfmpegDecoderException Thrown if an exception occurs when initializing the
     *                                decoder.
     */
    public FfmpegVideoDecoder(int numInputBuffers, int numOutputBuffers, int initialInputBufferSize, int threads, Format format) throws FfmpegDecoderException {
        super(new DecoderInputBuffer[numInputBuffers], new VideoDecoderOutputBuffer[numOutputBuffers]);

        try {
            if (!FfmpegLibrary.isAvailable()) {
                throw new FfmpegDecoderException("Failed to load decoder native library.");
            }
            assert format.sampleMimeType != null;
            codecName = Assertions.checkNotNull(FfmpegLibrary.getCodecName(format.sampleMimeType));
            extraData = getExtraData(format.sampleMimeType, format.initializationData);
            // Format uses clockwise degrees. Ignore unsupported non-right-angle rotations.
            int rotation = format.rotationDegrees % 360;
            rotationDegrees = rotation % 90 == 0 ? (rotation + 360) % 360 : 0;
            nativeContext = ffmpegInitialize(codecName, extraData, threads, format.width, format.height);
            if (nativeContext == 0) {
                throw new FfmpegDecoderException("Failed to initialize decoder.");
            }
            setInitialInputBufferSize(initialInputBufferSize);
        } catch (FfmpegDecoderException | RuntimeException | Error failure) {
            // SimpleDecoder starts its worker in super(). A failed constructor leaves no
            // instance for the renderer to release, so stop the worker before rethrowing.
            try {
                try {
                    super.release();
                } finally {
                    if (nativeContext != 0) {
                        ffmpegRelease(nativeContext);
                        nativeContext = 0;
                    }
                }
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Returns FFmpeg-compatible codec-specific initialization data ("extra data"), or {@code null} if
     * not required.
     */
    @Nullable
    static byte[] getExtraData(String mimeType, List<byte[]> initializationData)
            throws FfmpegDecoderException {
        if (MimeTypes.VIDEO_VC1.equals(mimeType)) {
            return Vc1InitializationData.build(initializationData);
        }
        if (initializationData.isEmpty()) return null;
        switch (mimeType) {
            case MimeTypes.VIDEO_H264 -> {
                byte[] sps = initializationData.get(0);
                byte[] pps = initializationData.get(1);
                byte[] extraData = new byte[sps.length + pps.length];
                System.arraycopy(sps, 0, extraData, 0, sps.length);
                System.arraycopy(pps, 0, extraData, sps.length, pps.length);
                return extraData;
            }
            case MimeTypes.VIDEO_H265, RealMediaCodecConfig.VIDEO_RV30, RealMediaCodecConfig.VIDEO_RV40 -> {
                return initializationData.get(0);
            }
            default -> {
                // Other codecs do not require extra data.
                return null;
            }
        }
    }

    @Override
    public String getName() {
        return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName;
    }

    /**
     * Sets the output mode for frames rendered by the decoder.
     *
     * @param outputMode The output mode.
     */
    public void setOutputMode(@C.VideoOutputMode int outputMode) {
        this.outputMode = outputMode;
    }

    @Override
    protected DecoderInputBuffer createInputBuffer() {
        return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT, FfmpegLibrary.getInputBufferPaddingSize());
    }

    @Override
    protected VideoDecoderOutputBuffer createOutputBuffer() {
        if (outputBuffers == null) outputBuffers = new ArrayList<>();
        VideoDecoderOutputBuffer outputBuffer = new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
        outputBuffers.add(outputBuffer);
        return outputBuffer;
    }

    @Override
    protected void releaseOutputBuffer(VideoDecoderOutputBuffer outputBuffer) {
        synchronized (outputBuffers) {
            releaseNativeFrame(outputBuffer);
        }
        super.releaseOutputBuffer(outputBuffer);
    }

    private void releaseNativeFrame(VideoDecoderOutputBuffer outputBuffer) {
        if (outputBuffer.decoderPrivate != 0) {
            ffmpegReleaseFrame(outputBuffer.decoderPrivate);
            outputBuffer.decoderPrivate = 0;
        }
    }

    @Override
    protected FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
        return new FfmpegDecoderException("Unexpected decode error", error);
    }

    @Override
    protected boolean sendInputBuffer(DecoderInputBuffer inputBuffer, boolean reset) throws FfmpegDecoderException {
        if (reset) {
            nativeContext = ffmpegReset(nativeContext);
            if (nativeContext == 0) {
                throw new FfmpegDecoderException("Error resetting (see logcat).");
            }
        }

        if (inputBuffer.format != null) outputFormat = inputBuffer.format;
        int sendPacketResult;
        if (inputBuffer.isEndOfStream()) {
            sendPacketResult = ffmpegSendEndOfStream(nativeContext);
        } else {
            ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
            sendPacketResult = ffmpegSendPacket(nativeContext, inputData, inputData.limit(), inputBuffer.timeUs);
        }
        if (sendPacketResult == VIDEO_DECODER_ERROR_INVALID_DATA) {
            return false;
        } else if (sendPacketResult == VIDEO_DECODER_ERROR_OTHER) {
            throw new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
        }
        return true;
    }

    @Override
    protected int receiveOutputBuffer(VideoDecoderOutputBuffer outputBuffer) throws FfmpegDecoderException {
        int getFrameResult = ffmpegReceiveFrame(
                nativeContext, outputMode, outputBuffer, false, rotationDegrees);
        if (getFrameResult == VIDEO_DECODER_ERROR_OTHER) {
            throw new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
        }
        if (getFrameResult == VIDEO_DECODER_ERROR_INVALID_DATA) return OUTPUT_UNAVAILABLE;
        if (getFrameResult == VIDEO_DECODER_END_OF_STREAM) return OUTPUT_END_OF_STREAM;
        outputBuffer.format = outputFormat;
        return OUTPUT_FRAME;
    }

    @Override
    public void release() {
        super.release();
        // The decode thread has stopped. Reclaim queued and renderer-held frames together;
        // clearing decoderPrivate also makes a later outputBuffer.release() harmless.
        synchronized (outputBuffers) {
            for (VideoDecoderOutputBuffer outputBuffer : outputBuffers) {
                releaseNativeFrame(outputBuffer);
            }
            ffmpegRelease(nativeContext);
            nativeContext = 0;
        }
    }

    /**
     * Renders output buffer to the given surface. Must only be called when in {@link
     * C#VIDEO_OUTPUT_MODE_SURFACE_YUV} mode.
     *
     * @param outputBuffer Output buffer.
     * @param surface      Output surface.
     * @throws FfmpegDecoderException Thrown if called with invalid output mode or frame
     *                                rendering fails.
     */
    public void renderToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
            throws FfmpegDecoderException {
        if (outputBuffer.mode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
            throw new FfmpegDecoderException("Invalid output mode.");
        }
        if (ffmpegRenderFrame(
                nativeContext, surface,
                outputBuffer, outputBuffer.width, outputBuffer.height,
                rotationDegrees) == VIDEO_DECODER_ERROR_OTHER) {
            throw new FfmpegDecoderException("Buffer render error: ");
        }
    }

    private native long ffmpegInitialize(String codecName, @Nullable byte[] extraData, int threads, int width, int height);

    private native int ffmpegSendEndOfStream(long context);

    private native long ffmpegReset(long context);

    private native void ffmpegRelease(long context);

    private native void ffmpegReleaseFrame(long frame);

    private native int ffmpegRenderFrame(
            long context, Surface surface, VideoDecoderOutputBuffer outputBuffer,
            int displayedWidth,
            int displayedHeight, int rotationDegrees);

    /**
     * Decodes the encoded data passed.
     *
     * @param context     Decoder context.
     * @param encodedData Encoded data.
     * @param length      Length of the data buffer.
     * @return {@link #VIDEO_DECODER_SUCCESS} if successful, {@link #VIDEO_DECODER_ERROR_OTHER} if an
     * error occurred.
     */
    private native int ffmpegSendPacket(long context, ByteBuffer encodedData, int length,
                                        long inputTime);

    /**
     * Gets the decoded frame.
     *
     * @param context      Decoder context.
     * @param outputBuffer Output buffer for the decoded frame.
     * @return {@link #VIDEO_DECODER_SUCCESS} if successful, {@link #VIDEO_DECODER_ERROR_INVALID_DATA}
     * if successful but the frame is decode-only, {@link #VIDEO_DECODER_ERROR_OTHER} if an error
     * occurred.
     */
    private native int ffmpegReceiveFrame(
            long context, int outputMode, VideoDecoderOutputBuffer outputBuffer, boolean decodeOnly,
            int rotationDegrees);

}
