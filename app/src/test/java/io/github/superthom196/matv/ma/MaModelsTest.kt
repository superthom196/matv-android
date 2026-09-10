package io.github.superthom196.matv.ma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaModelsTest {

    // audioFormatLabel

    @Test
    fun audioFormatLabel_fullFormat() {
        assertEquals("FLAC 24/44.1", audioFormatLabel("flac", 24, "44.1"))
        assertEquals("MP3 16/48", audioFormatLabel("MP3", 16, "48"))
    }

    @Test
    fun audioFormatLabel_dropsUnknownOrBlankCodec() {
        assertEquals("24/44.1", audioFormatLabel("?", 24, "44.1"))
        assertEquals("24/44.1", audioFormatLabel("", 24, "44.1"))
        assertEquals("24/44.1", audioFormatLabel("   ", 24, "44.1"))
        assertEquals("24/44.1", audioFormatLabel(null, 24, "44.1"))
    }

    @Test
    fun audioFormatLabel_onlyRate() {
        assertEquals("44.1 kHz", audioFormatLabel(null, null, "44.1"))
        assertEquals("FLAC 96 kHz", audioFormatLabel("flac", null, "96"))
    }

    @Test
    fun audioFormatLabel_onlyDepth() {
        assertEquals("24-bit", audioFormatLabel(null, 24, null))
        assertEquals("FLAC 24-bit", audioFormatLabel("flac", 24, null))
    }

    @Test
    fun audioFormatLabel_onlyCodec() {
        assertEquals("FLAC", audioFormatLabel("flac", null, null))
    }

    @Test
    fun audioFormatLabel_nothingGivesEmpty() {
        assertEquals("", audioFormatLabel(null, null, null))
        assertEquals("", audioFormatLabel("?", null, null))
    }

    // isHiRes

    @Test
    fun isHiRes_moreThan16Bits() {
        assertTrue(isHiRes(24, 44100))
        assertTrue(isHiRes(32, null))
    }

    @Test
    fun isHiRes_fasterThan48kHz() {
        assertTrue(isHiRes(16, 96000))
        assertTrue(isHiRes(null, 88200))
    }

    @Test
    fun isHiRes_cdAnd48kAreNot() {
        assertFalse(isHiRes(16, 44100))
        assertFalse(isHiRes(16, 48000))
    }

    @Test
    fun isHiRes_nullsTreatedAsCd() {
        assertFalse(isHiRes(null, null))
        assertFalse(isHiRes(null, 48000))
        assertFalse(isHiRes(16, null))
    }

    // AudioFormat.sampleRateKhz

    @Test
    fun sampleRateKhz_fractionalKeepsOneDecimal() {
        assertEquals("44.1", AudioFormat(sampleRate = 44100).sampleRateKhz)
        assertEquals("88.2", AudioFormat(sampleRate = 88200).sampleRateKhz)
    }

    @Test
    fun sampleRateKhz_wholeDropsDecimal() {
        assertEquals("48", AudioFormat(sampleRate = 48000).sampleRateKhz)
        assertEquals("96", AudioFormat(sampleRate = 96000).sampleRateKhz)
        assertEquals("192", AudioFormat(sampleRate = 192000).sampleRateKhz)
    }

    @Test
    fun sampleRateKhz_nullWhenUnknown() {
        assertNull(AudioFormat().sampleRateKhz)
        assertNull(AudioFormat(sampleRate = null).sampleRateKhz)
    }
}
