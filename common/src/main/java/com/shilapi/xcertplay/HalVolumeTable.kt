// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: reads the MediaTek audio HAL's per-step volume table the way EngineerMode does.
package com.shilapi.xcertplay

import android.media.AudioManager
import android.util.Base64

/**
 * The MediaTek HAL (audio.primary.*.so) keeps a custom volume table in NVRAM: for each volume
 * type, mode and level, one byte (0–255) that becomes the analog speaker gain plus the digital
 * hardware gain for that step. It is what the unit's EngineerMode app (Audio → Normal /
 * LoudSpeaker Mode) edits, and the only per-step gain stage on units without an external audio
 * processor. EngineerMode reads it through plain `AudioSystem.getParameters` strings that any
 * app with MODIFY_AUDIO_SETTINGS may send:
 *
 * - `GET_AUDIO_VOLUME_VERSION` → "=1" selects the version-1 table used here (9 types × 4 modes × 15 levels).
 * - `GetCmd=5` → the table's size in bytes (EngineerMode asserts it equals its own computation, 581).
 * - `GetBuffer=256,<size>` → the table, base64-encoded (256 = EngineerMode's "get customer data" type).
 *
 * This only reads. Writing (`SetBuffer=257,…` then `ReloadAudioVolume`) is left to EngineerMode.
 */
object HalVolumeTable {
    /** Type order from EngineerMode's spinner (version-1 table). Media is what music plays through. */
    val TYPES = listOf("Ring", "Sip", "Mic", "FMR", "Sph", "Sph2", "Sid", "Media", "Matv")
    /** Mode order from EngineerMode's Audio menu. A car unit's speaker path is "LoudSpeaker". */
    val MODES = listOf("Normal", "Headset", "LoudSpeaker", "Headset+LoudSpeaker")
    const val LEVELS = 15
    const val GET_CUSTOMER_DATA = 256
    const val SIZE_COMMAND = 5
    /** 4 modes × 15 levels × 9 types, then five "max level per usage" blocks (6+6+6+8+6) and 9 per-type bytes. */
    const val EXPECTED_SIZE = 4 * LEVELS * 9 + 6 + 6 + 6 + 8 + 6 + 9
    const val TYPE_MEDIA = 7

    class Result(
        val version: String, val custXml: String, val sizeAnswer: String, val size: Int,
        val raw: String, val bytes: ByteArray?, val levels: Array<Array<IntArray>>?,
    ) {
        val decoded: Boolean get() = levels != null

        fun describe(): String = buildString {
            appendLine("GET_AUDIO_VOLUME_VERSION -> ${version.ifEmpty { "(no answer)" }}")
            appendLine("GET_CUST_XML_ENABLE -> ${custXml.ifEmpty { "(no answer)" }}")
            appendLine("GetCmd=$SIZE_COMMAND (table size) -> ${sizeAnswer.ifEmpty { "(no answer)" }} → using $size")
            if (levels == null) {
                appendLine("GetBuffer=$GET_CUSTOMER_DATA,$size -> ${if (raw.isEmpty()) "(no answer)" else raw.take(400)}")
                appendLine(if (bytes == null) "Not decodable as base64 or hex: this firmware does not expose the table this way." else "Decoded ${bytes.size} bytes, expected at least ${4 * LEVELS * 9}: layout unknown.")
                return@buildString
            }
            appendLine("Decoded ${bytes?.size} bytes. Values 0–255 per level; higher = louder on this HAL.")
            appendLine()
            appendLine("Media (music), levels 0–14:")
            for (mode in MODES.indices) appendLine("  ${MODES[mode].padEnd(19)} ${levels[TYPE_MEDIA][mode].joinToString(" ") { it.toString().padStart(3) }}")
            appendLine()
            appendLine("Other types, LoudSpeaker mode:")
            for (type in TYPES.indices) if (type != TYPE_MEDIA) appendLine("  ${TYPES[type].padEnd(19)} ${levels[type][2].joinToString(" ") { it.toString().padStart(3) }}")
            val tail = bytes?.let { if (it.size > 4 * LEVELS * 9) it.copyOfRange(4 * LEVELS * 9, it.size) else null }
            if (tail != null) appendLine().appendLine("Trailing bytes (max level per usage, per type): ${tail.joinToString(" ") { (it.toInt() and 0xff).toString() }}")
            appendLine()
            appendLine("The knob's 30 steps land on these 15 levels two steps per level (assumption to verify: step 19 → level 9, step 20 → level 10). A jump between two neighbouring values here is a jump you hear.")
        }
    }

    fun read(manager: AudioManager): Result {
        fun ask(key: String): String = runCatching { manager.getParameters(key) ?: "" }.getOrDefault("").trim()
        fun value(answer: String, key: String): String = if (answer.startsWith("$key=")) answer.substring(key.length + 1).trim() else answer
        val version = ask("GET_AUDIO_VOLUME_VERSION")
        val custXml = ask("GET_CUST_XML_ENABLE")
        val sizeAnswer = ask("GetCmd=$SIZE_COMMAND")
        val size = value(sizeAnswer, "GetCmd").toIntOrNull()?.takeIf { it in 64..4096 } ?: EXPECTED_SIZE
        val key = "GetBuffer=$GET_CUSTOMER_DATA,$size"
        val raw = ask(key)
        val payload = value(raw, "GetBuffer")
        val bytes = decode(payload)
        val levels = bytes?.takeIf { it.size >= 4 * LEVELS * 9 }?.let { data ->
            Array(TYPES.size) { type -> Array(MODES.size) { mode -> IntArray(LEVELS) { level -> data[type * MODES.size * LEVELS + mode * LEVELS + level].toInt() and 0xff } } }
        }
        return Result(version, custXml, sizeAnswer, size, raw, bytes, levels)
    }

    private fun decode(payload: String): ByteArray? {
        if (payload.isEmpty() || payload.length < 16) return null
        val compact = payload.filterNot { it.isWhitespace() }
        if (compact.length % 2 == 0 && compact.all { it in "0123456789abcdefABCDEF" } && compact.length >= 64) {
            return ByteArray(compact.length / 2) { compact.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        for (flags in intArrayOf(Base64.DEFAULT, Base64.NO_WRAP, Base64.NO_WRAP or Base64.NO_PADDING, Base64.URL_SAFE)) {
            val decoded = runCatching { Base64.decode(compact, flags) }.getOrNull()
            if (decoded != null && decoded.size >= 32) return decoded
        }
        return null
    }
}
