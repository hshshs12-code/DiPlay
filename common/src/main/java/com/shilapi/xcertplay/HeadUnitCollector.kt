// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: collects everything about the head unit's audio and volume path into one zip.
package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pulls the head unit's audio and volume path apart for offline analysis: configuration files,
 * the relevant apps and native libraries, what Android's audio service reports, and, when the
 * unit's own ADB listens on localhost, the privileged view too (audio flinger state, every
 * app's logcat, the mixer controls, a root probe). Guided captures tag the same dumps while a
 * specific thing is happening (Android Auto playing, DiPlay playing, a knob sweep). Everything
 * lands in one directory that [zip] packs for the USB stick.
 */
class HeadUnitCollector(context: Context, private val report: () -> String) {
    private val app = context.applicationContext
    val stamp: String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val dir: File = File(app.filesDir, "collect/$stamp").apply { mkdirs() }
    private val log = StringBuilder()
    @Volatile private var adb: LocalAdb? = null
    @Volatile var adbAccess: LocalAdb.Access? = null; private set
    private var apkBytes = 0L

    fun note(line: String) { synchronized(log) { log.append(line).append('\n') } }
    fun notes(): String = synchronized(log) { log.toString() }

    // ---- level 1: what any app can read ------------------------------------------------------

    fun collectBasic(progress: (String) -> Unit) {
        progress("Identity and properties")
        write("meta.txt", buildString {
            appendLine("DiPlay ${runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()} collector $stamp")
            appendLine("manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} board=${Build.BOARD} hardware=${Build.HARDWARE} device=${Build.DEVICE} product=${Build.PRODUCT}")
            appendLine("display=${Build.DISPLAY} fingerprint=${Build.FINGERPRINT} release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        })
        write("getprop.txt", run("getprop"))
        write("diplay-report.txt", runCatching { report() }.getOrElse { "report failed: $it" })

        progress("Android audio service")
        write("audio-manager.txt", audioManagerReport())

        progress("Configuration files")
        copyConfigFiles()

        progress("Packages and apps")
        copyPackages(progress)

        progress("Native libraries")
        copyLibraries()

        progress("Kernel and device nodes")
        write("proc.txt", buildString {
            for (p in listOf("/proc/cpuinfo", "/proc/version", "/proc/devices", "/proc/asound/cards", "/proc/asound/pcm", "/proc/asound/modules", "/proc/mounts"))
                appendLine("=== $p").appendLine(runCatching { File(p).readText() }.getOrElse { "unreadable: ${it.javaClass.simpleName}" })
            appendLine("=== /proc/asound tree"); appendLine(tree(File("/proc/asound"), 3))
            appendLine("=== /dev"); appendLine(listNames(File("/dev")))
            appendLine("=== /dev/snd"); appendLine(listNames(File("/dev/snd")))
            appendLine("=== /sys/class/tty"); appendLine(listNames(File("/sys/class/tty")))
            appendLine("=== /sys/class/sound"); appendLine(listNames(File("/sys/class/sound")))
        })
    }

    private fun audioManagerReport(): String = buildString {
        val manager = app.getSystemService(AudioManager::class.java) ?: return "AudioManager unavailable"
        appendLine("volumeFixed=${manager.isVolumeFixed} mode=${manager.mode} musicActive=${manager.isMusicActive}")
        appendLine("PROPERTY_OUTPUT_SAMPLE_RATE=${manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)} PROPERTY_OUTPUT_FRAMES_PER_BUFFER=${manager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)}")
        for ((name, stream) in listOf("MUSIC" to AudioManager.STREAM_MUSIC, "VOICE_CALL" to AudioManager.STREAM_VOICE_CALL, "SYSTEM" to AudioManager.STREAM_SYSTEM,
            "RING" to AudioManager.STREAM_RING, "ALARM" to AudioManager.STREAM_ALARM, "NOTIFICATION" to AudioManager.STREAM_NOTIFICATION, "DTMF" to AudioManager.STREAM_DTMF, "ACCESSIBILITY" to 10)) {
            appendLine("stream $name($stream): volume=${runCatching { manager.getStreamVolume(stream) }.getOrNull()} max=${runCatching { manager.getStreamMaxVolume(stream) }.getOrNull()}")
        }
        for (stream in 11..20) runCatching { "stream $stream: volume=${manager.getStreamVolume(stream)} max=${manager.getStreamMaxVolume(stream)}" }.onSuccess { appendLine(it) }
        appendLine("--- vendor parameters")
        for (key in listOf("GetBesLoudnessStatus", "GetBesSurroundStatus", "GetMusicPlusStatus", "GetBesLoudnessEnable", "GetAudioParam", "audio_param", "AudioEnhance", "MusicPlus", "BesLoudness", "BesSurround", "volume", "audioVolume", "GetVolume", "GetMediaVolume", "mcu", "amp", "ampGain", "GetAmpStatus", "hw_volume", "loudness", "GetAudioHalVersion", "GetAudioFeatureOption", "ro.audio.flinger_standbytime_ms"))
            appendLine("$key -> ${runCatching { manager.getParameters(key) }.getOrElse { "error ${it.javaClass.simpleName}" }}")
        appendLine("--- output devices")
        runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { d -> appendLine("type=${d.type} name=${d.productName} id=${d.id} rates=${d.sampleRates.joinToString("/")} channels=${d.channelCounts.joinToString("/")} encodings=${d.encodings.joinToString("/")}") } }
        appendLine("--- effects")
        runCatching { AudioEffect.queryEffects()?.forEach { appendLine("${it.name} by ${it.implementor} type=${it.type} uuid=${it.uuid} connect=${it.connectMode}") } }
    }

    private val configName = Regex("(?i)(audio|volume|mixer|bes|loudness|ivi|mcu|jancar|car|amp|sound|eq|dsp|tuning|\\.ini$|\\.xml$|\\.conf$|\\.cfg$|\\.txt$)")
    private val configRoots = listOf("/system/etc", "/vendor/etc", "/system/vendor/etc", "/etc", "/odm/etc", "/custom", "/system/etc/ivi", "/vendor/etc/ivi",
        "/system/etc/audio_param", "/vendor/etc/audio_param", "/data/local/tmp", "/sdcard", "/storage/emulated/0", "/mnt/vendor", "/system/usr", "/system/etc/permissions")

    private fun copyConfigFiles() {
        val out = File(dir, "config-files").apply { mkdirs() }
        val index = StringBuilder()
        for (root in configRoots) {
            val base = File(root)
            if (!base.isDirectory) continue
            walk(base, if (root == "/sdcard" || root == "/storage/emulated/0") 1 else 3) { file ->
                if (!file.isFile || file.length() > 5_000_000L || !configName.containsMatchIn(file.name)) return@walk
                val target = File(out, file.absolutePath.trimStart('/').replace('/', '~'))
                val copied = runCatching { file.copyTo(target, overwrite = true); true }.getOrDefault(false)
                index.appendLine("${if (copied) "copied" else "unreadable"} ${file.absolutePath} ${file.length()}")
            }
        }
        write("config-files-index.txt", index.toString())
    }

    private val appKeywords = Regex("(?i)(mcu|ivi|jancar|factory|engineer|radio|fm\\b|bluetooth|bt|music|audio|sound|eq|equalizer|amp|canbus|carplay|zlink|autokit|carbit|carlink|gearhead|projection|hicar|easyconn|phonelink|mirror|android\\.auto|settings|launcher|car)")
    private val appSkip = Regex("(?i)(com\\.google\\.android\\.(gms|gsf|webview|tts|inputmethod)|com\\.android\\.chrome|org\\.chromium|com\\.shihab)")

    private fun copyPackages(progress: (String) -> Unit) {
        val pm = app.packageManager
        val all = runCatching { pm.getInstalledPackages(PackageManager.GET_META_DATA) }.getOrDefault(emptyList())
        val list = StringBuilder()
        val apkDir = File(dir, "apks").apply { mkdirs() }
        for (pkg in all) {
            val info = pkg.applicationInfo ?: continue
            val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault("?")
            val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            list.appendLine("${pkg.packageName}\t$label\t${pkg.versionName}\tsystem=$system\t${info.sourceDir}\tlibs=${info.nativeLibraryDir}")
            val wanted = appKeywords.containsMatchIn(pkg.packageName) || appKeywords.containsMatchIn(label)
            if (!wanted || appSkip.containsMatchIn(pkg.packageName)) continue
            val apk = File(info.sourceDir)
            if (apk.isFile && apk.length() <= 150_000_000L && apkBytes + apk.length() <= 600_000_000L) {
                progress("Copying ${pkg.packageName}")
                val ok = runCatching { apk.copyTo(File(apkDir, "${pkg.packageName}.apk"), overwrite = true); true }.getOrDefault(false)
                if (ok) apkBytes += apk.length()
                list.appendLine("  apk ${if (ok) "copied" else "unreadable"} ${apk.length()}")
            }
            val libs = info.nativeLibraryDir?.let(::File)
            if (libs != null && libs.isDirectory) {
                val target = File(dir, "apk-libs/${pkg.packageName}").apply { mkdirs() }
                libs.listFiles()?.filter { it.isFile }?.forEach { lib -> runCatching { lib.copyTo(File(target, lib.name), overwrite = true) } }
            }
        }
        write("packages.txt", list.toString())
    }

    private val libName = Regex("(?i)(audio|bes|mcu|ivi|jancar|serial|tinyalsa|alsa|hal|amp|car|sound|dsp|effect|mtk)")

    private fun copyLibraries() {
        val index = StringBuilder()
        val out = File(dir, "libs").apply { mkdirs() }
        var bytes = 0L
        for (root in listOf("/system/lib", "/system/lib/hw", "/system/lib/soundfx", "/vendor/lib", "/vendor/lib/hw", "/vendor/lib/soundfx", "/system/lib64", "/vendor/lib64", "/system/bin", "/vendor/bin")) {
            val base = File(root)
            if (!base.isDirectory) continue
            base.listFiles()?.sortedBy { it.name }?.forEach { file ->
                if (!file.isFile) return@forEach
                index.appendLine("$root/${file.name} ${file.length()}")
                if (libName.containsMatchIn(file.name) && file.length() <= 40_000_000L && bytes + file.length() <= 300_000_000L) {
                    val target = File(out, "${root.trimStart('/').replace('/', '~')}~${file.name}")
                    if (runCatching { file.copyTo(target, overwrite = true); true }.getOrDefault(false)) bytes += file.length()
                }
            }
        }
        write("libs-index.txt", index.toString())
    }

    // ---- level 2: the unit's own ADB -----------------------------------------------------------

    /** Opens the local ADB link once; the first use asks the driver to approve the key. */
    fun openAdb(progress: (String) -> Unit): LocalAdb.Access {
        val link = LocalAdb(AdbKeys.load(app))
        progress("Connecting to the head unit's ADB (approve the debugging prompt if it appears)")
        val access = link.connect(mayAsk = true)
        adbAccess = access
        if (access == LocalAdb.Access.READY) adb = link else runCatching { link.close() }
        note("adb access=$access")
        return access
    }

    private val adbCommands = listOf(
        "root-probe" to "id; echo debuggable=\$(getprop ro.debuggable) secure=\$(getprop ro.secure) type=\$(getprop ro.build.type) adbsecure=\$(getprop ro.adb.secure); ls -l /system/xbin/su /system/bin/su /sbin/su /su /sbin/magisk /data/adb /system/xbin/daemonsu 2>&1; which su 2>&1; (su -c id) 2>&1; (su 0 id) 2>&1",
        "getprop" to "getprop",
        "packages-f" to "pm list packages -f",
        "services" to "service list",
        "dumpsys-audio" to "dumpsys audio",
        "dumpsys-media.audio_flinger" to "dumpsys media.audio_flinger",
        "dumpsys-media.audio_policy" to "dumpsys media.audio_policy",
        "dumpsys-media.player" to "dumpsys media.player 2>&1 | head -400",
        "dumpsys-bluetooth" to "dumpsys bluetooth_manager 2>&1 | head -600",
        "dumpsys-wifip2p" to "dumpsys wifip2p 2>&1 | head -300",
        "dumpsys-activity-services" to "dumpsys activity services 2>&1 | head -800",
        "tinymix" to "ls -la /system/bin /vendor/bin 2>/dev/null | grep -i -E 'tiny|alsa|mtk|audio|mcu|ivi'; echo ===; tinymix 2>&1; echo ===; tinymix -D 0 2>&1 | head -400",
        "asound" to "cat /proc/asound/cards; ls -R /proc/asound 2>&1; for f in /proc/asound/card*/pcm*/info /proc/asound/card*/pcm*/sub*/hw_params /proc/asound/card*/pcm*/sub*/status; do echo \"=== \$f\"; cat \$f 2>&1; done",
        "dev" to "ls -la /dev 2>&1 | grep -i -E 'tty|i2c|mcu|audio|snd|spi|uart|serial'; echo ===; ls -la /dev/snd 2>&1; echo ===; ls -la /sys/class/tty 2>&1; echo ===; ls -la /sys/bus/i2c/devices 2>&1; for d in /sys/bus/i2c/devices/*; do echo \"\$d: \$(cat \$d/name 2>/dev/null)\"; done",
        "ini-find" to "find /system /vendor /data/local /sdcard /storage /custom /odm /mnt /oem /product -maxdepth 6 \\( -iname '*ivi*' -o -iname '*.ini' -o -iname '*mcu*' -o -iname '*jancar*' -o -iname '*volume*' -o -iname '*audio_param*' \\) 2>/dev/null; echo ===; for f in \$(find /system /vendor /data/local /sdcard /storage /custom /odm /mnt /oem -maxdepth 6 -iname '*.ini' -size -300k 2>/dev/null); do echo \"=== \$f\"; cat \"\$f\" 2>&1; done",
        "mounts" to "mount 2>&1; echo ===; df 2>&1",
        "logcat-full" to "logcat -d -v threadtime -t 8000 2>&1",
        "logcat-events" to "logcat -d -b events -v threadtime -t 2000 2>&1",
        "logcat-audio-filtered" to "logcat -d -v threadtime -t 20000 2>&1 | grep -i -E 'audio|volume|bes|mcu|amp|ivi|jancar|hal|alsa|tiny|loud' | tail -3000",
    )

    fun collectAdb(progress: (String) -> Unit) {
        val link = adb ?: return
        val out = File(dir, "adb").apply { mkdirs() }
        for ((name, command) in adbCommands) {
            progress("ADB: $name")
            val text = runCatching { link.shell("( $command ) 2>&1; echo \"[exit=\$?]\"", 90_000) }.getOrNull() ?: "no reply"
            File(out, "$name.txt").writeText("$ $command\n$text", Charsets.UTF_8)
        }
    }

    /** A tagged snapshot of the audio state right now (what is playing, how, through what). */
    fun capture(label: String, progress: (String) -> Unit) {
        val out = File(dir, "captures").apply { mkdirs() }
        val safe = label.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
        val when_ = SimpleDateFormat("HH-mm-ss", Locale.US).format(Date())
        File(out, "$safe-$when_-audio-manager.txt").writeText(audioManagerReport(), Charsets.UTF_8)
        val link = adb
        if (link != null) {
            for ((name, command) in listOf(
                "audio_flinger" to "dumpsys media.audio_flinger",
                "audio" to "dumpsys audio",
                "audio_policy" to "dumpsys media.audio_policy",
                "tinymix" to "tinymix 2>&1",
                "logcat-tail" to "logcat -d -v threadtime -t 600 2>&1",
                "top" to "top -n 1 2>&1 | head -40",
            )) {
                progress("Capture $label: $name")
                val text = runCatching { link.shell("( $command ) 2>&1", 60_000) }.getOrNull() ?: "no reply"
                File(out, "$safe-$when_-$name.txt").writeText(text, Charsets.UTF_8)
            }
        }
        note("capture '$label' at $when_ adb=${link != null}")
    }

    /** Clears logcat so the sweep that follows is the only thing in it. */
    fun knobSweepStart() {
        adb?.let { runCatching { it.shell("logcat -c", 10_000) } }
        note("knob sweep started ${SimpleDateFormat("HH-mm-ss", Locale.US).format(Date())} volume=${app.getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_MUSIC)}")
    }

    fun knobSweepStop(progress: (String) -> Unit) {
        note("knob sweep stopped ${SimpleDateFormat("HH-mm-ss", Locale.US).format(Date())} volume=${app.getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_MUSIC)}")
        val out = File(dir, "captures").apply { mkdirs() }
        val link = adb ?: return
        progress("Knob sweep: logcat")
        File(out, "knob-sweep-logcat.txt").writeText(runCatching { link.shell("logcat -d -v threadtime 2>&1", 90_000) }.getOrNull() ?: "no reply", Charsets.UTF_8)
        File(out, "knob-sweep-dumpsys-audio.txt").writeText(runCatching { link.shell("dumpsys audio 2>&1", 60_000) }.getOrNull() ?: "no reply", Charsets.UTF_8)
        File(out, "knob-sweep-tinymix.txt").writeText(runCatching { link.shell("tinymix 2>&1", 30_000) }.getOrNull() ?: "no reply", Charsets.UTF_8)
    }

    // ---- packaging -----------------------------------------------------------------------------

    fun finish(): File {
        write("collector-notes.txt", notes())
        runCatching { adb?.close() }; adb = null
        val zip = File(app.filesDir, "collect/headunit-$stamp.zip")
        ZipOutputStream(FileOutputStream(zip).buffered()).use { out -> addDir(out, dir, dir.name) }
        dir.deleteRecursively()
        return zip
    }

    fun close() { runCatching { adb?.close() }; adb = null }

    private fun addDir(out: ZipOutputStream, directory: File, prefix: String) {
        directory.listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (file.isDirectory) addDir(out, file, "$prefix/${file.name}")
            else {
                out.putNextEntry(ZipEntry("$prefix/${file.name}"))
                FileInputStream(file).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun write(name: String, text: String) = File(dir, name).writeText(text, Charsets.UTF_8)

    private fun run(vararg command: String): String = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text
    }.getOrElse { "failed: ${it.javaClass.simpleName}: ${it.message}" }

    private fun walk(base: File, depth: Int, visit: (File) -> Unit) {
        val children = runCatching { base.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (child.isDirectory) { if (depth > 0) walk(child, depth - 1, visit) } else visit(child)
        }
    }

    private fun tree(base: File, depth: Int, indent: String = ""): String = buildString {
        val children = runCatching { base.listFiles() }.getOrNull() ?: return "$indent(unreadable)\n"
        for (child in children.sortedBy { it.name }) {
            appendLine("$indent${child.name}${if (child.isDirectory) "/" else " ${child.length()}"}")
            if (child.isDirectory && depth > 0) append(tree(child, depth - 1, "$indent  "))
        }
    }

    private fun listNames(base: File): String = runCatching { base.listFiles()?.sortedBy { it.name }?.joinToString("\n") { it.name } ?: "(empty)" }.getOrElse { "unreadable" }

    companion object {
        fun copy(from: File, to: OutputStream) { FileInputStream(from).use { it.copyTo(to) } }
    }
}
