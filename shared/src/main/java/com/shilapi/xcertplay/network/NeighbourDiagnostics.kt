package com.shilapi.xcertplay.network

import java.io.File

/**
 * Whether anything has associated with the hotspot interface: the kernel neighbour table lists
 * every station that exchanged ARP with us, which is the first thing a joining iPhone does when
 * it asks for an address. `complete` entries carry a hardware address; incomplete ones are our
 * own unanswered probes. Android 8 exposes /proc/net/arp to apps; `ip neigh` is the fallback.
 */
object NeighbourDiagnostics {
    fun snapshot(interfaceName: String?): String {
        if (interfaceName.isNullOrBlank()) return "neighbours=unknown source=no_interface"
        fromProc(interfaceName)?.let { return it }
        fromIpNeigh(interfaceName)?.let { return it }
        return "neighbours=unknown source=unavailable"
    }

    private fun fromProc(iface: String): String? = runCatching {
        val lines = File("/proc/net/arp").readLines().drop(1)
        var total = 0; var complete = 0
        for (line in lines) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 6 || parts[5] != iface) continue
            total++
            val flags = parts[2].removePrefix("0x").toIntOrNull(16) ?: 0
            if (flags and 0x2 != 0 && parts[3] != "00:00:00:00:00:00") complete++
        }
        "neighbours=$total complete=$complete source=arp"
    }.getOrNull()

    private fun fromIpNeigh(iface: String): String? = runCatching {
        val process = ProcessBuilder("ip", "neigh", "show", "dev", iface).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() != 0) return null
        val lines = text.lines().filter { it.isNotBlank() }
        val complete = lines.count { it.contains("lladdr") && (it.contains("REACHABLE") || it.contains("STALE") || it.contains("DELAY") || it.contains("PROBE")) }
        "neighbours=${lines.size} complete=$complete source=ip"
    }.getOrNull()
}
