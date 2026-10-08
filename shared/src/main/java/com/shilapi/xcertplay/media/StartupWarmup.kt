package com.shilapi.xcertplay.media

import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayCrypto
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exercises the hot stream code paths once at app launch, in the background and before the iPhone
 * connects, so Android's JIT has compiled them by the time real frames arrive. On Android 8 the
 * ChaCha20-Poly1305 fallback is pure Java and runs interpreted for the first minute otherwise.
 */
object StartupWarmup {
    private val started = AtomicBoolean(false)

    fun run(dspEnabled: Boolean) {
        if (!started.compareAndSet(false, true)) return
        Thread({
            ThreadPriorities.background()
            val startedAt = System.nanoTime()
            val crypto = runCatching { AirPlayCrypto.warmUp(bytes = 3 * 1024 * 1024, chunk = 1400) }.getOrElse { "failed: ${it.message}" }
            var dsp = "skipped"
            if (dspEnabled) {
                runCatching {
                    val eq = EqualizerDsp(48000, 2)
                    eq.configure(FloatArray(EqualizerDsp.BANDS) { if (it % 2 == 0) 3f else -2f }, limiter = true)
                    val buffer = ByteArray(48000 * 4)
                    repeat(6) { eq.process(buffer, 0, buffer.size) }
                    dsp = "ok"
                }.onFailure { dsp = "failed: ${it.message}" }
            }
            Log.i("DiPlay-Warmup", "crypto=$crypto dsp=$dsp elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}")
        }, "diplay-warmup").apply { isDaemon = true }.start()
    }
}
