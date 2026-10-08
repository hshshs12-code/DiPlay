package com.shilapi.xcertplay.settings

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.media.AudioEffectSettings
import com.shilapi.xcertplay.media.EqPresets
import com.shilapi.xcertplay.media.EqualizerDsp

/**
 * The equalizer UI shared by the main settings page and the in-CarPlay overlay. The host supplies
 * its own themed slider, switch and button widgets; this panel owns the logic and the band view.
 */
class EqualizerPanel(
    private val context: Context,
    private val accent: Int,
    private val textColor: Int,
    private val mutedColor: Int,
    private val sliderFactory: (title: String, steps: List<Int>, current: Int, describe: (Int) -> String, onChange: (Int) -> Unit) -> View,
    private val switchFactory: (label: String, checked: Boolean, description: String, onChanged: (Boolean) -> Unit) -> View,
    private val buttonFactory: (title: String, onClick: () -> Unit) -> View,
) {
    private var settings = AudioEffectSettings.load(context)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun save(next: AudioEffectSettings) { settings = next; AudioEffectSettings.save(context, next) }

    fun build(): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(switchFactory("Enable audio effects", settings.enabled,
            "Equalizer, bass boost and loudness on CarPlay music. Changes below apply live; the master switch applies on the next track.") { enabled ->
            save(settings.copy(enabled = enabled))
            body.visibility = if (enabled) View.VISIBLE else View.GONE
        })
        body.visibility = if (settings.enabled) View.VISIBLE else View.GONE
        root.addView(body)
        buildBody(body)
        return root
    }

    private fun buildBody(body: LinearLayout) {
        body.removeAllViews()
        val deviceInfo = AudioEffectSettings.bandInfo()
        val dsp = settings.dspMode
        body.addView(switchFactory("12-band software equalizer", dsp,
            if (dsp) "On: 12 bands from 31 Hz to 16 kHz, ±12 dB, processed by DiPlay (a few percent of one core). Applies on the next track."
            else "Off: the head unit's own ${deviceInfo.centerHz.size}-band equalizer effect. Switch on for 12 bands.") { enabled ->
            save(settings.copy(dspMode = enabled))
            buildBody(body)
        })
        // Preset picker
        val presetLabel = TextView(context).apply {
            text = "Preset: ${settings.presetName.ifEmpty { "Custom" }}"; textSize = 16f; setTextColor(textColor)
            setPadding(0, dp(12), 0, dp(4))
        }
        body.addView(presetLabel)
        val bands = EqualizerBandsView(context).apply {
            accent = this@EqualizerPanel.accent; textColor = this@EqualizerPanel.textColor; mutedColor = this@EqualizerPanel.mutedColor
        }
        fun refreshBands() {
            if (settings.dspMode) {
                bands.minDb = EqualizerDsp.MIN_DB.toInt(); bands.maxDb = EqualizerDsp.MAX_DB.toInt()
                bands.centersHz = EqualizerDsp.CENTER_HZ.toList()
                bands.levelsDb = IntArray(EqualizerDsp.BANDS) { settings.dspGainsDb.getOrElse(it) { 0 } }
            } else {
                bands.minDb = deviceInfo.minLevelMb / 100; bands.maxDb = deviceInfo.maxLevelMb / 100
                bands.centersHz = deviceInfo.centerHz
                bands.levelsDb = IntArray(deviceInfo.centerHz.size) { (settings.bandLevelsMb.getOrElse(it) { 0 } / 100).coerceIn(bands.minDb, bands.maxDb) }
            }
        }
        refreshBands()
        body.addView(buttonFactory("Choose preset") {
            val names = EqPresets.ALL.map { it.name }
            AlertDialog.Builder(context).setTitle("Equalizer preset")
                .setItems(names.toTypedArray()) { _, index ->
                    val preset = EqPresets.ALL[index]
                    if (settings.dspMode) {
                        save(settings.copy(dspGainsDb = preset.db.toList(), presetName = preset.name))
                    } else {
                        val mapped = EqPresets.forBands(preset, deviceInfo.centerHz)
                        val levels = mapped.map { (it * 100).coerceIn(deviceInfo.minLevelMb, deviceInfo.maxLevelMb) }
                        save(settings.copy(bandLevelsMb = levels, presetName = preset.name))
                    }
                    presetLabel.text = "Preset: ${preset.name}"
                    refreshBands()
                }.show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(4) })
        bands.onLevelChanged = { band, db ->
            if (settings.dspMode) {
                val gains = MutableList(EqualizerDsp.BANDS) { settings.dspGainsDb.getOrElse(it) { 0 } }
                gains[band] = db
                save(settings.copy(dspGainsDb = gains, presetName = ""))
            } else {
                val levels = MutableList(deviceInfo.centerHz.size) { settings.bandLevelsMb.getOrElse(it) { 0 } }
                levels[band] = (db * 100).coerceIn(deviceInfo.minLevelMb, deviceInfo.maxLevelMb)
                save(settings.copy(bandLevelsMb = levels, presetName = ""))
            }
            presetLabel.text = "Preset: Custom"
        }
        body.addView(bands, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)).apply { topMargin = dp(8) })
        body.addView(TextView(context).apply {
            text = "Drag up to boost, down to cut. Values in dB. Large boosts on loud tracks can clip; the soft limiter (12-band mode) rounds them off."
            textSize = 13f; setTextColor(mutedColor); setPadding(0, dp(4), 0, dp(8))
        })
        if (settings.dspMode) {
            body.addView(switchFactory("Soft limiter", settings.limiter,
                "Rounds off peaks that boosted bands push past full scale. Off keeps the signal untouched (hard clipping on very loud boosts).") { enabled ->
                save(settings.copy(limiter = enabled))
            })
        }
        body.addView(sliderFactory("Bass boost (device)", (0..100 step 5).toList(), (settings.bassStrength / 10 / 5) * 5, { "$it %" }) {
            save(settings.copy(bassStrength = it * 10))
        })
        body.addView(sliderFactory("Loudness boost (device)", (0..20).toList(), settings.loudnessGainMb / 100, { if (it == 0) "Off" else "+$it dB" }) {
            save(settings.copy(loudnessGainMb = it * 100))
        })
        body.addView(buttonFactory("Reset equalizer") {
            save(settings.copy(dspGainsDb = List(EqualizerDsp.BANDS) { 0 }, bandLevelsMb = List(deviceInfo.centerHz.size) { 0 },
                bassStrength = 0, loudnessGainMb = 0, presetName = "Flat"))
            buildBody(body)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })
    }

    private fun LinearLayout.addView(view: View, params: LinearLayout.LayoutParams) = (this as ViewGroup).addView(view, params)
}
