package com.shilapi.xcertplay.media

import kotlin.math.ln

/** Graphic equalizer presets defined on the 12 software bands, interpolated for other band layouts. */
object EqPresets {
    class Preset(val name: String, val db: IntArray)

    //               31  62 125 250 500  1k  2k  3k  4k  8k 12k 16k
    val ALL: List<Preset> = listOf(
        Preset("Flat",            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
        //                         31  62 125 250 500  1k  2k  3k  4k  8k 12k 16k
        Preset("Sound Good",      intArrayOf(4, 3, 2, 1, -2, -3, -1, 0, 1, 2, 3, 3)),
        Preset("Highs Only",      intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 4, 4)),
        // The user's Android phone EQ used with Android Auto: 60 Hz +7, 230 Hz +1.5, 910 Hz 0, 4 kHz +1, 14 kHz +3.
        Preset("Phone (Android Auto)", intArrayOf(7, 7, 5, 2, 1, 0, 0, 1, 1, 2, 3, 3)),
        Preset("Bass Boost",      intArrayOf(7, 6, 5, 3, 1, 0, 0, 0, 0, 0, 0, 0)),
        Preset("Bass Reducer",    intArrayOf(-7, -6, -5, -3, -1, 0, 0, 0, 0, 0, 0, 0)),
        Preset("Treble Boost",    intArrayOf(0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 6)),
        Preset("Treble Reducer",  intArrayOf(0, 0, 0, 0, 0, -1, -2, -3, -4, -5, -6, -6)),
        Preset("Vocal Booster",   intArrayOf(-2, -2, -1, 0, 2, 4, 4, 3, 2, 1, 0, -1)),
        Preset("Loudness",        intArrayOf(6, 5, 3, 1, 0, -1, 0, 1, 2, 3, 4, 4)),
        Preset("Rock",            intArrayOf(5, 4, 3, 1, -1, -1, 1, 2, 3, 4, 4, 4)),
        Preset("Pop",             intArrayOf(-1, 0, 1, 2, 4, 4, 3, 2, 1, 0, -1, -1)),
        Preset("Jazz",            intArrayOf(4, 3, 1, 2, -1, -1, 0, 1, 2, 3, 4, 4)),
        Preset("Classical",       intArrayOf(4, 3, 2, 1, -1, -1, 0, 1, 2, 3, 3, 4)),
        Preset("Dance",           intArrayOf(6, 5, 3, 0, 1, 3, 4, 4, 3, 2, 1, 0)),
        Preset("Electronic",      intArrayOf(5, 4, 2, 0, -1, 2, 1, 2, 3, 4, 5, 5)),
        Preset("Hip-Hop",         intArrayOf(6, 5, 3, 2, -1, -1, 1, 2, 2, 3, 3, 4)),
        Preset("R&B",             intArrayOf(3, 6, 5, 2, -2, -1, 2, 3, 3, 3, 4, 4)),
        Preset("Acoustic",        intArrayOf(4, 4, 3, 1, 2, 2, 3, 3, 4, 3, 2, 2)),
        Preset("Latin",           intArrayOf(4, 3, 0, 0, -1, -1, -1, 0, 2, 3, 4, 4)),
        Preset("Piano",           intArrayOf(3, 2, 0, 2, 3, 2, 3, 4, 3, 3, 4, 4)),
        Preset("Small Speakers",  intArrayOf(-6, -5, -2, 1, 3, 3, 3, 2, 2, 2, 3, 3)),
        Preset("Spoken Word",     intArrayOf(-3, -2, 0, 1, 3, 4, 4, 4, 3, 2, 0, -1)),
        Preset("Deep",            intArrayOf(5, 4, 2, 1, 3, 2, 0, -2, -3, -4, -5, -5)),
        Preset("Late Night",      intArrayOf(3, 2, 1, 0, -1, -1, 0, 0, 0, 0, -1, -2)),
        Preset("Lounge",          intArrayOf(-3, -2, -1, 1, 4, 3, 0, -2, 2, 1, 1, 1)),
        Preset("Car (road noise)", intArrayOf(2, 3, 2, 0, -1, 1, 3, 3, 2, 2, 1, 0)),
    )

    /** Maps a 12-band preset onto arbitrary center frequencies by log-frequency interpolation. */
    fun forBands(preset: Preset, centersHz: List<Int>): IntArray = IntArray(centersHz.size) { i ->
        val f = ln(centersHz[i].coerceAtLeast(1).toDouble())
        val xs = EqualizerDsp.CENTER_HZ.map { ln(it.toDouble()) }
        when {
            f <= xs.first() -> preset.db.first()
            f >= xs.last() -> preset.db.last()
            else -> {
                val k = xs.indexOfFirst { it >= f }
                val t = (f - xs[k - 1]) / (xs[k] - xs[k - 1])
                Math.round(preset.db[k - 1] + (preset.db[k] - preset.db[k - 1]) * t).toInt()
            }
        }
    }
}
