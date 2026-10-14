package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@UnstableApi
class FfmpegLibraryTest {
    @Test
    fun av1SelectsDav1d() {
        assertEquals("libdav1d", FfmpegLibrary.getCodecName(MimeTypes.VIDEO_AV1))
    }

    @Test
    fun vc1UsesStandardMimeOnly() {
        assertEquals("vc1", FfmpegLibrary.getCodecName(MimeTypes.VIDEO_VC1))
        assertNull(FfmpegLibrary.getCodecName("video/VC1"))
        assertNull(FfmpegLibrary.getCodecName("video/vc1"))
        assertNull(FfmpegLibrary.getCodecName("video/x-ms-wmv"))
    }
}
