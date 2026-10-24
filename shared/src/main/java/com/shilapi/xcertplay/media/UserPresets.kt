// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: presets the user saves (from the iPhone companion app or the head unit).
package com.shilapi.xcertplay.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Named snapshots of the whole sound chain, stored on the head unit. Unlike [SoundPresets] they
 * are complete settings objects, so applying one restores everything except the car-side facts
 * (which seat the driver is in, the output trim) that stay as they are.
 */
object UserPresets {
    class Entry(val name: String, val settings: AudioEffectSettings)

    const val MAX_PRESETS = 40
    const val MAX_NAME_LENGTH = 40
    private const val PREFS = "diplay_user_presets"
    private const val KEY = "presets"

    fun load(context: Context): List<Entry> {
        val text = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return parse(text)
    }

    fun find(context: Context, name: String): Entry? = load(context).firstOrNull { it.name == name }

    /** Adds or replaces the preset called [name] with [settings]; returns the new list. */
    fun save(context: Context, name: String, settings: AudioEffectSettings): List<Entry> {
        val clean = cleanName(name) ?: return load(context)
        val next = load(context).filterNot { it.name == clean } + Entry(clean, settings.copy(presetName = clean))
        store(context, next.takeLast(MAX_PRESETS))
        return load(context)
    }

    fun delete(context: Context, name: String): List<Entry> {
        store(context, load(context).filterNot { it.name == name })
        return load(context)
    }

    /** The preset's settings on top of the current car-side facts. */
    fun apply(entry: Entry, current: AudioEffectSettings): AudioEffectSettings =
        entry.settings.copy(driverSide = current.driverSide, outputTrimDb = current.outputTrimDb, presetName = entry.name)

    fun cleanName(name: String): String? = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LENGTH).ifEmpty { null }

    fun encode(entries: List<Entry>): String = JSONArray().apply {
        entries.forEach { put(JSONObject().put("name", it.name).put("s", AudioEffectSettingsJson.toJson(it.settings))) }
    }.toString()

    fun parse(text: String): List<Entry> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val name = cleanName(item.optString("name", "")) ?: return@mapNotNull null
            val settings = item.optJSONObject("s")?.let { AudioEffectSettingsJson.merge(AudioEffectSettings(), it) } ?: return@mapNotNull null
            Entry(name, settings.copy(presetName = name))
        }
    }.getOrDefault(emptyList())

    private fun store(context: Context, entries: List<Entry>) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encode(entries)).apply()
    }
}
