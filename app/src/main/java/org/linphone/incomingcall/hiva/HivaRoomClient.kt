package org.linphone.incomingcall.hiva

import android.util.Log
import com.google.gson.JsonParser
import org.linphone.incomingcall.bot.local.LocalCandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

object HivaRoomClient {

    private const val TAG = "HIVA_BARS"

    suspend fun getBars(resolution: String, fromSec: Long, toSec: Long): List<LocalCandle> {
        return withContext(Dispatchers.IO) {
            val apiResolution = MazanehBarsResolution.toApi(resolution)
            val url =
                "${HivaGoldClient.BASE_URL}mazaneh/api/mazaneh-bars/?symbol=mazaneh&from=$fromSec&to=$toSec&resolution=$apiResolution"
            Log.i(
                TAG,
                "getBars tf=$resolution apiResolution=$apiResolution from=$fromSec to=$toSec spanSec=${toSec - fromSec}"
            )
            val req = Request.Builder().url(url).get().build()
            HivaGoldClient.okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getBars http=${resp.code} tf=$resolution apiResolution=$apiResolution")
                    return@use emptyList()
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    Log.w(TAG, "getBars empty body tf=$resolution")
                    return@use emptyList()
                }
                val root = JsonParser.parseString(body)
                val candles = mutableListOf<LocalCandle>()
                if (root.isJsonArray) {
                    val arr = root.asJsonArray
                    for (i in 0 until arr.size()) {
                        val o = arr[i].asJsonObject
                        val t = o.get("time")?.asLong ?: o.get("t")?.asLong ?: continue
                        val open = o.get("open")?.asDouble ?: o.get("o")?.asDouble ?: continue
                        val high = o.get("high")?.asDouble ?: o.get("h")?.asDouble ?: continue
                        val low = o.get("low")?.asDouble ?: o.get("l")?.asDouble ?: continue
                        val close = o.get("close")?.asDouble ?: o.get("c")?.asDouble ?: continue
                        candles += LocalCandle(t, open, high, low, close)
                    }
                } else if (root.isJsonObject) {
                    val obj = root.asJsonObject
                    val t = obj.getAsJsonArray("t") ?: return@use emptyList()
                    val o = obj.getAsJsonArray("o") ?: return@use emptyList()
                    val h = obj.getAsJsonArray("h") ?: return@use emptyList()
                    val l = obj.getAsJsonArray("l") ?: return@use emptyList()
                    val c = obj.getAsJsonArray("c") ?: return@use emptyList()
                    val size = t.size()
                    for (i in 0 until size) {
                        candles += LocalCandle(
                            t[i].asLong,
                            o[i].asDouble,
                            h[i].asDouble,
                            l[i].asDouble,
                            c[i].asDouble
                        )
                    }
                }
                val sorted = candles.sortedBy { it.time }
                val clipped = sorted.filter { it.time in fromSec..toSec }
                val expected = MazanehBarsResolution.expectedBarsPerDay(resolution)
                if (expected != null && clipped.size > expected * 2) {
                    Log.w(
                        TAG,
                        "getBars suspicious count=${clipped.size} expected~$expected tf=$resolution apiResolution=$apiResolution — wrong resolution param or bad cache file; re-download day mock"
                    )
                } else {
                    Log.i(
                        TAG,
                        "getBars ok tf=$resolution apiResolution=$apiResolution raw=${sorted.size} clipped=${clipped.size} first=${clipped.firstOrNull()?.time} last=${clipped.lastOrNull()?.time}"
                    )
                }
                clipped
            }
        }
    }
}
