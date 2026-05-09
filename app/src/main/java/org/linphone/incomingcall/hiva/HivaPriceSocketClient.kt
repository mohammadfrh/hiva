package org.linphone.incomingcall.hiva

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean

class HivaPriceSocketClient(
    private val okHttpClient: OkHttpClient,
    private val symbol: String
) {
    /**
     * Open futures position id for [HivaGoldApi.closeFuturesTransaction] (same as web `close-futures-transaction/{id}/`).
     * Parsed only from account payloads (`transactions_open_list`, `new_transactions_open`, etc.).
     * **Not** the small `id` values inside ticker `data_buy` / `data_sell` (those are order-book rows).
     */
    data class TransactionRef(
        val id: String,
        val action: String,
        val isClosed: Boolean = false
    )

    data class Snapshot(
        val connected: Boolean = false,
        val accountSnapshotReady: Boolean = false,
        val price: Double = 0.0,
        val bestBid: Double = 0.0,
        val bestAsk: Double = 0.0,
        val pendingOrderCount: Int = 0,
        val openTransactions: List<TransactionRef> = emptyList(),
        val lastTransactionId: String = "",
        val updatedAtMs: Long = 0L,
        val endpoint: String = "",
        val lastError: String = ""
    )

    private val tag = "BT_PRICE_SOCKET"
    private val started = AtomicBoolean(false)
    private val reconnectHandler = Handler(Looper.getMainLooper())
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var snapshot = Snapshot()
    @Volatile private var isConnecting = false
    @Volatile private var reconnectRunnable: Runnable? = null
    @Volatile private var reconnectDelayMs: Long = 1_000L
    private val maxReconnectDelayMs: Long = 15_000L
    @Volatile private var tradingWebSocket: WebSocket? = null
    @Volatile private var liveBarsWebSocket: WebSocket? = null
    @Volatile private var snapshotListener: ((Snapshot) -> Unit)? = null
    @Volatile var rawMessageListener: ((String) -> Unit)? = null
    @Volatile var transactionClosedListener: (() -> Unit)? = null
    
    private fun endpoints(): List<String> {
        if (symbol == "mazaneh" || symbol == "mazane") {
            return listOf(
                "wss://demo.hivagold.org/mazaneh/ws/mazaneh/price/",
                "wss://demo.hivagold.org/mazaneh/ws/mazaneh/trading/",
                "wss://demo.hivagold.org/mazaneh/ws/mazaneh/live-bars/"
            )
        }
        val token = HivaGoldClient.accessTokenOrNull().orEmpty()
        val tokenPart = if (token.isNotBlank()) "?token=$token" else ""
        return listOf(
            "wss://demo.hivagold.org/ws/$tokenPart"
        )
    }
    @Volatile private var endpointIndex = 0

    fun start() {
        if (started.getAndSet(true)) return
        reconnectDelayMs = 1_000L
        cancelReconnect()
        Log.i(tag, "socket start symbol=$symbol")
        connect()
    }

    fun stop() {
        started.set(false)
        cancelReconnect()
        isConnecting = false
        Log.i(tag, "socket stop symbol=$symbol")
        webSocket?.close(1000, "stop")
        webSocket = null
        tradingWebSocket?.close(1000, "stop")
        tradingWebSocket = null
        liveBarsWebSocket?.close(1000, "stop")
        liveBarsWebSocket = null
        snapshot = snapshot.copy(connected = false, lastError = "stopped")
        snapshotListener?.invoke(snapshot)
    }

    fun ensureConnected() {
        if (!started.get()) return
        if (!snapshot.connected && !isConnecting) {
            connect()
        }
    }

    fun getSnapshot(): Snapshot = snapshot

    fun dropStaleAccountState(reason: String) {
        val current = snapshot
        if (current.openTransactions.isEmpty() && current.pendingOrderCount == 0) return
        snapshot = current.copy(
            openTransactions = emptyList(),
            pendingOrderCount = 0,
            updatedAtMs = System.currentTimeMillis()
        )
        snapshotListener?.invoke(snapshot)
        Log.w(tag, "socket account state dropped reason=$reason")
    }

    fun setSnapshotListener(listener: ((Snapshot) -> Unit)?) {
        snapshotListener = listener
    }

    private fun connect() {
        if (!started.get()) return
        if (isConnecting) return
        isConnecting = true
        cancelReconnect()
        val allEndpoints = endpoints()
        val priceEndpoint = allEndpoints[0]
        val tradingEndpoint = if (allEndpoints.size > 1) allEndpoints[1] else null
        val liveBarsEndpoint = if (allEndpoints.size > 2) allEndpoints[2] else null

        val referer = if (symbol == "mazaneh" || symbol == "mazane") {
            "${HivaGoldClient.BASE_URL}mazaneh/"
        } else {
            "${HivaGoldClient.BASE_URL}room/"
        }
        val builder = Request.Builder()
            .header("Origin", HivaGoldClient.BASE_URL.trimEnd('/'))
            .header("Referer", referer)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36")

        Log.i(tag, "socket connecting price=$priceEndpoint trading=$tradingEndpoint liveBars=$liveBarsEndpoint")
        snapshot = snapshot.copy(endpoint = priceEndpoint, lastError = "", connected = false)
        webSocket?.cancel()
        webSocket = okHttpClient.newWebSocket(builder.url(priceEndpoint).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnecting = false
                reconnectDelayMs = 1_000L
                cancelReconnect()
                snapshot = snapshot.copy(connected = true, updatedAtMs = System.currentTimeMillis(), endpoint = priceEndpoint, lastError = "")
                snapshotListener?.invoke(snapshot)
                Log.i(tag, "socket price open url=$priceEndpoint code=${response.code}")
                
                if (symbol != "mazaneh" && symbol != "mazane") {
                    webSocket.send("""{"action":"subscribe_all"}""")
                    webSocket.send("""{"action":"SubRemove","subs":["0~hivagold~$symbol~gold~1"]}""")
                    webSocket.send("""{"action":"SubAdd","subs":["0~hivagold~$symbol~gold~1"]}""")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"type\":\"ping\"") || text.contains("\"type\": \"ping\"")) {
                    webSocket.send("""{"type":"pong"}""")
                }
                parseAndUpdate(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                snapshot = snapshot.copy(connected = false, lastError = "price closed $code")
                snapshotListener?.invoke(snapshot)
                if (started.get()) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                snapshot = snapshot.copy(connected = false, lastError = "price fail ${t.message}")
                snapshotListener?.invoke(snapshot)
                Log.w(tag, "socket price failure url=$priceEndpoint error=${t.message} responseCode=${response?.code ?: "-"}")
                if (started.get()) scheduleReconnect()
            }
        })

        if (tradingEndpoint != null) {
            tradingWebSocket?.cancel()
            tradingWebSocket = okHttpClient.newWebSocket(builder.url(tradingEndpoint).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(tag, "socket trading open url=$tradingEndpoint code=${response.code}")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("transaction_closed")) {
                        Log.i(tag, "socket trading event: transaction_closed")
                        transactionClosedListener?.invoke()
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(tag, "socket trading failure url=$tradingEndpoint error=${t.message} responseCode=${response?.code ?: "-"}")
                }
            })
        }

        if (liveBarsEndpoint != null) {
            liveBarsWebSocket?.cancel()
            liveBarsWebSocket = okHttpClient.newWebSocket(builder.url(liveBarsEndpoint).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(tag, "socket live-bars open url=$liveBarsEndpoint code=${response.code}")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    parseAndUpdate(text)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(tag, "socket live-bars failure url=$liveBarsEndpoint error=${t.message} responseCode=${response?.code ?: "-"}")
                }
            })
        }
    }

    private fun parseAndUpdate(text: String) {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return
        if (!root.isJsonObject) return
        val rootObj = root.asJsonObject
        val price = findTopLevelNumericFuzzy(rootObj, "price", "P", "last_price", "lastPrice", "close", "c")
        val bid = findTopLevelNumericFuzzy(rootObj, "best_bid", "bestBid", "bid", "b")
        val ask = findTopLevelNumericFuzzy(rootObj, "best_ask", "bestAsk", "ask", "a")
        // Position ids for close API — never read from ticker `data_buy` / `data_sell` (those are book depth indices).
        val incrementalTransactions = extractTransactionsFromAnyKey(
            root = root,
            keys = setOf(
                "new_transactions_history",
                "new_transactions_open",
                "new_transaction",
                "transaction"
            )
        )
        val inferredPendingOrders = findLargestArraySizeByKey(root, setOf("user_orders_list"))

        val hasOpenList = rootObj.has("transactions_open_list")
        val hasHistoryList = rootObj.has("transactions_history_list")
        val hasOrderList = rootObj.has("user_orders_list")

        val openTransactionsFromSnapshot = if (hasOpenList) {
            extractTransactions(rootObj, "transactions_open_list")
        } else snapshot.openTransactions
        val openTransactions = mergeTransactions(
            base = openTransactionsFromSnapshot,
            additions = incrementalTransactions
        )
        val historyTransactions = if (hasHistoryList) {
            extractTransactions(rootObj, "transactions_history_list")
        } else emptyList()
        val pendingOrderCount = if (hasOrderList) {
            rootObj.getAsJsonArrayOrEmpty("user_orders_list").size()
        } else inferredPendingOrders ?: snapshot.pendingOrderCount
        val lastTransactionId = incrementalTransactions.firstOrNull()?.id
            ?: historyTransactions.firstOrNull()?.id
            ?: openTransactions.firstOrNull()?.id
            ?: snapshot.lastTransactionId

        val hasIncrementalTransaction = incrementalTransactions.isNotEmpty()
        val accountSnapshotReady = snapshot.accountSnapshotReady || hasOpenList || hasHistoryList || hasOrderList || hasIncrementalTransaction
        val hasAccountUpdate = hasOpenList || hasHistoryList || hasOrderList || hasIncrementalTransaction
        if (price == null && bid == null && ask == null &&
            !hasAccountUpdate
        ) return
        snapshot = snapshot.copy(
            connected = true,
            accountSnapshotReady = accountSnapshotReady,
            price = price ?: snapshot.price,
            bestBid = bid ?: snapshot.bestBid,
            bestAsk = ask ?: snapshot.bestAsk,
            pendingOrderCount = pendingOrderCount,
            openTransactions = openTransactions,
            lastTransactionId = lastTransactionId,
            updatedAtMs = System.currentTimeMillis()
        )
        snapshotListener?.invoke(snapshot)
        if (hasAccountUpdate) {
            Log.i(
                tag,
                "socket account update open=${openTransactions.size} pending=$pendingOrderCount lastTx=${lastTransactionId.ifBlank { "-" }} openIds=${openTransactions.take(5).joinToString(",") { it.id }} incremental=${incrementalTransactions.take(3).joinToString(",") { "${it.id}:${it.action}" }}"
            )
            rawMessageListener?.invoke(text)
        }
        if (lastTransactionId.isNotBlank() && lastTransactionId != snapshot.lastTransactionId) {
            Log.i(tag, "socket transaction id changed lastTx=$lastTransactionId")
        }
    }

    private fun scheduleReconnect() {
        if (!started.get()) return
        if (reconnectRunnable != null) return
        val delay = reconnectDelayMs
        reconnectRunnable = Runnable {
            reconnectRunnable = null
            connect()
        }
        reconnectHandler.postDelayed(reconnectRunnable!!, delay)
        Log.w(tag, "socket reconnect scheduled delayMs=$delay nextDelayMs=${(reconnectDelayMs * 2L).coerceAtMost(maxReconnectDelayMs)}")
        reconnectDelayMs = (reconnectDelayMs * 2L).coerceAtMost(maxReconnectDelayMs)
    }

    private fun cancelReconnect() {
        val task = reconnectRunnable ?: return
        reconnectHandler.removeCallbacks(task)
        reconnectRunnable = null
    }

    private fun extractTransactions(obj: JsonObject, key: String): List<TransactionRef> {
        val arr = obj.getAsJsonArrayOrEmpty(key)
        if (arr.size() == 0) return emptyList()
        val out = mutableListOf<TransactionRef>()
        for (i in 0 until arr.size()) {
            val rowEl = arr[i]
            if (!rowEl.isJsonObject) continue
            val row = rowEl.asJsonObject
            val id = row.stringValue("id", row.stringValue("transaction_id", row.stringValue("position_id", "")))
            if (id.isBlank()) continue
            val action = row.stringValue(
                "order_action",
                row.stringValue("action", row.stringValue("type", row.stringValue("direction", "")))
            ).lowercase()
            out += TransactionRef(id = id, action = action)
        }
        return out
    }

    private fun extractTransactionsFromAnyKey(root: JsonElement, keys: Set<String>): List<TransactionRef> {
        val out = mutableListOf<TransactionRef>()
        collectTransactionsRecursive(root, keys, out)
        return out
    }

    private fun collectTransactionsRecursive(
        element: JsonElement,
        keys: Set<String>,
        out: MutableList<TransactionRef>
    ) {
        if (element.isJsonNull) return
        if (element.isJsonArray) {
            val arr = element.asJsonArray
            for (i in 0 until arr.size()) {
                collectTransactionsRecursive(arr[i], keys, out)
            }
            return
        }
        if (!element.isJsonObject) return
        val obj = element.asJsonObject
        for ((key, value) in obj.entrySet()) {
            if (key in keys) {
                out += parseTransactionNode(value)
            } else {
                collectTransactionsRecursive(value, keys, out)
            }
        }
    }

    private fun findFuzzyValue(obj: JsonObject, vararg keywords: String): JsonElement? {
        for (k in keywords) {
            if (obj.has(k)) return obj.get(k)
        }
        val entries = obj.entrySet()
        for (k in keywords) {
            val cleanKey = k.replace("_", "").lowercase()
            for ((key, value) in entries) {
                val cleanCandidate = key.replace("_", "").lowercase()
                if (cleanCandidate == cleanKey || cleanCandidate.endsWith(cleanKey)) {
                    return value
                }
            }
        }
        return null
    }

    private fun parseTransactionNode(node: JsonElement): List<TransactionRef> {
        if (node.isJsonNull) return emptyList()
        if (node.isJsonArray) {
            val arr = node.asJsonArray
            val out = mutableListOf<TransactionRef>()
            for (i in 0 until arr.size()) {
                out += parseTransactionNode(arr[i])
            }
            return out
        }
        if (!node.isJsonObject) return emptyList()
        val row = node.asJsonObject
        
        val idNode = findFuzzyValue(row, "id", "transaction_id", "position_id")
        val id = if (idNode != null && !idNode.isJsonNull) idNode.asString else ""
        if (id.isBlank()) return emptyList()
        
        val actionNode = findFuzzyValue(row, "order_action", "action", "type", "direction", "side")
        val action = if (actionNode != null && !actionNode.isJsonNull) actionNode.asString.lowercase() else ""
        
        val closingPriceNode = findFuzzyValue(row, "closing_order_price", "close_price")
        val closingPrice = if (closingPriceNode != null && !closingPriceNode.isJsonNull) closingPriceNode.asDouble else -1.0
        val isClosed = closingPrice > 0.0
        return listOf(TransactionRef(id = id, action = action, isClosed = isClosed))
    }

    private fun mergeTransactions(base: List<TransactionRef>, additions: List<TransactionRef>): List<TransactionRef> {
        if (additions.isEmpty()) return base
        val merged = LinkedHashMap<String, TransactionRef>()
        for (tx in base) {
            if (tx.id.isNotBlank()) merged[tx.id] = tx
        }
        for (tx in additions) {
            if (tx.id.isNotBlank()) {
                if (tx.isClosed) {
                    merged.remove(tx.id)
                } else {
                    merged[tx.id] = tx
                }
            }
        }
        return merged.values.toList()
    }

    private fun findLargestArraySizeByKey(root: JsonElement, keys: Set<String>): Int? {
        var result: Int? = null
        fun walk(el: JsonElement) {
            if (el.isJsonNull) return
            if (el.isJsonArray) {
                val arr = el.asJsonArray
                for (i in 0 until arr.size()) walk(arr[i])
                return
            }
            if (!el.isJsonObject) return
            val obj = el.asJsonObject
            for ((key, value) in obj.entrySet()) {
                if (key in keys && value.isJsonArray) {
                    val size = value.asJsonArray.size()
                    result = maxOf(result ?: size, size)
                }
                walk(value)
            }
        }
        walk(root)
        return result
    }

    private fun findTopLevelNumericFuzzy(obj: JsonObject, vararg keywords: String): Double? {
        for (k in keywords) {
            val el = obj.get(k) ?: continue
            if (el.isJsonNull) continue
            val num = runCatching { el.asDouble }.getOrNull() ?: runCatching { el.asString.toDoubleOrNull() }.getOrNull()
            if (num != null) return num
        }
        val entries = obj.entrySet()
        for (k in keywords) {
            val cleanKey = k.replace("_", "").lowercase()
            for ((key, value) in entries) {
                if (value.isJsonNull) continue
                val cleanCandidate = key.replace("_", "").lowercase()
                if (cleanCandidate == cleanKey || cleanCandidate.endsWith(cleanKey)) {
                    val num = runCatching { value.asDouble }.getOrNull() ?: runCatching { value.asString.toDoubleOrNull() }.getOrNull()
                    if (num != null) return num
                }
            }
        }
        return null
    }

    private fun JsonObject.getAsJsonArrayOrEmpty(key: String): JsonArray {
        val el = get(key) ?: return JsonArray()
        if (!el.isJsonArray) return JsonArray()
        return el.asJsonArray
    }

    private fun JsonObject.stringValue(key: String, fallback: String = ""): String {
        val el = get(key) ?: return fallback
        if (el.isJsonNull) return fallback
        return runCatching { el.asString }.getOrDefault(fallback)
    }
}
