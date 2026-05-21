package org.linphone.incomingcall.hiva

/**
 * hivaex.ir mazaneh-bars uses duration strings (e.g. [60s]) — not minute integers ([1]).
 * Web chart example: resolution=60s for 1m candles.
 */
object MazanehBarsResolution {

    fun toApi(tfMinutes: String): String {
        val raw = tfMinutes.trim().lowercase()
        if (raw.endsWith("s") || raw.endsWith("m") || raw.endsWith("h")) return raw
        val minutes = raw.toIntOrNull() ?: return "60s"
        return "${minutes * 60}s"
    }

    /** Expected closed 1m bars for one Tehran calendar day. */
    fun expectedBarsPerDay(tfMinutes: String): Int? = when (tfMinutes.trim()) {
        "1" -> 1440
        "5" -> 288
        "15" -> 96
        "30" -> 48
        "60" -> 24
        else -> null
    }
}
