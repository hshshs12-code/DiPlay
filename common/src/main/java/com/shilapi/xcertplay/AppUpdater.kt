// SPDX-License-Identifier: GPL-3.0-only
// In-app updater for the legacy fork: checks the fork's GitHub releases, downloads the APK asset
// and hands it to PackageInstaller (system confirmation dialog) or the system installer UI.
package com.shilapi.xcertplay

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {
    const val REPO = "hshshs12-code/DiPlay"
    private const val PREFS = "diplay_updater"
    private const val MANUAL_APK_KEY = "manual_install_apk"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 30_000

    class Release(val tag: String, val version: String, val apkUrl: String, val apkName: String, val sizeBytes: Long, val notes: String)

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** GitHub's latest non-prerelease, non-draft release with its first .apk asset. */
    fun latestRelease(): Release {
        val connection = open("https://api.github.com/repos/$REPO/releases/latest")
        try {
            val code = connection.responseCode
            if (code == 404) error("No release published yet")
            if (code != 200) error("GitHub answered HTTP $code")
            val json = JSONObject(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            val tag = json.getString("tag_name")
            val assets = json.optJSONArray("assets")
            var apk: JSONObject? = null
            if (assets != null) for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                if (asset.getString("name").endsWith(".apk", ignoreCase = true)) { apk = asset; break }
            }
            val chosen = apk ?: error("Release $tag has no .apk attached")
            return Release(
                tag = tag,
                version = tag.removePrefix("v").removePrefix("V"),
                apkUrl = chosen.getString("browser_download_url"),
                apkName = chosen.getString("name"),
                sizeBytes = chosen.optLong("size"),
                notes = json.optString("body", ""),
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Compares every number in the version strings in order: 0.2.13-legacy24.2 > 0.2.13-legacy24. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = numbers(candidate); val b = numbers(current)
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { 0 }; val y = b.getOrElse(index) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(text: String): List<Int> =
        Regex("\\d+").findAll(text).map { it.value.toIntOrNull() ?: 0 }.toList()

    private fun updateDirectory(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "updates").apply { mkdirs() }

    fun downloadApk(context: Context, release: Release, onProgress: (Long, Long) -> Unit): File {
        val directory = updateDirectory(context)
        val target = File(directory, "DiPlay-${release.version}.apk")
        val temporary = File(directory, target.name + ".part")
        val connection = open(release.apkUrl)
        try {
            val code = connection.responseCode
            if (code != 200) error("Download answered HTTP $code")
            val total = if (connection.contentLengthLong > 0) connection.contentLengthLong else release.sizeBytes
            temporary.outputStream().use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) error("Could not save the download")
            return target
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    /** Commits a PackageInstaller session; Android shows its own confirmation for non-system apps. */
    fun installApk(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("DiPlayUpdate.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                session.fsync(output)
            }
            val callback = Intent(context, UpdateInstallReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val sender = PendingIntent.getBroadcast(context, sessionId, callback, flags)
            session.commit(sender.intentSender)
        } catch (failure: Exception) {
            runCatching { session.abandon() }
            throw failure
        } finally {
            session.close()
        }
    }

    fun rememberManualApk(context: Context, apk: File) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(MANUAL_APK_KEY, apk.absolutePath).apply()
    }

    fun pendingManualApk(context: Context): File? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MANUAL_APK_KEY, null)
            ?.let(::File)?.takeIf { it.exists() }

    fun clearManualApk(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(MANUAL_APK_KEY).apply()
    }

    /** Opens the APK in the system installer UI through the FileProvider. */
    fun installManually(context: Context, apk: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }
        rememberManualApk(context, apk)
        context.startActivity(intent)
    }

    fun pendingApk(context: Context): File? =
        updateDirectory(context).listFiles()?.filter { it.name.endsWith(".apk") }?.maxByOrNull { it.lastModified() }

    fun cleanup(context: Context) {
        updateDirectory(context).listFiles()?.forEach { it.delete() }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.setRequestProperty("User-Agent", "DiPlay-Legacy-Updater")
        connection.setRequestProperty("Accept", "application/vnd.github+json, */*")
        return connection
    }
}
