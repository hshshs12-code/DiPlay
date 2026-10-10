package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(val nalus: ByteArray, val receivedNs: Long = System.nanoTime()) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    data object Resync : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's 250 ms age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val jobs = LinkedBlockingQueue<VideoJob>()

    fun offer(job: VideoJob) {
        if (job is VideoJob.Frame) {
            val oversized = job.nalus.size > maxBytes
            // Every frame of a UI stream is a reference frame; shedding one smears the picture
            // until the next keyframe. When tuned, the reader waits for space first, which closes
            // the TCP window so the phone throttles itself (Open Headunit measured two bursts of
            // ~50 shed frames on an MT6735 with the old immediate drop).
            val deadline = System.nanoTime() + ForkTuning.FEED_WAIT_MILLIS * 1_000_000
            while (true) {
                synchronized(this) {
                    if (!isFull(job)) {
                        if (!oversized) jobs.offer(job)
                        return
                    }
                    if (oversized || !ForkTuning.feedWait || System.nanoTime() >= deadline) {
                        discardFrames()
                        jobs.offer(VideoJob.Resync)
                        // A single oversized frame is also a lost reference chain: resync, then drop it.
                        if (!oversized) jobs.offer(job)
                        return
                    }
                }
                try { Thread.sleep(ForkTuning.FEED_WAIT_SLICE_MILLIS) } catch (_: InterruptedException) { return }
            }
        }
        synchronized(this) { jobs.offer(job) }
    }

    private fun isFull(job: VideoJob.Frame): Boolean {
        val frames = jobs.filterIsInstance<VideoJob.Frame>()
        return frames.size >= maxFrames || frames.sumOf { it.nalus.size.toLong() } + job.nalus.size > maxBytes
    }

    @Synchronized fun discardFrames() {
        jobs.removeIf { it is VideoJob.Frame || it is VideoJob.Resync }
    }

    fun poll(timeoutMillis: Long): VideoJob? = jobs.poll(timeoutMillis, TimeUnit.MILLISECONDS)
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
