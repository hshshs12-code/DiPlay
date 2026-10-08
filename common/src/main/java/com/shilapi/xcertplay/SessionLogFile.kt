package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File

/** Bounded, private diagnostics. Each write is redacted before touching storage. */
internal class SessionLogFile(val file: File) : Closeable {
    private val lock = Any()
    private var closed = false
    // Legacy fork: one persistent buffered writer flushed per line, instead of a stat + open +
    // append + close for every line on whichever thread produced it (USB reader, audio).
    private var writer: java.io.Writer? = null
    private var bytesWritten = 0L

    fun reset(header: String) = synchronized(lock) {
        if (!closed) {
            file.parentFile?.mkdirs()
            closeWriter()
            rotate()
            file.writeText("")
            bytesWritten = 0
            append(header)
        }
    }

    fun append(line: String) = synchronized(lock) {
        if (closed) return@synchronized
        val safe = DiagnosticRedactor.redact(line) ?: return@synchronized
        runCatching {
            if (bytesWritten > MAX_BYTES) {
                closeWriter()
                rotate()
                file.writeText("")
                bytesWritten = 0
            }
            val out = writer ?: java.io.BufferedWriter(java.io.FileWriter(file, true), 16 * 1024).also {
                writer = it
                bytesWritten = file.length()
            }
            out.write(safe); out.write("\n"); out.flush()
            bytesWritten += safe.length + 1
        }
        Unit
    }

    /** Kept for callers that batch writes elsewhere; writes are already visible after [append]. */
    fun flushNow(@Suppress("UNUSED_PARAMETER") timeoutMillis: Long) = synchronized(lock) { runCatching { writer?.flush() }; Unit }

    private fun closeWriter() { runCatching { writer?.flush(); writer?.close() }; writer = null }

    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
            val source = File(file.parentFile, ARCHIVE_NAMES[index - 1])
            val destination = File(file.parentFile, ARCHIVE_NAMES[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, ARCHIVE_NAMES.first()), overwrite = true)
    }

    override fun close() = synchronized(lock) { closed = true; closeWriter() }

    companion object {
        /** Writes are visible after append; kept so report builders have one call to make. */
        fun flushAll(@Suppress("UNUSED_PARAMETER") timeoutMillis: Long) = Unit

        const val MAX_BYTES = 512 * 1024L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"
    }
}
