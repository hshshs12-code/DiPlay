package com.shilapi.xcertplay.media

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Stereo "stage" for one L/R pair, run in double precision inside [EqualizerDsp] right before auto
 * level. The amplifier does fader/front-rear after us, so everything here is plain L/R processing:
 *
 * - **Width** (0..200 %, 100 = unchanged) works on mid/side. Below 100 the whole side signal is
 *   scaled down (0 = mono). Above 100 only a high-passed copy of the side is added (200 Hz,
 *   second order from two cascaded one-poles), so bass is not widened (it stays as centred as the
 *   recording made it; the side changes < 1 dB below 60 Hz even at 200) and the mid signal is
 *   never touched: the mono sum L+R is identical at every width.
 * - **Crossfeed** (0..100) is a Bauer-style low-passed cross-bleed: each side receives
 *   `a·LP(other) − a·LP(self)` with a 700 Hz first-order low-pass, i.e. the low-frequency side
 *   signal is reduced. The low-pass group delay (~0.23 ms) supplies the interaural time cue, and
 *   centred (mono) content is untouched, so there is no comb filtering or level change. At 100 the
 *   bleed is about −7.4 dB at low frequencies, fading out above ~2 kHz.
 * - **Balance** (−100..100) uses the constant-power (sin/cos) pan law normalised to unity at the
 *   centre, so L²+R² power is constant across the travel; full left mutes the right channel.
 * - **Driver-seat delay** (0..6 ms, 0.1 ms steps) delays the near speaker so both wavefronts reach
 *   the driver together and the image centres on the driver's seat. A fractional delay line with
 *   4-point Lagrange interpolation (exact when the delay is a whole number of samples) keeps
 *   0.1 ms steps accurate at 44.1 and 48 kHz.
 *
 * Every parameter change is click-free: gains follow their targets through a 20 ms one-pole, and
 * delays glide at 1 % speed (≤ 17 cents of transient pitch shift, 6 ms in 0.6 s) instead of
 * jumping. Cost is about 25 multiply-adds per frame while active; the stage switches itself off
 * (zero cost) once it has settled at neutral.
 *
 * Thread safety: [configure] may run on any thread; it publishes an immutable target set that the
 * audio thread picks up at the next [beginBlock]. All other methods belong to the audio thread.
 */
class StereoStage(private val sampleRate: Int) {
    private class Targets(
        val side: Double, val extra: Double, val cross: Double,
        val left: Double, val right: Double, val delayLeft: Double, val delayRight: Double,
        /** Set on the very first configuration: start exactly at the targets instead of ramping. */
        val snap: Boolean = false,
    ) {
        val neutral: Boolean
            get() = side == 1.0 && extra == 0.0 && cross == 0.0 && left == 1.0 && right == 1.0 && delayLeft == 0.0 && delayRight == 0.0

        fun sameAs(o: Targets?) = o != null && side == o.side && extra == o.extra && cross == o.cross &&
            left == o.left && right == o.right && delayLeft == o.delayLeft && delayRight == o.delayRight
    }

    private val pending = AtomicReference<Targets?>(null)
    @Volatile private var lastConfigured: Targets? = null
    @Volatile private var running = false

    // Audio-thread state: current (ramped) gains and their targets.
    private var target = NEUTRAL
    private var side = 1.0
    private var extra = 0.0
    private var cross = 0.0
    private var left = 1.0
    private var right = 1.0
    private var delayLeft = 0.0
    private var delayRight = 0.0
    private var ramping = false
    private var settleCounter = 0

    private val ramp = 1.0 - exp(-1.0 / (RAMP_SECONDS * sampleRate))
    private val wideG = tptGain(WIDEN_HP_HZ)
    private val crossG = tptGain(CROSSFEED_LP_HZ)
    private var wideZ = 0.0
    private var wideZ2 = 0.0
    private var crossZ = 0.0

    // Delay lines, one per channel (either side can be the near one, and a side change glides both).
    private val size = Integer.highestOneBit(maxDelaySamples(sampleRate).toInt() + 8) shl 1
    private val mask = size - 1
    private val ringLeft = DoubleArray(size)
    private val ringRight = DoubleArray(size)
    private var pos = 0
    /** Valid history in the rings (including the current frame), saturating at [size]. */
    private var written = 0

    /**
     * Sets the stage from user values: [width] 0..200 (100 = unchanged), [balance] −100..100,
     * [delayTenthsMs] 0..60 on the [driverSide] speaker (0 = left, 1 = right), [crossfeed] 0..100.
     */
    fun configure(width: Int, balance: Int, delayTenthsMs: Int, driverSide: Int, crossfeed: Int) {
        val w = width.coerceIn(0, 200) / 100.0
        val s = min(w, 1.0)
        val e = max(w - 1.0, 0.0)
        val c = 2.0 * CROSSFEED_MAX * crossfeed.coerceIn(0, 100) / 100.0 * s
        val (gl, gr) = panGains(balance)
        val d = delayTenthsMs.coerceIn(0, MAX_DELAY_TENTHS) * sampleRate / 10_000.0
        val first = lastConfigured == null
        val next = Targets(s, e, c, gl, gr, if (driverSide == 1) 0.0 else d, if (driverSide == 1) d else 0.0, snap = first)
        if (next.sameAs(lastConfigured)) return
        lastConfigured = next
        // Neutral while idle (nothing pending, nothing running): the stage already passes audio
        // through, so stay off. The audio thread raises `running` before it empties `pending`, so
        // a target it is about to apply is always seen here.
        if (next.neutral && pending.get() == null && !running) return
        pending.set(next)
    }

    /** True while the stage changes the signal (or is still ramping back to neutral). */
    fun isActive(): Boolean = running || pending.get() != null

    /** Picks up new targets; call once per block before [processFrame]. */
    fun beginBlock() {
        if (pending.get() == null) return
        val wasRunning = running
        running = true
        val next = pending.getAndSet(null) ?: return
        target = next
        if (next.snap) {
            // First configuration, before any audio: start exactly at the targets (silence as history).
            side = next.side; extra = next.extra; cross = next.cross; left = next.left; right = next.right
            delayLeft = next.delayLeft; delayRight = next.delayRight
            ringLeft.fill(0.0); ringRight.fill(0.0); written = size
            ramping = false
            running = !next.neutral
            return
        }
        if (!wasRunning) {
            // Waking from idle: the rings hold stale audio, so the delay may only grow into fresh history.
            ringLeft.fill(0.0); ringRight.fill(0.0); written = 0
            wideZ = 0.0; wideZ2 = 0.0; crossZ = 0.0
        }
        ramping = true
        settleCounter = 0
    }

    /** Processes one stereo frame in place: `frame[0]` = left, `frame[1]` = right. */
    fun processFrame(frame: DoubleArray) {
        if (ramping) step()
        val l = frame[0]
        val r = frame[1]
        val m = 0.5 * (l + r)
        val s = 0.5 * (l - r)
        // Topology-preserving one-poles on the side signal: two cascaded 200 Hz high-passes (each
        // the complement of a low-pass) for widening, one 700 Hz low-pass for crossfeed.
        val v1 = (s - wideZ) * wideG
        val l1 = v1 + wideZ
        wideZ = l1 + v1
        val h1 = s - l1
        val v2 = (h1 - wideZ2) * wideG
        val l2 = v2 + wideZ2
        wideZ2 = l2 + v2
        val vc = (s - crossZ) * crossG
        val lc = vc + crossZ
        crossZ = lc + vc
        val sideOut = side * s + extra * (h1 - l2) - cross * lc
        var outL = (m + sideOut) * left
        var outR = (m - sideOut) * right

        val p = pos
        ringLeft[p] = outL
        ringRight[p] = outR
        if (written < size) written++
        if (delayLeft != target.delayLeft) delayLeft = glide(delayLeft, target.delayLeft)
        if (delayRight != target.delayRight) delayRight = glide(delayRight, target.delayRight)
        if (delayLeft > 0.0) outL = tap(ringLeft, p, delayLeft)
        if (delayRight > 0.0) outR = tap(ringRight, p, delayRight)
        pos = (p + 1) and mask
        frame[0] = outL
        frame[1] = outR
    }

    /** Convenience for whole interleaved stereo blocks (tests, offline use). */
    fun process(interleaved: DoubleArray, frames: Int) {
        beginBlock()
        if (!running) return
        val frame = DoubleArray(2)
        for (n in 0 until frames) {
            frame[0] = interleaved[2 * n]; frame[1] = interleaved[2 * n + 1]
            processFrame(frame)
            interleaved[2 * n] = frame[0]; interleaved[2 * n + 1] = frame[1]
        }
    }

    private fun step() {
        val t = target
        side += (t.side - side) * ramp
        extra += (t.extra - extra) * ramp
        cross += (t.cross - cross) * ramp
        left += (t.left - left) * ramp
        right += (t.right - right) * ramp
        if (++settleCounter < SETTLE_CHECK_FRAMES) return
        settleCounter = 0
        if (abs(t.side - side) < SETTLED && abs(t.extra - extra) < SETTLED && abs(t.cross - cross) < SETTLED &&
            abs(t.left - left) < SETTLED && abs(t.right - right) < SETTLED &&
            delayLeft == t.delayLeft && delayRight == t.delayRight
        ) {
            side = t.side; extra = t.extra; cross = t.cross; left = t.left; right = t.right
            ramping = false
            if (t.neutral) { running = false; wideZ = 0.0; wideZ2 = 0.0; crossZ = 0.0 }
        }
    }

    /** Moves a delay toward its target at [DELAY_GLIDE] samples per sample; growth only into valid history. */
    private fun glide(current: Double, goal: Double): Double =
        if (goal > current) min(min(current + DELAY_GLIDE, goal), max(0.0, written - 3.0)).coerceAtLeast(current)
        else max(current - DELAY_GLIDE, goal)

    /** Fractional read [d] samples back from [p]: linear below one sample, 4-point Lagrange above. */
    private fun tap(ring: DoubleArray, p: Int, d: Double): Double {
        val i = d.toInt()
        val f = d - i
        if (i == 0) {
            val x0 = ring[p]
            return x0 + f * (ring[(p - 1) and mask] - x0)
        }
        val x0 = ring[(p - i) and mask]
        if (f == 0.0) return x0
        val xm = ring[(p - i + 1) and mask]
        val x1 = ring[(p - i - 1) and mask]
        val x2 = ring[(p - i - 2) and mask]
        val fm1 = f - 1.0
        val fm2 = f - 2.0
        val fp1 = f + 1.0
        return (-f * fm1 * fm2 * xm + fp1 * f * fm1 * x2) * (1.0 / 6.0) + (fp1 * fm1 * fm2 * x0 - fp1 * f * fm2 * x1) * 0.5
    }

    private fun tptGain(hz: Double): Double {
        val g = tan(PI * min(hz, sampleRate * 0.45) / sampleRate)
        return g / (1.0 + g)
    }

    /** Current delay applied to each channel in samples (for diagnostics/tests). */
    fun delaySamples(): DoubleArray = doubleArrayOf(delayLeft, delayRight)

    companion object {
        const val MAX_DELAY_TENTHS = 60
        private const val RAMP_SECONDS = 0.020
        private const val SETTLED = 1e-7
        private const val SETTLE_CHECK_FRAMES = 64
        private const val DELAY_GLIDE = 0.01
        private const val WIDEN_HP_HZ = 200.0
        private const val CROSSFEED_LP_HZ = 700.0
        /** Per-side bleed coefficient at crossfeed 100: a/(1−a) = −7.4 dB at low frequencies. */
        private const val CROSSFEED_MAX = 0.3
        private val NEUTRAL = Targets(1.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0)

        fun maxDelaySamples(sampleRate: Int): Double = MAX_DELAY_TENTHS * sampleRate / 10_000.0

        /** Constant-power balance gains (left, right), exactly 1/1 at the centre and 0 on the muted side at ±100. */
        fun panGains(balance: Int): Pair<Double, Double> {
            val b = balance.coerceIn(-100, 100)
            if (b == 0) return 1.0 to 1.0
            if (b == -100) return sqrt(2.0) to 0.0
            if (b == 100) return 0.0 to sqrt(2.0)
            val theta = (b / 100.0 + 1.0) * PI / 4.0
            return sqrt(2.0) * cos(theta) to sqrt(2.0) * sin(theta)
        }

        /**
         * Worst-case peak growth of the stage in dB, for headroom: a hard-panned source gains
         * (1+w)/2 when widened to w > 1, and balance lifts the louder side by up to +3 dB.
         */
        fun boostDb(width: Int, balance: Int): Double {
            val w = width.coerceIn(0, 200) / 100.0
            val (gl, gr) = panGains(balance)
            val peak = (1.0 + max(w, 1.0)) / 2.0 * max(gl, gr)
            return if (peak <= 1.0) 0.0 else 20.0 * log10(peak)
        }
    }
}
