package com.shilapi.xcertplay.media

import android.os.Process

/**
 * Real-time-ish priorities for the streaming threads. Without them every CarPlay thread competes
 * at default priority with the UI and the head unit's background apps; on a slow quad-A7 that is
 * audible as underruns during the first minute while everything else is still starting.
 */
object ThreadPriorities {
    fun audio() = set(Process.THREAD_PRIORITY_URGENT_AUDIO)
    fun video() = set(Process.THREAD_PRIORITY_DISPLAY)
    fun transport() = set(Process.THREAD_PRIORITY_FOREGROUND - 2)
    fun background() = set(Process.THREAD_PRIORITY_BACKGROUND)

    private fun set(priority: Int) {
        try { Process.setThreadPriority(priority) } catch (_: Throwable) { /* not permitted here; keep default */ }
    }
}
