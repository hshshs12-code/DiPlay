// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: iAP2 over the iPhone's USB HID interface (the "iPod USB Interface" configuration).
package com.shilapi.xcertplay.ipod

import java.io.ByteArrayOutputStream

/**
 * The report sizes declared by a HID report descriptor, keyed by report ID. Sizes are the report
 * body in bytes, excluding the leading report-ID byte.
 */
class HidReportLayout(val inputs: Map<Int, Int>, val outputs: Map<Int, Int>) {
    val maxInputBytes: Int get() = inputs.values.maxOrNull() ?: 0

    fun describe(): String =
        "in=" + inputs.entries.sortedBy { it.key }.joinToString(",") { "0x${it.key.toString(16)}:${it.value}" } +
            " out=" + outputs.entries.sortedBy { it.key }.joinToString(",") { "0x${it.key.toString(16)}:${it.value}" }

    companion object {
        /**
         * Minimal HID report-descriptor walk: tracks Report ID / Report Size / Report Count global
         * items and sums the Input and Output main items per report ID. Feature reports are ignored.
         */
        fun parse(descriptor: ByteArray): HidReportLayout {
            val inputs = LinkedHashMap<Int, Int>()
            val outputs = LinkedHashMap<Int, Int>()
            var reportId = 0
            var reportSize = 0
            var reportCount = 0
            var offset = 0
            while (offset < descriptor.size) {
                val prefix = descriptor[offset].toInt() and 0xff
                offset++
                if (prefix == 0xfe) { // long item
                    if (offset >= descriptor.size) break
                    val dataSize = descriptor[offset].toInt() and 0xff
                    offset += 2 + dataSize
                    continue
                }
                val size = when (prefix and 0x03) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 4 }
                if (offset + size > descriptor.size) break
                var value = 0
                for (i in 0 until size) value = value or ((descriptor[offset + i].toInt() and 0xff) shl (8 * i))
                offset += size
                when (prefix and 0xfc) {
                    0x84 -> reportId = value
                    0x74 -> reportSize = value
                    0x94 -> reportCount = value
                    0x80 -> inputs[reportId] = (inputs[reportId] ?: 0) + reportSize * reportCount
                    0x90 -> outputs[reportId] = (outputs[reportId] ?: 0) + reportSize * reportCount
                }
            }
            fun bytes(bits: Map<Int, Int>) = bits.mapValues { (it.value + 7) / 8 }.filterValues { it > 0 }
            return HidReportLayout(bytes(inputs), bytes(outputs))
        }
    }
}

/**
 * Report framing for iAP2 over HID: every report is `reportId, linkControlByte, payload…` padded
 * with zeros to the declared report size. LCB bit 0 marks a continuation of the previous report
 * and bit 1 marks that more reports follow.
 */
object Iap2HidFraming {
    const val LCB_CONTINUATION = 0x01
    const val LCB_MORE_TO_FOLLOW = 0x02
    val MARKER = byteArrayOf(0xff.toByte(), 0x55, 0x02, 0x00, 0xee.toByte(), 0x10)

    /** Splits [data] into complete output reports (report-ID byte included). */
    fun fragment(data: ByteArray, outputs: Map<Int, Int>): List<ByteArray> {
        require(outputs.isNotEmpty()) { "The HID descriptor declares no output reports" }
        val sizes = outputs.entries.sortedBy { it.value }
        val largest = sizes.last()
        val reports = ArrayList<ByteArray>()
        var offset = 0
        var first = true
        do {
            val remaining = data.size - offset
            val chosen = sizes.firstOrNull { it.value - 1 >= remaining } ?: largest
            val capacity = chosen.value - 1
            val take = minOf(capacity, remaining)
            val more = offset + take < data.size
            val report = ByteArray(chosen.value + 1)
            report[0] = chosen.key.toByte()
            report[1] = ((if (first) 0 else LCB_CONTINUATION) or (if (more) LCB_MORE_TO_FOLLOW else 0)).toByte()
            System.arraycopy(data, offset, report, 2, take)
            reports += report
            offset += take
            first = false
        } while (offset < data.size)
        return reports
    }

    /** Joins input reports back into the byte stream the link layer expects. */
    class Reassembler {
        private val pending = ByteArrayOutputStream()

        /**
         * Accepts one input report (report-ID byte included). Returns the reassembled bytes when
         * the report completes a sequence, trimmed of report padding where the content is
         * recognisable (link packets by their header length, the detection marker by its size).
         */
        fun accept(report: ByteArray, length: Int = report.size): ByteArray? {
            if (length < 2) return null
            val lcb = report[1].toInt() and 0xff
            if (lcb and LCB_CONTINUATION == 0) pending.reset()
            pending.write(report, 2, length - 2)
            if (lcb and LCB_MORE_TO_FOLLOW != 0) return null
            val bytes = pending.toByteArray()
            pending.reset()
            return trim(bytes)
        }

        fun reset() = pending.reset()
    }

    /** Removes zero padding after whole link packets or the marker; unknown content passes through. */
    fun trim(bytes: ByteArray): ByteArray {
        var end = 0
        while (end < bytes.size) {
            val b0 = bytes[end].toInt() and 0xff
            if (b0 != 0xff || end + 1 >= bytes.size) break
            val b1 = bytes[end + 1].toInt() and 0xff
            if (b1 == 0x5a && end + 4 <= bytes.size) {
                val length = ((bytes[end + 2].toInt() and 0xff) shl 8) or (bytes[end + 3].toInt() and 0xff)
                if (length < 9 || end + length > bytes.size) break
                end += length
            } else if (b1 == 0x55 && end + MARKER.size <= bytes.size) {
                end += MARKER.size
            } else break
        }
        if (end == 0) return bytes
        if (end == bytes.size) return bytes
        // Anything after the parsed packets must be padding to be dropped.
        for (i in end until bytes.size) if (bytes[i].toInt() != 0) return bytes
        return bytes.copyOf(end)
    }
}
