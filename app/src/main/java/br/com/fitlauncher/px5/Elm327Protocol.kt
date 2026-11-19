package br.com.fitlauncher.px5

import java.util.Locale

/**
 * Small, independent reader for the public ELM327 / OBD-II Mode 01 protocol.
 * No proprietary Car Scanner code or artwork is used.
 * All functions are Android-independent so parsing can be tested on a desktop JVM.
 */
object Elm327Protocol {
    private val noResponseMarkers = listOf(
        "NO DATA", "UNABLE TO CONNECT", "BUS ERROR", "CAN ERROR", "STOPPED", "ERROR"
    )

    /** Returns the first complete 0x41 PID response, or null for missing/broken data. */
    fun mode01Bytes(raw: String?, pid: Int, count: Int): List<Int>? {
        if (raw.isNullOrBlank() || pid !in 0..255 || count !in 1..32) return null
        val marker = "41" + pid.toString(16).uppercase(Locale.ROOT).padStart(2, '0')
        for (line in raw.uppercase(Locale.ROOT).replace('>', '\n').split('\r', '\n')) {
            if (noResponseMarkers.any { line.contains(it) }) continue
            // Handles ELM replies both with and without whitespace and CAN headers:
            // 41 0C 1A F8, 410C1AF8, or 7E8 04 41 0C 1A F8
            val hex = line.filter { it in '0'..'9' || it in 'A'..'F' }
            val index = hex.indexOf(marker)
            if (index < 0 || hex.length < index + marker.length + count * 2) continue
            val bytes = ArrayList<Int>(count)
            for (n in 0 until count) {
                val start = index + marker.length + n * 2
                val parsed = hex.substring(start, start + 2).toIntOrNull(16) ?: break
                bytes.add(parsed)
            }
            if (bytes.size == count) return bytes
        }
        return null
    }

    /** OBD-II PID bitmap for a range starting at base (00, 20, 40, ...). */
    fun supportedPids(raw: String?, base: Int): Set<Int>? {
        val bytes = mode01Bytes(raw, base, 4) ?: return null
        val result = mutableSetOf<Int>()
        for (i in 0 until 32) {
            val octet = bytes[i / 8]
            val bit = 7 - i % 8
            if ((octet shr bit) and 1 == 1) result.add(base + i + 1)
        }
        return result
    }

    fun rpm(raw: String?): Int? = mode01Bytes(raw, 0x0C, 2)
        ?.let { (it[0] * 256 + it[1]) / 4 }

    fun coolantC(raw: String?): Int? = mode01Bytes(raw, 0x05, 1)?.let { it[0] - 40 }

    fun vehicleKmh(raw: String?): Int? = mode01Bytes(raw, 0x0D, 1)?.firstOrNull()

    fun engineLoadPercent(raw: String?): Int? = mode01Bytes(raw, 0x04, 1)
        ?.let { (100.0 * it[0] / 255.0).toInt() }

    /** Voltage reported by ECU using the standard OBD PID 01 42, if supported. */
    fun ecuVoltage(raw: String?): Double? = mode01Bytes(raw, 0x42, 2)
        ?.let { (it[0] * 256 + it[1]) / 1000.0 }

    /** ATRV is the ELM adapter's voltage measurement, not necessarily ECU's PID 0142. */
    fun adapterVoltage(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val v = Regex("(\\d{1,2}(?:[.,]\\d{1,2})?)\\s*V", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
        return v?.takeIf { it in 0.0..40.0 }
    }
}
