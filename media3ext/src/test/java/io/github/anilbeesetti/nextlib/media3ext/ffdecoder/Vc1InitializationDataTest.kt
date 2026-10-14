package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

@UnstableApi
class Vc1InitializationDataTest {
    // Structural fixtures only: FFmpeg must still validate the actual header bitstream.
    private val sequence = byteArrayOf(0, 0, 1, 0x0f, 0xc2.toByte(), 0, 0, 3, 1, 0x20)
    private val entry = byteArrayOf(0, 0, 1, 0x0e, 0, 0x40, 0x10)

    @Test
    fun matroskaHeadersAndEscapeBytesArePreserved() {
        val data = sequence + entry
        assertArrayEquals(data, FfmpegVideoDecoder.getExtraData(MimeTypes.VIDEO_VC1, listOf(data)))
    }

    @Test
    fun splitHeadersAreJoinedInOrder() {
        assertArrayEquals(sequence + entry, Vc1InitializationData.build(listOf(sequence, entry)))
    }

    @Test
    fun startCodeSplitAcrossBuffersIsPreserved() {
        val data = sequence + entry
        assertArrayEquals(data, Vc1InitializationData.build(listOf(data.copyOfRange(0, 2), data.copyOfRange(2, data.size))))
    }

    @Test
    fun optionalWvc1PrefixIsPreserved() {
        val data = byteArrayOf(0) + sequence + entry
        assertArrayEquals(data, Vc1InitializationData.build(listOf(data)))
    }

    @Test
    fun missingOrIncompleteHeadersAreRejected() {
        for (data in listOf(emptyList(), listOf(byteArrayOf()), listOf(sequence),
            listOf(entry), listOf(ByteArray(20)), listOf(sequence + byteArrayOf(0, 0, 1, 0x0e)),
            listOf(entry + sequence), listOf(byteArrayOf(0, 0, 1, 0x0f) + entry + ByteArray(8)))) {
            assertThrows(FfmpegDecoderException::class.java) { Vc1InitializationData.build(data) }
        }
    }
}
