package com.shilapi.xcertplay.media

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEffectSettingsJsonTest {
    private val full = AudioEffectSettings(
        enabled = true, dspMode = true, presetName = "Mine", dspGainsDb = List(EqualizerDsp.BANDS) { it - 6 },
        limiter = true, limiterOff = false, subsonicHz = 40, bassEnhance = 55, bassEnhanceHz = 120, bassClipDb = 4, driveDb = 3,
        outputTrimDb = 1, autoLevel = true, autoLevelAmount = 70,
        parametric = listOf(ParametricBand.peak(180, -3f, 2f), ParametricBand.highShelf(8000, 2f)),
        stereoWidth = 120, balance = -10, driverSeatDelayTenthsMs = 12, driverSide = AudioEffectSettings.DRIVER_RIGHT, crossfeed = 20,
        airDb = 3, airHz = 8000, deEss = 40, loudnessComp = true, loudnessCompAmount = 50, loudnessCompReferenceStep = 18,
    )

    @Test fun roundTripsEveryField() {
        val json = AudioEffectSettingsJson.toJson(full)
        val back = AudioEffectSettingsJson.merge(AudioEffectSettings(), json)
        assertEquals(full, back)
    }

    @Test fun mergeIsPartialAndClamped() {
        val json = JSONObject().put("driveDb", 99).put("balance", -500).put("presetName", "")
        val merged = AudioEffectSettingsJson.merge(full, json)
        assertEquals(AudioEffectSettings.MAX_DRIVE_DB, merged.driveDb)
        assertEquals(-AudioEffectSettings.MAX_BALANCE, merged.balance)
        assertEquals("", merged.presetName)
        assertEquals(full.dspGainsDb, merged.dspGainsDb)        // untouched keys survive
        assertEquals(full.parametric, merged.parametric)
        assertTrue(merged.autoLevel)
    }

    @Test fun shortGainArraysKeepTheRestAndAirCornerSnaps() {
        val json = JSONObject().put("dspGainsDb", org.json.JSONArray(listOf(5, 4))).put("airHz", 9100)
        val merged = AudioEffectSettingsJson.merge(full, json)
        assertEquals(5, merged.dspGainsDb[0]); assertEquals(4, merged.dspGainsDb[1]); assertEquals(full.dspGainsDb[2], merged.dspGainsDb[2])
        assertEquals(EqualizerDsp.BANDS, merged.dspGainsDb.size)
        assertEquals(10000, merged.airHz)
    }

    @Test fun userPresetsEncodeAndParse() {
        val entries = listOf(UserPresets.Entry("Road trip", full), UserPresets.Entry("Quiet", full.copy(driveDb = 0, limiterOff = true)))
        val parsed = UserPresets.parse(UserPresets.encode(entries))
        assertEquals(2, parsed.size)
        assertEquals("Road trip", parsed[0].name)
        assertEquals(full.copy(presetName = "Road trip"), parsed[0].settings)
        assertTrue(parsed[1].settings.limiterOff)
        assertFalse(parsed[1].settings.driveDb > 0)
        assertEquals(null, UserPresets.cleanName("   "))
        assertEquals("A  B".replace("  ", " "), UserPresets.cleanName(" A   B "))
    }
}
