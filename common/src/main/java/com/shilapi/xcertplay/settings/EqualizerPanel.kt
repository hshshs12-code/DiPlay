package com.shilapi.xcertplay.settings

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.LoudnessVolumeTracker
import com.shilapi.xcertplay.media.AirShelf
import com.shilapi.xcertplay.media.AudioEffectSettings
import com.shilapi.xcertplay.media.EqPresets
import com.shilapi.xcertplay.media.EqualizerDsp
import com.shilapi.xcertplay.media.ParametricBand
import com.shilapi.xcertplay.media.SoundPresets

/**
 * The sound-processing UI shared by the main settings page and the in-CarPlay overlay, laid out
 * for a cheap resistive car touchscreen. It opens on an overview a driver can use at a glance:
 * four favourite presets, the two everyday controls (bass character, auto level), one honest
 * status line, and two doors: "All presets" and "Advanced" (bands, bass, dynamics, tone, stereo,
 * parametric). Every target is at least 64 dp; the steppers replace drag sliders. The host still
 * supplies its themed switch and button widgets; the slider factory is kept for compatibility.
 */
class EqualizerPanel(
    private val context: Context,
    private val accent: Int,
    private val textColor: Int,
    private val mutedColor: Int,
    @Suppress("unused") private val sliderFactory: (title: String, steps: List<Int>, current: Int, describe: (Int) -> String, onChange: (Int) -> Unit) -> View,
    private val switchFactory: (label: String, checked: Boolean, description: String, onChanged: (Boolean) -> Unit) -> View,
    private val buttonFactory: (title: String, onClick: () -> Unit) -> View,
) {
    private enum class SoundPage { OVERVIEW, ALL_PRESETS, ADVANCED }

    private var settings = AudioEffectSettings.load(context)
    private val density = context.resources.displayMetrics.density
    private val wide = context.resources.configuration.screenWidthDp >= 700
    private fun dp(v: Int) = (v * density).toInt()
    private lateinit var body: LinearLayout
    private var presetSummary: TextView? = null
    private var refreshOverview: (() -> Unit)? = null

    /** Saves, applies live, and repaints the indicators that depend on the current preset. */
    private fun save(next: AudioEffectSettings) {
        settings = next
        AudioEffectSettings.save(context, next)
        presetSummary?.text = "Current: ${next.presetName.ifEmpty { "Custom" }}"
        refreshOverview?.invoke()
    }

    fun build(): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(switchFactory("Sound processing", settings.enabled,
            "Equalizer, bass, dynamics and stereo processing on music (CarPlay and iPod mode). Changes apply live.") { enabled ->
            save(settings.copy(enabled = enabled))
            body.visibility = if (enabled) View.VISIBLE else View.GONE
        })
        body.visibility = if (settings.enabled) View.VISIBLE else View.GONE
        root.addView(body)
        buildBody()
        return root
    }

    private fun rebuild() = buildBody()

    private fun buildBody() {
        refreshOverview = null; presetSummary = null
        body.removeAllViews()
        when (soundPage) {
            SoundPage.OVERVIEW -> buildOverview()
            SoundPage.ALL_PRESETS -> { addBack(); buildPresets(AudioEffectSettings.bandInfo()) }
            SoundPage.ADVANCED -> { addBack(); buildAdvanced() }
        }
    }

    private fun addBack() {
        body.addView(buttonFactory("‹  Back to Sound") { soundPage = SoundPage.OVERVIEW; rebuild() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(8); bottomMargin = dp(4) })
    }

    // ---- overview ------------------------------------------------------------------------------

    private fun buildOverview() {
        if (!settings.dspMode) {
            body.addView(heading("Device equalizer", "DiPlay's own processing is off; only the head unit's effect runs."))
            body.addView(buttonFactory("Use DiPlay's sound processing") { save(settings.copy(dspMode = true)); rebuild() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(8) })
            body.addView(buttonFactory("Device equalizer settings") { soundPage = SoundPage.ADVANCED; rebuild() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(12) })
            return
        }
        body.addView(heading("Preset", null))
        val favourites = listOf("Yaris · Trap Clean" to "Trap Clean", "Yaris · Rage" to "Rage", "Yaris · 808 Heavy" to "808 Heavy", "Yaris · Podcast / Voice" to "Podcast")
        val tiles = ArrayList<Pair<String, TextView>>()
        val columns = if (wide) 4 else 2
        var row: LinearLayout? = null
        favourites.forEachIndexed { index, (name, title) ->
            val preset = SoundPresets.YARIS.first { it.name == name }
            if (index % columns == 0) { row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }; body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (index == 0) 6 else 10) }) }
            val tile = TextView(context).apply {
                textSize = 21f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); maxLines = 2
                isClickable = true; isFocusable = true; setPadding(dp(8), dp(6), dp(8), dp(6))
                contentDescription = "Preset $title"
                setOnClickListener { save(preset.apply(settings)); LoudnessVolumeTracker.refresh(context); rebuild() }
            }
            tiles += name to tile
            row!!.addView(tile, LinearLayout.LayoutParams(0, dp(96), 1f).apply { if (index % columns != columns - 1) rightMargin = dp(10) })
        }

        val controls = LinearLayout(context).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        fun addControl(view: View, first: Boolean) {
            controls.addView(view, if (wide) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (first) rightMargin = dp(10) }
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (first) bottomMargin = dp(10) })
        }
        addControl(overviewControl("Bass character", "Adds upper-bass harmonics the door speakers can play", settings.bassEnhance) { amount ->
            save(settings.copy(bassEnhance = amount, presetName = ""))
        }, true)
        addControl(overviewControl("Auto level", "Keeps songs and podcasts at a steady level", if (settings.autoLevel) settings.autoLevelAmount else 0) { amount ->
            save(settings.copy(autoLevel = amount > 0, autoLevelAmount = if (amount > 0) amount else settings.autoLevelAmount, presetName = ""))
        }, false)
        body.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val state = TextView(context).apply {
            textSize = 16f; setTextColor(mutedColor); gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56)
            setPadding(dp(16), dp(8), dp(16), dp(8)); background = rounded(Color.argb(20, 255, 255, 255), Color.argb(50, 255, 255, 255))
        }
        body.addView(state, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        val doors = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        doors.addView(buttonFactory("All presets") { soundPage = SoundPage.ALL_PRESETS; rebuild() }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { rightMargin = dp(10) })
        doors.addView(buttonFactory("Advanced  ›") { soundPage = SoundPage.ADVANCED; rebuild() }, LinearLayout.LayoutParams(0, dp(64), 1f))
        body.addView(doors, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        refreshOverview = {
            tiles.forEach { (name, tile) ->
                val active = settings.presetName == name
                tile.text = tile.contentDescription.toString().removePrefix("Preset ") + if (active) "\n✓ Selected" else ""
                tile.isSelected = active
                tile.setTextColor(if (active) Color.rgb(12, 17, 27) else textColor)
                tile.background = feedback(rounded(if (active) Color.WHITE else Color.argb(32, 255, 255, 255), if (active) Color.WHITE else Color.argb(60, 255, 255, 255)))
            }
            val forcedBy = limiterForcedBy()
            val limiterText = when {
                settings.limiter && forcedBy.isEmpty() -> "Limiter on"
                forcedBy.isNotEmpty() -> "Limiter on (${forcedBy.joinToString(", ")})"
                else -> "Limiter off"
            }
            val side = if (settings.driverSide == AudioEffectSettings.DRIVER_RIGHT) "right" else "left"
            state.text = "✓ $limiterText   ·   Driver on the $side" + (if (settings.presetName.isEmpty()) "   ·   Custom" else "")
        }
        refreshOverview?.invoke()
    }

    /** Which stages are forcing the limiter on right now (mirrors EqualizerDsp.configure). */
    private fun limiterForcedBy(): List<String> = listOfNotNull(
        "bass enhancer".takeIf { settings.bassEnhance > 0 },
        "auto level".takeIf { settings.autoLevel },
        "loudness".takeIf { settings.loudnessComp },
    )

    /** A card with a title, a large value between 64 dp − / + buttons, and a one-line hint. */
    private fun overviewControl(title: String, hint: String, initial: Int, onChange: (Int) -> Unit): View {
        var value = initial.coerceIn(0, 100)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(Color.argb(28, 255, 255, 255), Color.argb(60, 255, 255, 255))
        }
        card.addView(TextView(context).apply { text = title; textSize = 21f; setTextColor(textColor); setTypeface(typeface, Typeface.BOLD) })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val valueView = TextView(context).apply { textSize = 32f; setTextColor(textColor); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD) }
        lateinit var minus: View
        lateinit var plus: View
        fun repaint() {
            valueView.text = if (value == 0) "Off" else "$value %"
            minus.isEnabled = value > 0; plus.isEnabled = value < 100
            minus.alpha = if (minus.isEnabled) 1f else 0.35f
            plus.alpha = if (plus.isEnabled) 1f else 0.35f
        }
        minus = stepButton("−", "Decrease $title") { value = (value - 5).coerceAtLeast(0); repaint(); onChange(value) }
        plus = stepButton("+", "Increase $title") { value = (value + 5).coerceAtMost(100); repaint(); onChange(value) }
        row.addView(minus, LinearLayout.LayoutParams(dp(64), dp(64)))
        row.addView(valueView, LinearLayout.LayoutParams(0, dp(72), 1f))
        row.addView(plus, LinearLayout.LayoutParams(dp(64), dp(64)))
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        card.addView(TextView(context).apply { text = hint; textSize = 15f; setTextColor(mutedColor); gravity = Gravity.CENTER; maxLines = 2 })
        repaint()
        return card
    }

    // ---- advanced ------------------------------------------------------------------------------

    private fun buildAdvanced() {
        val deviceInfo = AudioEffectSettings.bandInfo()
        body.addView(heading("Advanced", "Current: ${settings.presetName.ifEmpty { "Custom" }}").also { presetSummary = it.findViewWithTag<TextView>("subtitle") })
        body.addView(switchFactory("12-band software equalizer", settings.dspMode,
            if (settings.dspMode) "On: DiPlay's own processing (12 bands, bass enhancer, dynamics, stereo, tone)."
            else "Off: only the head unit's own ${deviceInfo.centerHz.size}-band equalizer effect. Switch on for everything below.") { enabled ->
            save(settings.copy(dspMode = enabled)); rebuild()
        })
        buildBands(deviceInfo)
        if (settings.dspMode) {
            buildBass()
            buildDynamics()
            buildTone()
            buildStereo()
            buildParametric()
        } else {
            section("Device effects", "device") { card ->
                card.addView(stepper("Bass boost (device)", (0..100 step 10).toList(), settings.bassStrength / 10 / 10 * 10, { "$it %" }) { save(settings.copy(bassStrength = it * 10)) })
                card.addView(stepper("Loudness boost (device)", (0..20).toList(), settings.loudnessGainMb / 100, { if (it == 0) "Off" else "+$it dB" }) { save(settings.copy(loudnessGainMb = it * 100)) })
            }
        }
        body.addView(buttonFactory("Reset everything to flat") {
            val previous = settings
            save(AudioEffectSettings(enabled = true, dspMode = settings.dspMode, driverSide = settings.driverSide, presetName = "Flat"))
            rebuild()
            AlertDialog.Builder(context).setTitle("Sound reset to flat").setMessage("Your previous settings can be restored now.")
                .setPositiveButton("Undo") { _, _ -> save(previous); rebuild() }.setNegativeButton("Keep flat", null).show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(14) })
    }

    // ---- presets -------------------------------------------------------------------------------

    private fun buildPresets(deviceInfo: AudioEffectSettings.Companion.BandInfo) {
        body.addView(heading("All presets", "Current: ${settings.presetName.ifEmpty { "Custom" }}").also { presetSummary = it.findViewWithTag<TextView>("subtitle") })
        if (settings.dspMode) {
            val columns = if (wide) 4 else 2
            var row: LinearLayout? = null
            SoundPresets.YARIS.forEachIndexed { index, preset ->
                if (index % columns == 0) { row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }; body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }) }
                val active = settings.presetName == preset.name
                val tile = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = feedback(rounded(if (active) Color.WHITE else Color.argb(32, 255, 255, 255), if (active) Color.WHITE else Color.argb(60, 255, 255, 255)))
                    setPadding(dp(12), dp(10), dp(12), dp(10)); isClickable = true; isFocusable = true; isSelected = active
                    contentDescription = "Preset ${preset.name.removePrefix("Yaris · ")}" + if (active) ", selected" else ""
                    addView(TextView(context).apply { text = preset.name.removePrefix("Yaris · ") + if (active) "  ✓" else ""; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(if (active) Color.rgb(12, 17, 27) else textColor); maxLines = 2 })
                    addView(TextView(context).apply { text = preset.description; textSize = 13f; setTextColor(if (active) Color.rgb(30, 40, 56) else mutedColor); maxLines = 3; setPadding(0, dp(4), 0, 0) })
                    setOnClickListener { save(preset.apply(settings)); LoudnessVolumeTracker.refresh(context); rebuild() }
                }
                row!!.addView(tile, LinearLayout.LayoutParams(0, dp(if (wide) 120 else 128), 1f).apply { if (index % columns != columns - 1) rightMargin = dp(8) })
            }
            val remainder = SoundPresets.YARIS.size % columns
            if (remainder != 0) repeat(columns - remainder) { row!!.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { rightMargin = dp(8) }) }
        }
        body.addView(buttonFactory("More equalizer curves (bands only)…") {
            val names = EqPresets.ALL.map { it.name }
            val dialog = AlertDialog.Builder(context).setTitle("Equalizer curve (bands only)")
                .setItems(names.toTypedArray()) { _, index ->
                    val preset = EqPresets.ALL[index]
                    if (settings.dspMode) save(settings.copy(dspGainsDb = preset.db.toList(), presetName = preset.name))
                    else {
                        val mapped = EqPresets.forBands(preset, deviceInfo.centerHz)
                        save(settings.copy(bandLevelsMb = mapped.map { (it * 100).coerceIn(deviceInfo.minLevelMb, deviceInfo.maxLevelMb) }, presetName = preset.name))
                    }
                    rebuild()
                }.show()
            dialog.listView?.dividerHeight = dp(1)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(12) })
    }

    // ---- bands ---------------------------------------------------------------------------------

    private fun buildBands(deviceInfo: AudioEffectSettings.Companion.BandInfo) {
        section("Bands", "bands", hint = "Touch a column and slide up to boost, down to cut; the column you touch first stays selected for the whole slide.") { card ->
            val bands = EqualizerBandsView(context).apply { accent = this@EqualizerPanel.accent; textColor = this@EqualizerPanel.textColor; mutedColor = this@EqualizerPanel.mutedColor }
            if (settings.dspMode) {
                bands.minDb = EqualizerDsp.MIN_DB.toInt(); bands.maxDb = EqualizerDsp.MAX_DB.toInt()
                bands.centersHz = EqualizerDsp.CENTER_HZ.toList()
                bands.levelsDb = IntArray(EqualizerDsp.BANDS) { settings.dspGainsDb.getOrElse(it) { 0 } }
            } else {
                bands.minDb = deviceInfo.minLevelMb / 100; bands.maxDb = deviceInfo.maxLevelMb / 100
                bands.centersHz = deviceInfo.centerHz
                bands.levelsDb = IntArray(deviceInfo.centerHz.size) { (settings.bandLevelsMb.getOrElse(it) { 0 } / 100).coerceIn(bands.minDb, bands.maxDb) }
            }
            bands.onLevelChanged = { band, db ->
                if (settings.dspMode) {
                    val gains = MutableList(EqualizerDsp.BANDS) { settings.dspGainsDb.getOrElse(it) { 0 } }; gains[band] = db
                    save(settings.copy(dspGainsDb = gains, presetName = ""))
                } else {
                    val levels = MutableList(deviceInfo.centerHz.size) { settings.bandLevelsMb.getOrElse(it) { 0 } }
                    levels[band] = (db * 100).coerceIn(deviceInfo.minLevelMb, deviceInfo.maxLevelMb)
                    save(settings.copy(bandLevelsMb = levels, presetName = ""))
                }
            }
            card.addView(bands, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (wide) 250 else 220)))
            if (settings.dspMode) {
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(buttonFactory("Flatten bands") { save(settings.copy(dspGainsDb = List(EqualizerDsp.BANDS) { 0 }, presetName = "")); rebuild() }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { rightMargin = dp(8) })
                row.addView(buttonFactory("All bands −1 dB") { save(settings.copy(dspGainsDb = settings.dspGainsDb.map { (it - 1).coerceIn(-12, 12) }, presetName = "")); rebuild() }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { rightMargin = dp(8) })
                row.addView(buttonFactory("All bands +1 dB") { save(settings.copy(dspGainsDb = settings.dspGainsDb.map { (it + 1).coerceIn(-12, 12) }, presetName = "")); rebuild() }, LinearLayout.LayoutParams(0, dp(64), 1f))
                card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
        }
    }

    // ---- groups --------------------------------------------------------------------------------

    private fun buildBass() {
        section("Bass", "bass", hint = "Harmonics stand in for the sub-bass the door speakers cannot play; the subsonic filter removes what only flaps the cones. The enhancer keeps the limiter on.") { card ->
            card.addView(stepper("Bass enhancer", (0..100 step 10).toList(), settings.bassEnhance / 10 * 10, { if (it == 0) "Off" else "$it %" + (if (it >= 60) " · trims the real sub by ${"%.0f".format(6.0 * (it / 100.0) * (it / 100.0))} dB" else "") }) { save(settings.copy(bassEnhance = it, presetName = "")) })
            val crossovers = listOf(60, 70, 80, 90, 100, 110, 120, 140, 160, 200)
            card.addView(stepper("Enhancer crossover", crossovers, crossovers.minByOrNull { kotlin.math.abs(it - settings.bassEnhanceHz) } ?: 100, { "below $it Hz" }) { save(settings.copy(bassEnhanceHz = it, presetName = "")) })
            val subsonic = listOf(0, 20, 25, 30, 35, 40, 50, 60, 80, 100, 120)
            card.addView(stepper("Subsonic filter", subsonic, subsonic.minByOrNull { kotlin.math.abs(it - settings.subsonicHz) } ?: 0, { if (it == 0) "Off" else "−6 dB at $it Hz, 24 dB/octave below" }) { save(settings.copy(subsonicHz = it, presetName = "")) })
            card.addView(stepper("Bass boost (device effect)", (0..100 step 10).toList(), settings.bassStrength / 10 / 10 * 10, { if (it == 0) "Off" else "$it %" }) { save(settings.copy(bassStrength = it * 10)) })
        }
    }

    private fun buildDynamics() {
        section("Loudness and dynamics", "dyn") { card ->
            card.addView(switchFactory("Auto level", settings.autoLevel, "Evens out quiet podcasts and loud tracks (weighted programme level toward a fixed target). Turns the limiter on while active.") { save(settings.copy(autoLevel = it, presetName = "")) })
            card.addView(stepper("Auto level strength", (0..100 step 10).toList(), settings.autoLevelAmount / 10 * 10, { if (it == 0) "Off" else "$it %" }) { save(settings.copy(autoLevelAmount = it, presetName = "")) })
            val forced = limiterForcedBy()
            card.addView(switchFactory("Limiter", settings.limiter,
                "True-peak limiter, ceiling ${"%.2f".format(EqualizerDsp.LIMITER_CEILING_DB)} dBFS, 2 ms look-ahead. Digital peak protection only; it cannot protect the speakers." +
                    if (forced.isNotEmpty()) "\nCurrently kept on by: ${forced.joinToString(", ")}." else "") { save(settings.copy(limiter = it, presetName = "")) })
            val detected = runCatching { LoudnessVolumeTracker.describe(context) }.getOrDefault("")
            card.addView(switchFactory("Loudness compensation (volume-linked)", settings.loudnessComp,
                "Fuller bass and treble when the volume is low, nothing at full volume: a broad voicing inspired by equal-loudness contours, from the estimated Android volume. Needs the knob to be Android's volume." + (if (detected.isNotEmpty()) "\n$detected" else "")) {
                save(settings.copy(loudnessComp = it, presetName = "")); LoudnessVolumeTracker.refresh(context)
            })
            card.addView(stepper("Loudness amount", (0..100 step 10).toList(), settings.loudnessCompAmount / 10 * 10, { if (it == 0) "Off" else "$it %" }) { save(settings.copy(loudnessCompAmount = it, presetName = "")) })
            val maxStep = runCatching { context.getSystemService(AudioManager::class.java)?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()?.takeIf { it > 0 } ?: 15
            card.addView(stepper("Reference volume step", (0..maxStep).toList(), settings.loudnessCompReferenceStep.coerceIn(0, maxStep),
                { if (it == 0 || it == maxStep) "Maximum ($maxStep)" else "$it of $maxStep" }) { save(settings.copy(loudnessCompReferenceStep = it)); LoudnessVolumeTracker.refresh(context) })
            card.addView(stepper("Loudness boost (device effect)", (0..20).toList(), settings.loudnessGainMb / 100, { if (it == 0) "Off" else "+$it dB" }) { save(settings.copy(loudnessGainMb = it * 100)) })
        }
    }

    private fun buildTone() {
        section("Tone", "tone", hint = "Air lifts the top for the dash tweeters; de-ess backs the lift off while \"s\" sounds are present (it does not cut sibilance that is already in the mix).") { card ->
            card.addView(stepper("Air (high shelf)", (0..AirShelf.MAX_DB).toList(), settings.airDb.coerceIn(0, AirShelf.MAX_DB), { if (it == 0) "Off" else "+$it dB" }) { save(settings.copy(airDb = it, presetName = "")) })
            card.addView(stepper("Air corner", AirShelf.CORNERS_HZ, AirShelf.snapCorner(settings.airHz), { "above ${it / 1000} kHz" }) { save(settings.copy(airHz = it, presetName = "")) })
            card.addView(stepper("De-ess", (0..100 step 10).toList(), settings.deEss.coerceIn(0, 100) / 10 * 10, { if (it == 0) "Off" else "$it %" }) { save(settings.copy(deEss = it, presetName = "")) })
        }
    }

    private fun buildStereo() {
        section("Stereo", "stereo", hint = "Widening works above 200 Hz, so the bass image stays where it was recorded. The driver-seat delay centres the image on the driver.") { card ->
            card.addView(stepper("Width", (0..AudioEffectSettings.MAX_STEREO_WIDTH step 10).toList(), settings.stereoWidth.coerceIn(0, AudioEffectSettings.MAX_STEREO_WIDTH) / 10 * 10,
                { when { it == 100 -> "100 % · as recorded"; it == 0 -> "mono"; it < 100 -> "$it % · narrower"; else -> "$it % · wider" } }) { save(settings.copy(stereoWidth = it, presetName = "")) })
            card.addView(stepper("Balance", (-AudioEffectSettings.MAX_BALANCE..AudioEffectSettings.MAX_BALANCE step 10).toList(), settings.balance.coerceIn(-100, 100) / 10 * 10,
                { when { it == 0 -> "Centre"; it < 0 -> "${-it} % left"; else -> "$it % right" } }) { save(settings.copy(balance = it, presetName = "")) })
            card.addView(stepper("Driver-seat delay", (0..AudioEffectSettings.MAX_DRIVER_DELAY_TENTHS step 2).toList(), settings.driverSeatDelayTenthsMs.coerceIn(0, AudioEffectSettings.MAX_DRIVER_DELAY_TENTHS) / 2 * 2,
                { if (it == 0) "Off" else "%.1f ms (≈%d cm)".format(it / 10.0, it * 34 / 10) }) { save(settings.copy(driverSeatDelayTenthsMs = it, presetName = "")) })
            card.addView(switchFactory("Driver sits on the right", settings.driverSide == AudioEffectSettings.DRIVER_RIGHT, "Off for left-hand-drive cars.") {
                save(settings.copy(driverSide = if (it) AudioEffectSettings.DRIVER_RIGHT else AudioEffectSettings.DRIVER_LEFT))
            })
            card.addView(stepper("Crossfeed", (0..AudioEffectSettings.MAX_CROSSFEED step 10).toList(), settings.crossfeed.coerceIn(0, 100) / 10 * 10, { if (it == 0) "Off" else "$it %" }) { save(settings.copy(crossfeed = it, presetName = "")) })
        }
    }

    private fun buildParametric() {
        section("Parametric EQ", "param", hint = "Up to five bands with their own frequency, gain and width. Tap a band to pick its type.") { card ->
            val frequencies = listOf(30, 40, 50, 60, 70, 80, 90, 100, 120, 140, 160, 180, 200, 250, 300, 350, 400, 500, 600, 700, 800, 1000, 1200, 1500, 1800, 2000, 2500, 3000, 3500, 4000, 5000, 6000, 7000, 8000, 10000, 12000, 14000, 16000)
            val qTenths = listOf(3, 5, 7, 10, 14, 20, 30, 40, 60)
            val current = List(EqualizerDsp.MAX_PARAMETRIC) { settings.parametric.getOrElse(it) { ParametricBand() } }
            val shown = minOf(EqualizerDsp.MAX_PARAMETRIC, current.indexOfLast { it.type != ParametricBand.OFF } + 2)
            fun update(index: Int, change: (ParametricBand) -> ParametricBand) {
                val next = MutableList(EqualizerDsp.MAX_PARAMETRIC) { settings.parametric.getOrElse(it) { ParametricBand() } }
                next[index] = change(next[index])
                save(settings.copy(parametric = next, presetName = ""))
            }
            val types = listOf("Off", "Peak (bell)", "Low shelf", "High shelf")
            for (index in 0 until shown) {
                val band = current[index]
                card.addView(buttonFactory("Band ${index + 1}: ${band.describe()}") {
                    AlertDialog.Builder(context).setTitle("Band ${index + 1} type")
                        .setSingleChoiceItems(types.toTypedArray(), band.type) { dialog, which -> update(index) { it.copy(type = which) }; dialog.dismiss(); rebuild() }
                        .setNegativeButton("Cancel", null).show()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(6) })
                if (band.type != ParametricBand.OFF) {
                    card.addView(stepper("Frequency", frequencies, frequencies.minByOrNull { kotlin.math.abs(it - band.hz) } ?: 1000, { if (it >= 1000) "${it / 1000.0} kHz" else "$it Hz" }) { hz -> update(index) { it.copy(hz = hz) } })
                    card.addView(stepper("Gain", (-12..12).toList(), Math.round(band.gainDb).coerceIn(-12, 12), { (if (it > 0) "+" else "") + "$it dB" }) { db -> update(index) { it.copy(gainTenths = db * 10) } })
                    if (band.type == ParametricBand.PEAK) card.addView(stepper("Width (Q)", qTenths, qTenths.minByOrNull { kotlin.math.abs(it - band.qTenths) } ?: 10, { "Q ${it / 10.0}" }) { q -> update(index) { it.copy(qTenths = q) } })
                }
            }
        }
    }

    // ---- widgets -------------------------------------------------------------------------------

    private fun heading(title: String, subtitle: String?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(2))
        addView(TextView(context).apply { text = title; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(textColor) })
        if (subtitle != null) addView(TextView(context).apply { text = subtitle; textSize = 15f; setTextColor(mutedColor); tag = "subtitle" })
    }

    /** A collapsible group with a big header; open/closed state survives rebuilds and reopenings. */
    private fun section(title: String, key: String, hint: String? = null, build: (LinearLayout) -> Unit) {
        val open = expanded[key] ?: (key == "bands")
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = feedback(rounded(Color.argb(28, 255, 255, 255), Color.argb(50, 255, 255, 255))); setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true; isFocusable = true
        }
        header.addView(TextView(context).apply { text = title; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(textColor) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val chevron = TextView(context).apply { text = if (open) "▾" else "▸"; textSize = 22f; setTextColor(accent) }
        header.addView(chevron)
        body.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(12) })
        val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(6), dp(4), dp(6)); visibility = if (open) View.VISIBLE else View.GONE }
        if (hint != null) card.addView(TextView(context).apply { text = hint; textSize = 14f; setTextColor(mutedColor); setPadding(0, 0, 0, dp(4)) })
        build(card)
        body.addView(card)
        header.contentDescription = "$title, ${if (open) "expanded" else "collapsed"}"
        header.setOnClickListener {
            val now = card.visibility != View.VISIBLE
            expanded[key] = now
            card.visibility = if (now) View.VISIBLE else View.GONE
            chevron.text = if (now) "▾" else "▸"
            header.contentDescription = "$title, ${if (now) "expanded" else "collapsed"}"
        }
    }

    /** Title and value on the left, 64 dp −/+ buttons on the right: no dragging needed. */
    private fun stepper(title: String, values: List<Int>, current: Int, describe: (Int) -> String, onChange: (Int) -> Unit): View {
        var index = values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 0
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val titleView = TextView(context).apply { this.text = title; textSize = 18f; setTextColor(textColor) }
        val valueView = TextView(context).apply { this.text = describe(values[index]); textSize = 16f; setTextColor(accent) }
        text.addView(titleView); text.addView(valueView)
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        lateinit var minus: View
        lateinit var plus: View
        fun repaint() {
            minus.isEnabled = index > 0; plus.isEnabled = index < values.size - 1
            minus.alpha = if (minus.isEnabled) 1f else 0.35f; plus.alpha = if (plus.isEnabled) 1f else 0.35f
        }
        fun step(delta: Int) {
            val next = (index + delta).coerceIn(0, values.size - 1)
            if (next != index) { index = next; valueView.text = describe(values[index]); onChange(values[index]) }
            repaint()
        }
        minus = stepButton("−", "Decrease $title") { step(-1) }
        plus = stepButton("+", "Increase $title") { step(+1) }
        row.addView(minus, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(8) })
        row.addView(plus, LinearLayout.LayoutParams(dp(64), dp(64)))
        repaint()
        return row
    }

    private fun stepButton(glyph: String, description: String, click: () -> Unit) = TextView(context).apply {
        text = glyph; textSize = 28f; gravity = Gravity.CENTER; setTextColor(textColor); contentDescription = description
        background = feedback(rounded(Color.argb(40, 255, 255, 255), Color.argb(70, 255, 255, 255))); isClickable = true; isFocusable = true
        setOnClickListener { click() }
    }

    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(14).toFloat(); setStroke(dp(1), stroke) }
    private fun feedback(content: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), content, null)

    private fun LinearLayout.addView(view: View, params: LinearLayout.LayoutParams) = (this as ViewGroup).addView(view, params)

    companion object {
        /** Open/closed state of the groups, shared across panel instances. */
        private val expanded = HashMap<String, Boolean>()
        /** Which sound page is showing; survives rebuilds and reopenings within the process. */
        private var soundPage = SoundPage.OVERVIEW
    }
}
