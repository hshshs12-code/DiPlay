package com.shilapi.xcertplay.media

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import java.io.Closeable
import java.util.Collections

/**
 * Android audio effects attached to one AudioTrack session: Equalizer, BassBoost and
 * LoudnessEnhancer. Created when a media track starts and released with it; settings changes
 * reach every live instance through [applyToActive].
 */
class AudioEffectController private constructor(private val sessionId: Int) : Closeable {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null

    fun apply(settings: AudioEffectSettings) {
        synchronized(this) {
            if (!settings.enabled) {
                equalizer?.enabled = false
                bassBoost?.enabled = false
                loudness?.enabled = false
                return
            }
            try {
                val eq = equalizer ?: Equalizer(PRIORITY, sessionId).also { equalizer = it }
                val range = eq.bandLevelRange
                for (band in 0 until eq.numberOfBands.toInt()) {
                    val level = settings.bandLevelsMb.getOrElse(band) { 0 }
                        .coerceIn(range[0].toInt(), range[1].toInt())
                    eq.setBandLevel(band.toShort(), level.toShort())
                }
                eq.enabled = settings.bandLevelsMb.any { it != 0 }
            } catch (failure: Exception) {
                Log.w(TAG, "equalizer unavailable session=$sessionId", failure)
            }
            try {
                val bass = bassBoost ?: BassBoost(PRIORITY, sessionId).also { bassBoost = it }
                if (bass.strengthSupported) bass.setStrength(settings.bassStrength.toShort())
                bass.enabled = settings.bassStrength > 0
            } catch (failure: Exception) {
                Log.w(TAG, "bass boost unavailable session=$sessionId", failure)
            }
            try {
                val enhancer = loudness ?: LoudnessEnhancer(sessionId).also { loudness = it }
                enhancer.setTargetGain(settings.loudnessGainMb)
                enhancer.enabled = settings.loudnessGainMb > 0
            } catch (failure: Exception) {
                Log.w(TAG, "loudness enhancer unavailable session=$sessionId", failure)
            }
        }
    }

    fun describe(): String = synchronized(this) {
        "effects session=$sessionId eq=${equalizer?.enabled ?: "none"} bass=${bassBoost?.enabled ?: "none"} " +
            "loudness=${loudness?.enabled ?: "none"}"
    }

    override fun close() {
        synchronized(this) {
            runCatching { equalizer?.release() }; equalizer = null
            runCatching { bassBoost?.release() }; bassBoost = null
            runCatching { loudness?.release() }; loudness = null
        }
        active.remove(this)
    }

    companion object {
        private const val TAG = "xcertplay-audiofx"
        private const val PRIORITY = 0
        private val active: MutableSet<AudioEffectController> =
            Collections.synchronizedSet(mutableSetOf<AudioEffectController>())

        /** Attaches the configured effects to [sessionId]; returns null when effects are off. */
        fun attach(sessionId: Int, settings: AudioEffectSettings): AudioEffectController? {
            if (!settings.enabled || sessionId == 0) return null
            val controller = AudioEffectController(sessionId)
            controller.apply(settings)
            active.add(controller)
            return controller
        }

        fun applyToActive(settings: AudioEffectSettings) {
            val snapshot = synchronized(active) { active.toList() }
            snapshot.forEach { runCatching { it.apply(settings) } }
        }

        fun activeCount(): Int = active.size
    }
}
