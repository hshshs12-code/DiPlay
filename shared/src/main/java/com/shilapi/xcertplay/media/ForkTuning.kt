package com.shilapi.xcertplay.media

/**
 * Legacy fork: runtime switches read by the stream code, set from the saved settings when a session
 * starts. Everything defaults to the upstream behaviour so unit tests and old call sites are unaffected.
 */
object ForkTuning {
    /** TCP options on the video socket: no delay, keep-alive, low-delay TOS, bounded receive buffer. */
    @Volatile var videoSocketOptions: Boolean = false
    /** When the decode queue is full, wait up to [FEED_WAIT_MILLIS] for space before discarding. */
    @Volatile var feedWait: Boolean = false
    /** Trace lines per session for "wired link" byte-level logs before throttling kicks in. */
    @Volatile var traceBudget: Int = Int.MAX_VALUE

    const val FEED_WAIT_MILLIS = 1_000L
    const val FEED_WAIT_SLICE_MILLIS = 25L
}
