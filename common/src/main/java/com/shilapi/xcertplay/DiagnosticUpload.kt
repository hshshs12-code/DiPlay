// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: posts a diagnostic report to dpaste.com (unlisted, expires in 7 days) so the user
// can read a short link off the head unit instead of pulling a file from it.
package com.shilapi.xcertplay

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object DiagnosticUpload {
    private const val ENDPOINT = "https://dpaste.com/api/v2/"
    private const val PREFS = "diplay_diagnostic_upload"
    private const val KEY_LAST_URL = "last_url"
    private const val KEY_LAST_AT = "last_at"
    /** dpaste accepts more, but head-unit links are only useful if the upload finishes quickly. */
    private const val MAX_BYTES = 900 * 1024

    class Result(val url: String, val bytes: Int, val truncated: Boolean)

    fun upload(report: String, title: String): Result {
        var body = report
        var truncated = false
        val encoded = body.toByteArray(Charsets.UTF_8)
        if (encoded.size > MAX_BYTES) {
            // Keep the header and the newest log lines: the failure is at the end.
            val head = body.substring(0, minOf(body.length, 64 * 1024))
            val tail = body.substring(maxOf(0, body.length - (MAX_BYTES - head.length - 200)))
            body = head + "\n\n--- [report truncated for upload; ${encoded.size} bytes total] ---\n\n" + tail
            truncated = true
        }
        val form = "content=" + URLEncoder.encode(body, "UTF-8") +
            "&syntax=text&expiry_days=7&title=" + URLEncoder.encode(title, "UTF-8")
        val payload = form.toByteArray(Charsets.UTF_8)
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "DiPlay-Legacy-Diagnostics")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            val answer = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }?.trim() ?: ""
            if (code !in 200..299) error("dpaste answered HTTP $code ${answer.take(120)}")
            val url = answer.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("https://") }
                ?: error("dpaste returned no link: ${answer.take(120)}")
            return Result(url, payload.size, truncated)
        } finally {
            connection.disconnect()
        }
    }

    fun rememberUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_URL, url).putLong(KEY_LAST_AT, System.currentTimeMillis()).apply()
    }

    fun lastUrl(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_URL, null)

    /** The part the user reads out: dpaste.com/ABCDEF. */
    fun shortForm(url: String): String = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
}
