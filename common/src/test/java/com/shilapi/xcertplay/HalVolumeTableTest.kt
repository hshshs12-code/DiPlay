package com.shilapi.xcertplay

import android.media.AudioManager
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HalVolumeTableTest {
    private fun manager(answers: Map<String, String>): AudioManager {
        val manager = mock(AudioManager::class.java)
        for ((key, value) in answers) `when`(manager.getParameters(key)).thenReturn(value)
        return manager
    }

    private fun syntheticTable(): ByteArray {
        val bytes = ByteArray(HalVolumeTable.EXPECTED_SIZE)
        for (type in 0 until 9) for (mode in 0 until 4) for (level in 0 until HalVolumeTable.LEVELS)
            bytes[type * 60 + mode * 15 + level] = (type * 20 + mode * 5 + level).toByte()
        bytes[HalVolumeTable.EXPECTED_SIZE - 1] = 0xff.toByte()
        return bytes
    }

    @Test fun decodesTheVersion1LayoutFromABase64Answer() {
        val table = syntheticTable()
        val manager = manager(mapOf(
            "GET_AUDIO_VOLUME_VERSION" to "GET_AUDIO_VOLUME_VERSION=1",
            "GET_CUST_XML_ENABLE" to "GET_CUST_XML_ENABLE=0",
            "GetCmd=5" to "GetCmd=${HalVolumeTable.EXPECTED_SIZE}",
            "GetBuffer=256,${HalVolumeTable.EXPECTED_SIZE}" to "GetBuffer=" + Base64.encodeToString(table, Base64.NO_WRAP),
        ))
        val result = HalVolumeTable.read(manager)
        assertTrue(result.decoded)
        assertEquals(HalVolumeTable.EXPECTED_SIZE, result.size)
        val media = result.levels!![HalVolumeTable.TYPE_MEDIA]
        assertEquals(7 * 20 + 2 * 5 + 0, media[2][0])        // Media, LoudSpeaker, level 0
        assertEquals(7 * 20 + 2 * 5 + 14, media[2][14])      // Media, LoudSpeaker, level 14
        assertEquals(0, result.levels!![0][0][0])
        val text = result.describe()
        assertTrue(text.contains("Media (music)"))
        assertTrue(text.contains("LoudSpeaker"))
        assertTrue(text.contains("255"))                       // the trailing byte survives
    }

    @Test fun fallsBackToTheExpectedSizeAndReportsAnUndecodableAnswer() {
        val manager = manager(mapOf("GetCmd=5" to "", "GetBuffer=256,${HalVolumeTable.EXPECTED_SIZE}" to "GetBuffer="))
        val result = HalVolumeTable.read(manager)
        assertFalse(result.decoded)
        assertNull(result.bytes)
        assertEquals(HalVolumeTable.EXPECTED_SIZE, result.size)
        assertTrue(result.describe().contains("does not expose"))
    }

    @Test fun acceptsAHexEncodedAnswerToo() {
        val table = syntheticTable()
        val hex = table.joinToString("") { String.format("%02x", it.toInt() and 0xff) }
        val manager = manager(mapOf("GetBuffer=256,${HalVolumeTable.EXPECTED_SIZE}" to "GetBuffer=$hex"))
        val result = HalVolumeTable.read(manager)
        assertTrue(result.decoded)
        assertEquals(7 * 20 + 2 * 5 + 9, result.levels!![HalVolumeTable.TYPE_MEDIA][2][9])
    }
}
