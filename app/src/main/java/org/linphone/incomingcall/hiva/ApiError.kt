package org.linphone.incomingcall.hiva

import com.google.gson.JsonParser

fun parseApiDetailMessage(json: String?): String {
    if (json.isNullOrBlank()) return ""
    return try {
        val el = JsonParser.parseString(json)
        if (!el.isJsonObject) return json
        val o = el.asJsonObject
        when {
            o.has("detail") -> {
                val d = o.get("detail")
                when {
                    d.isJsonArray -> d.asJsonArray.joinToString("\n") { it.asString }
                    d.isJsonPrimitive -> d.asString
                    else -> json
                }
            }
            else -> json
        }
    } catch (_: Exception) {
        json
    }
}
