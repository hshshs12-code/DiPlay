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
import com.shilapi.xcertplay.media.ParametricBand
import com.shilapi.xcertplay.media.SoundPresets

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
        if (settings.dspMode) {
            body.addView(buttonFactory("Car sound presets (2018 Yaris)") {
                val labels = SoundPresets.YARIS.map { "${it.name}\n${it.description}" }
                AlertDialog.Builder(context).setTitle("Sound preset for the car")
                    .setItems(labels.toTypedArray()) { _, index ->
                        save(SoundPresets.YARIS[index].apply(settings))
                        buildBody(body)
                    }.show()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
        }
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
        if (settings.dspMode) buildAdvanced(body)
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

    /** Subsonic filter, bass enhancer, auto level and the parametric bands (12-band mode only). */
    private fun buildAdvanced(body: LinearLayout) {
        body.addView(TextView(context).apply {
            text = "Sound processing"; textSize = 17f; setTextColor(textColor); setPadding(0, dp(14), 0, dp(2))
        })
        body.addView(TextView(context).apply {
            text = "Runs in DiPlay before the car's amplifier. Each stage is free when off. Presets above set all of this at once."
            textSize = 13f; setTextColor(mutedColor); setPadding(0, 0, 0, dp(6))
        })
        val subsonicSteps = listOf(0, 20, 25, 30, 35, 40, 50, 60, 80, 100, 120)
        body.addView(sliderFactory("Subsonic filter", subsonicSteps, subsonicSteps.minByOrNull { kotlin.math.abs(it - settings.subsonicHz) } ?: 0,
            { if (it == 0) "Off" else "$it Hz (24 dB/oct)" }) { save(settings.copy(subsonicHz = it, presetName = "")) })
        body.addView(sliderFactory("Bass enhancer", (0..100 step 10).toList(), settings.bassEnhance / 10 * 10,
            { if (it == 0) "Off" else "$it % · harmonics the door speakers can play" }) { save(settings.copy(bassEnhance = it, presetName = "")) })
        val crossovers = listOf(60, 70, 80, 90, 100, 110, 120, 140, 160, 200)
        body.addView(sliderFactory("Bass enhancer range", crossovers, crossovers.minByOrNull { kotlin.math.abs(it - settings.bassEnhanceHz) } ?: 100,
            { "below $it Hz" }) { save(settings.copy(bassEnhanceHz = it, presetName = "")) })
        body.addView(switchFactory("Auto level", settings.autoLevel,
            "Slow leveler: quiet podcasts and loud tracks end up at a similar volume. Turns the soft limiter on while active.") {
            save(settings.copy(autoLevel = it, presetName = ""))
        })
        body.addView(sliderFactory("Auto level strength", (0..100 step 10).toList(), settings.autoLevelAmount / 10 * 10,
            { if (it == 0) "Off" else "$it %" }) { save(settings.copy(autoLevelAmount = it, presetName = "")) })

        body.addView(TextView(context).apply {
            text = "Parametric EQ"; textSize = 17f; setTextColor(textColor); setPadding(0, dp(14), 0, dp(2))
        })
        body.addView(TextView(context).apply {
            text = "Up to five bands with their own frequency, gain and width. Peak = bell around the frequency; shelves lift or cut everything below/above it."
            textSize = 13f; setTextColor(mutedColor); setPadding(0, 0, 0, dp(4))
        })
        val frequencies = listOf(30, 40, 50, 60, 70, 80, 90, 100, 120, 140, 160, 180, 200, 250, 300, 350, 400, 500, 600, 700, 800, 1000, 1200, 1500, 1800, 2000, 2500, 3000, 3500, 4000, 5000, 6000, 7000, 8000, 10000, 12000, 14000, 16000)
        val qTenths = listOf(3, 5, 7, 10, 14, 20, 30, 40, 60)
        val gains = (-12..12).toList()
        val current = List(EqualizerDsp.MAX_PARAMETRIC) { settings.parametric.getOrElse(it) { ParametricBand() } }
        val shown = minOf(EqualizerDsp.MAX_PARAMETRIC, current.indexOfLast { it.type != ParametricBand.OFF } + 2)
        fun update(index: Int, change: (ParametricBand) -> ParametricBand) {
            val next = MutableList(EqualizerDsp.MAX_PARAMETRIC) { settings.parametric.getOrElse(it) { ParametricBand() } }
            next[index] = change(next[index])
            save(settings.copy(parametric = next, presetName = ""))
        }
        for (index in 0 until shown) {
            val band = current[index]
            val holder = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            holder.addView(buttonFactory("Band ${index + 1}: ${band.describe()}  (tap to change type)") {
                update(index) { it.copy(type = (it.type + 1) % 4) }
                buildBody(body)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })
            if (band.type != ParametricBand.OFF) {
                holder.addView(sliderFactory("Frequency", frequencies, frequencies.minByOrNull { kotlin.math.abs(it - band.hz) } ?: 1000,
                    { if (it >= 1000) "${it / 1000.0} kHz" else "$it Hz" }) { hz -> update(index) { it.copy(hz = hz) } })
                holder.addView(sliderFactory("Gain", gains, Math.round(band.gainDb).coerceIn(-12, 12),
                    { (if (it > 0) "+" else "") + "$it dB" }) { db -> update(index) { it.copy(gainTenths = db * 10) } })
                if (band.type == ParametricBand.PEAK) {
                    holder.addView(sliderFactory("Width (Q)", qTenths, qTenths.minByOrNull { kotlin.math.abs(it - band.qTenths) } ?: 10,
                        { "Q ${it / 10.0}" + when { it <= 5 -> " · wide"; it >= 30 -> " · narrow"; else -> "" } }) { q -> update(index) { it.copy(qTenths = q) } })
                }
            }
            body.addView(holder)
        }
    }

    private fun LinearLayout.addView(view: View, params: LinearLayout.LayoutParams) = (this as ViewGroup).addView(view, params)
}
