package org.linphone.incomingcall.hiva

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.*
import java.util.concurrent.atomic.AtomicBoolean

class HivaMazanehSocketClient(
    private val okHttpClient: OkHttpClient
) {
    data class WallRow(val price: Double, val volume: Double)
    data class WallData(val sell: List<WallRow>, val buy: List<WallRow>)

    data class MazanehSnapshot(
        val connected: Boolean = false,
        val price: Double = 0.0,
        val wall: WallData? = null,
        val lastBar: JsonObject? = null,
        val updatedAtMs: Long = 0L
    )

    private val tag = "BT_MAZANEH_WS"
    private val started = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    
    @Volatile private var priceSocket: WebSocket? = null
    @Volatile private var wallSocket: WebSocket? = null
    @Volatile private var barsSocket: WebSocket? = null
    
    @Volatile private var snapshot = MazanehSnapshot()
    @Volatile private var snapshotListener: ((MazanehSnapshot) -> Unit)? = null

    private val wsBase = "wss://demo.hivagold.org/mazaneh/ws/mazaneh/"

    fun start() {
        if (started.getAndSet(true)) return
        Log.i(tag, "starting mazaneh sockets")
        connectAll()
    }

    fun stop() {
        started.set(false)
        priceSocket?.close(1000, "stop")
        wallSocket?.close(1000, "stop")
        barsSocket?.close(1000, "stop")
        priceSocket = null
        wallSocket = null
        barsSocket = null
        snapshot = snapshot.copy(connected = false)
        notifyListener()
    }

    fun setSnapshotListener(listener: (MazanehSnapshot) -> Unit) {
        snapshotListener = listener
    }

    private fun connectAll() {
        if (!started.get()) return
        connectPrice()
        connectWall()
        connectBars()
    }

    private fun connectPrice() {
        val req = Request.Builder().url("${wsBase}price/").build()
        priceSocket = okHttpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "price socket open")
                updateConnectedState()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = parseJson(text) ?: return
                val p = obj.get("price")?.asDouble ?: return
                snapshot = snapshot.copy(price = p, updatedAtMs = System.currentTimeMillis())
                notifyListener()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "price socket failure: ${t.message}")
                if (started.get()) handler.postDelayed({ connectPrice() }, 5000)
            }
        })
    }

    private fun connectWall() {
        val req = Request.Builder().url("${wsBase}wall/").build()
        wallSocket = okHttpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "wall socket open")
                updateConnectedState()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = parseJson(text) ?: return
                val sellArr = obj.getAsJsonArray("sell")
                val buyArr = obj.getAsJsonArray("buy")
                val sell = mutableListOf<WallRow>()
                val buy = mutableListOf<WallRow>()
                sellArr?.forEach { 
                    val o = it.asJsonObject
                    sell.add(WallRow(o.get("price").asDouble, o.get("volume").asDouble))
                }
                buyArr?.forEach { 
                    val o = it.asJsonObject
                    buy.add(WallRow(o.get("price").asDouble, o.get("volume").asDouble))
                }
                snapshot = snapshot.copy(wall = WallData(sell, buy), updatedAtMs = System.currentTimeMillis())
                notifyListener()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "wall socket failure: ${t.message}")
                if (started.get()) handler.postDelayed({ connectWall() }, 5000)
            }
        })
    }

    private fun connectBars() {
        val req = Request.Builder().url("${wsBase}live-bars/").build()
        barsSocket = okHttpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "bars socket open")
                updateConnectedState()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = parseJson(text) ?: return
                snapshot = snapshot.copy(lastBar = obj, updatedAtMs = System.currentTimeMillis())
                notifyListener()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "bars socket failure: ${t.message}")
                if (started.get()) handler.postDelayed({ connectBars() }, 5000)
            }
        })
    }

    private fun updateConnectedState() {
        val allOpen = priceSocket != null && wallSocket != null && barsSocket != null
        snapshot = snapshot.copy(connected = allOpen)
        notifyListener()
    }

    private fun notifyListener() {
        handler.post { snapshotListener?.invoke(snapshot) }
    }

    private fun parseJson(text: String): JsonObject? {
        return try { JsonParser.parseString(text).asJsonObject } catch (e: Exception) { null }
    }
}
