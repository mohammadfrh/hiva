package org.linphone.incomingcall.bot.local

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.linphone.incomingcall.hiva.HivaRoomClient
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

object LocalBotRuntime {
    private const val TAG = "BT_RUNTIME"
    private const val BT_TRACE_TAG = "BT_TRACE"
    // Must match python-bot/src/bot/live.py mock_download day window logic.
    // Tehran fixed offset used there: UTC+3:30 => subtract 12600 seconds from UTC midnight.
    private const val TEHRAN_FIXED_OFFSET_SECONDS = 3 * 3600L + 1800L

    suspend fun fetchCandles(tf: String, hours: Int): List<LocalCandle> {
        val now = System.currentTimeMillis() / 1000L
        val from = now - hours * 3600L
        Log.d(TAG, "fetchCandles tf=$tf hours=$hours from=$from to=$now")
        val candles = HivaRoomClient.getBars(tf, from, now)
        if (candles.isEmpty()) {
            Log.w(TAG, "fetchCandles returned empty list")
            return candles
        }
        val first = candles.first()
        val last = candles.last()
        Log.d(
            TAG,
            "fetchCandles count=${candles.size} firstTime=${first.time} firstClose=${first.close} lastTime=${last.time} lastClose=${last.close}"
        )
        return candles
    }

    suspend fun marketSignal(profileId: String): JsonObject {
        val candles = fetchCandles("1", 24)
        return marketSignalFromCandles(profileId, candles)
    }

    suspend fun marketSignalFromCandles(
        profileId: String,
        candles: List<LocalCandle>,
        preloadedMtf: Map<Int, List<LocalCandle>>? = null
    ): JsonObject {
        if (candles.isEmpty()) {
            return JsonObject().apply {
                addProperty("type", "no_signal")
                addProperty("reason", "insufficient_history")
            }
        }
        val mtf = if (preloadedMtf != null) {
            buildMtfFromPreloaded(candles.first().time, candles.last().time, preloadedMtf)
        } else {
            buildMtfForWindow(candles.first().time, candles.last().time)
        }
        val signal = PythonBacktestBridge.runSignal(profileId, candles, mtf)
        val exitReasonRaw = signal.stringValue("exit_reason", "")
        val exitPriceRaw = signal.doubleValue("exit_price", 0.0)
        val pnlPts = signal.doubleValue("pnl_price_points", 0.0)
        return JsonObject().apply {
            addProperty("type", signal.stringValue("type", "no_signal"))
            addProperty("direction", signal.stringValue("direction", ""))
            addProperty("entry_price", signal.stringValue("entry_price", ""))
            addProperty("stop_loss", signal.stringValue("stop_loss", ""))
            addProperty("take_profit", signal.stringValue("take_profit", ""))
            addProperty("setup_score", signal.stringValue("setup_score", ""))
            addProperty("quantity", signal.intValue("quantity", 0))
            addProperty("candle_time", signal.longValue("candle_time", 0L))
            addProperty("entry_time", signal.longValue("entry_time", 0L))
            val notes = JsonArray()
            signal.arrayValue("setup_notes").forEach { if (!it.isJsonNull) notes.add(it.asString) }
            add("setup_notes", notes)
            addProperty("exit_reason", exitReasonRaw)
            addProperty("exit_price", if (exitPriceRaw != 0.0 || exitReasonRaw.isNotBlank()) exitPriceRaw.toString() else "")
            addProperty("pnl_price_points", if (pnlPts != 0.0 || exitReasonRaw.isNotBlank()) pnlPts.toString() else "")
            addProperty("reason", exitReasonRaw.ifBlank { signal.stringValue("reason", "") })
        }
    }

    suspend fun marketBacktest(profileId: String): JsonObject {
        Log.i(TAG, "marketBacktest start profile=$profileId")
        val candles = fetchCandles("1", 24)
        if (candles.isEmpty()) {
            return JsonObject().apply {
                addProperty("trade_count", 0)
                addProperty("win_rate", 0.0)
                addProperty("net_profit", 0.0)
                addProperty("ending_balance", LocalBotConfig.STARTING_BALANCE)
                addProperty("profit_factor", 0.0)
                addProperty("max_drawdown_percent", 0.0)
            }
        }
        val mtf = buildMtfForWindow(candles.first().time, candles.last().time)
        logBacktestPayloadTrace("live24h", profileId, candles, mtf)
        val summary = PythonBacktestBridge.runBacktest(profileId, candles, mtf)
        Log.i(
            TAG,
            "marketBacktest done profile=$profileId candles=${candles.size} trades=${summary.intValue("trade_count")} winRate=${summary.doubleValue("win_rate")} net=${summary.doubleValue("net_profit")} end=${summary.doubleValue("ending_balance")} pf=${summary.doubleValue("profit_factor")} dd=${summary.doubleValue("max_drawdown_percent")}"
        )
        return summary
    }

    suspend fun marketBacktestFromCandles(
        profileId: String,
        candles: List<LocalCandle>,
        preloadedMtf: Map<Int, List<LocalCandle>>? = null
    ): JsonObject {
        if (candles.isEmpty()) {
            return JsonObject().apply {
                addProperty("trade_count", 0)
                addProperty("win_rate", 0.0)
                addProperty("net_profit", 0.0)
                addProperty("ending_balance", LocalBotConfig.STARTING_BALANCE)
                addProperty("profit_factor", 0.0)
                addProperty("max_drawdown_percent", 0.0)
                add("trades", JsonArray())
            }
        }
        val mtf = if (preloadedMtf != null) {
            buildMtfFromPreloaded(candles.first().time, candles.last().time, preloadedMtf)
        } else {
            buildMtfForWindow(candles.first().time, candles.last().time)
        }
        val payloadHash = computePayloadHash(candles, mtf)
        logBacktestPayloadTrace("from_candles", profileId, candles, mtf)
        return PythonBacktestBridge.runBacktest(profileId, candles, mtf).apply {
            addProperty("_input_payload_hash", payloadHash)
            addProperty("_input_source", "from_candles")
        }
    }

    suspend fun marketBacktest(profileId: String, datasetId: String, store: LocalDataStore): JsonObject {
        Log.i(TAG, "marketBacktest(dataset) start profile=$profileId dataset=$datasetId")
        val pythonDatasetId = normalizeDatasetIdForPython(datasetId)
        val candles1m = store.loadDatasetCandles(datasetId, "1")
        if (candles1m.isEmpty()) {
            Log.w(
                TAG,
                "marketBacktest(dataset) empty dataset=$datasetId fallback=live24h available=${store.listDatasets().joinToString(",")}"
            )
            return marketBacktest(profileId)
        }
        val first = candles1m.first()
        val last = candles1m.last()
        val dayDatasetPattern = Regex("^[A-Za-z]+-day-(\\d{4}-\\d{2}-\\d{2})$")
        val initialMtfCounts = mutableMapOf<String, Int>()
        val missingTfs = mutableListOf<String>()
        for (tf in listOf("5", "15", "30", "60")) {
            val tfCandles = store.loadDatasetCandlesMockOnly(datasetId, tf)
            if (tfCandles.isNotEmpty()) initialMtfCounts["${tf}m"] = tfCandles.size else missingTfs += tf
        }
        if (missingTfs.isNotEmpty() && dayDatasetPattern.matches(datasetId)) {
            val date = dayDatasetPattern.find(datasetId)?.groupValues?.get(1).orEmpty()
            if (date.isNotBlank()) {
                try {
                    store.ensureMockDayTimeframes(date, missingTfs)
                } catch (_: Exception) {
                    // Keep execution path exact-python with whatever files exist.
                }
            }
        }
        val mtfCounts = mutableMapOf<String, Int>()
        for (tf in listOf("5", "15", "30", "60")) {
            val tfCandles = store.loadDatasetCandlesMockOnly(datasetId, tf)
            if (tfCandles.isNotEmpty()) {
                mtfCounts["${tf}m"] = tfCandles.size
            }
        }
        logBacktestPayloadTrace(
            source = "dataset:$datasetId",
            profileId = profileId,
            candles1m = candles1m,
            mtf = mapOf(
                5 to store.loadDatasetCandlesMockOnly(datasetId, "5"),
                15 to store.loadDatasetCandlesMockOnly(datasetId, "15"),
                30 to store.loadDatasetCandlesMockOnly(datasetId, "30"),
                60 to store.loadDatasetCandlesMockOnly(datasetId, "60")
            ).filterValues { it.isNotEmpty() }
        )
        Log.i(
            TAG,
            "marketBacktest(dataset) loaded dataset=$datasetId pythonDataset=$pythonDatasetId count=${candles1m.size} firstTime=${first.time} lastTime=${last.time} mtf_before=$initialMtfCounts mtf_after=$mtfCounts source=python-load_dataset"
        )
        val summary = runCatching {
            PythonBacktestBridge.runBacktestFromDataset(
                profileId = profileId,
                datasetId = pythonDatasetId,
                mockDirPath = store.mockDirPath(),
                outputsDirPath = store.outputsDirPath()
            )
        }.getOrElse { err ->
            Log.e(
                TAG,
                "marketBacktest(dataset) python failure profile=$profileId dataset=$datasetId pythonDataset=$pythonDatasetId error=${err.message}",
                err
            )
            return JsonObject().apply {
                addProperty("trade_count", 0)
                addProperty("win_count", 0)
                addProperty("loss_count", 0)
                addProperty("win_rate", 0.0)
                addProperty("net_profit", 0.0)
                addProperty("withdrawn_profit", 0.0)
                addProperty("total_fees", 0.0)
                addProperty("ending_balance", LocalBotConfig.STARTING_BALANCE)
                addProperty("profit_factor", 0.0)
                addProperty("max_drawdown_percent", 0.0)
                addProperty("error", err.message ?: "python_backtest_failed")
            }
        }
        Log.i(
            TAG,
            "marketBacktest(dataset) done profile=$profileId dataset=$datasetId pythonDataset=$pythonDatasetId trades=${summary.intValue("trade_count")} wins=${summary.intValue("win_count")} losses=${summary.intValue("loss_count")} winRate=${summary.doubleValue("win_rate")} net=${summary.doubleValue("net_profit")} withdrawn=${summary.doubleValue("withdrawn_profit")} fees=${summary.doubleValue("total_fees")}"
        )
        BacktestParityLogger.logComparison(datasetId, profileId, summary)
        return summary
    }

    suspend fun dashboard(profileId: String): JsonObject {
        val health = JsonObject().apply {
            addProperty("status", "ok-local")
            addProperty("profile", profileId)
        }
        val signal = marketSignal(profileId)
        val bt = marketBacktest(profileId)
        val trades = bt.arrayValue("trades")
        val wins = trades.count {
            it.isJsonObject && it.asJsonObject.doubleValue(
                "net_pnl_money",
                it.asJsonObject.doubleValue("netPnlMoney", 0.0)
            ) > 0.0
        }
        val losses = trades.count {
            it.isJsonObject && it.asJsonObject.doubleValue(
                "net_pnl_money",
                it.asJsonObject.doubleValue("netPnlMoney", 0.0)
            ) <= 0.0
        }
        val todaySummary = JsonObject().apply {
            addProperty("closed_trade_count", bt.intValue("trade_count", trades.size()))
            addProperty("win_count", wins)
            addProperty("loss_count", losses)
            addProperty("net_pnl_money", bt.doubleValue("net_profit", 0.0))
        }
        Log.i(
            TAG,
            "dashboard summary profile=$profileId trades=${todaySummary.intValue("closed_trade_count", 0)} wins=$wins losses=$losses net=${todaySummary.doubleValue("net_pnl_money", 0.0)} winRate=${bt.doubleValue("win_rate", 0.0)} withdrawn=${bt.doubleValue("withdrawn_profit", 0.0)} fees=${bt.doubleValue("total_fees", 0.0)}"
        )
        for (i in 0 until trades.size()) {
            val trEl = trades[i]
            if (!trEl.isJsonObject) continue
            val tr = trEl.asJsonObject
            val direction = tr.stringValue("direction", "-")
            val entry = tr.stringValue("entry_price", tr.stringValue("entryPrice", "-"))
            val exit = tr.stringValue("exit_price", tr.stringValue("exitPrice", "-"))
            val pnlPoints = tr.stringValue("pnl_price_points", tr.stringValue("pnlPricePoints", "-"))
            val netPnl = tr.doubleValue("net_pnl_money", tr.doubleValue("netPnlMoney", 0.0))
            val reason = tr.stringValue("exit_reason", tr.stringValue("exitReason", "-"))
            val result = if (netPnl > 0.0) "win" else "loss"
            Log.i(
                TAG,
                "dashboard trade[$i] profile=$profileId dir=$direction entry=$entry exit=$exit pnlPoints=$pnlPoints net=$netPnl result=$result reason=$reason"
            )
        }
        val todaySignals = JsonArray().apply {
            for (i in trades.size() - 1 downTo 0) {
                val tr = trades[i].asJsonObject
                val recordedAt = runCatching {
                    val sec = tr.longValue("exit_time", tr.longValue("exitTime", 0L))
                    if (sec > 0L) Instant.ofEpochSecond(sec).toString() else Instant.now().toString()
                }.getOrDefault(Instant.now().toString())
                add(JsonObject().apply {
                    addProperty("recorded_at", recordedAt)
                    addProperty("signal_type", "close_signal")
                    addProperty("direction", tr.stringValue("direction", "-"))
                    addProperty("entry_price", tr.stringValue("entry_price", tr.stringValue("entryPrice", "-")))
                    addProperty("exit_price", tr.stringValue("exit_price", tr.stringValue("exitPrice", "-")))
                    addProperty("pnl_points", tr.stringValue("pnl_price_points", tr.stringValue("pnlPricePoints", "-")))
                    addProperty("exit_reason", tr.stringValue("exit_reason", tr.stringValue("exitReason", "-")))
                })
            }
            if (size() == 0) {
                val now = Instant.now().toString()
                add(JsonObject().apply {
                    addProperty("recorded_at", now)
                    addProperty("signal_type", signal.stringValue("type", "no_signal"))
                    addProperty("direction", signal.stringValue("direction", "-"))
                    addProperty("entry_price", signal.stringValue("entry_price", "-"))
                    addProperty("exit_price", "-")
                    addProperty("pnl_points", "-")
                    addProperty("exit_reason", signal.stringValue("reason", "-"))
                })
            }
        }
        val config = JsonObject().apply {
            addProperty("profile_id", profileId)
            addProperty("auto_trade", false)
        }
        return JsonObject().apply {
            add("health", health)
            add("signal", signal)
            add("today_summary", todaySummary)
            add("today_signals", todaySignals)
            add("config", config)
        }
    }

    fun gregorianDayRange(date: String): Pair<Long, Long> {
        val d = LocalDate.parse(date)
        val utcMidnight = d.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val start = utcMidnight - TEHRAN_FIXED_OFFSET_SECONDS
        return start to (start + 86400L)
    }

    private suspend fun buildMtfForWindow(from: Long, to: Long): Map<Int, List<LocalCandle>> {
        val mtf = mutableMapOf<Int, List<LocalCandle>>()
        for (tf in listOf("5", "15", "30", "60")) {
            try {
                val tfCandles = HivaRoomClient.getBars(tf, from, to)
                if (tfCandles.isNotEmpty()) {
                    mtf[tf.toInt()] = tfCandles
                }
            } catch (_: Exception) {
                // Keep missing HTF empty; Python engine will use available data.
            }
        }
        return mtf
    }

    private fun buildMtfFromPreloaded(
        from: Long,
        to: Long,
        preloadedMtf: Map<Int, List<LocalCandle>>
    ): Map<Int, List<LocalCandle>> {
        val out = mutableMapOf<Int, List<LocalCandle>>()
        for ((tf, rows) in preloadedMtf) {
            if (rows.isEmpty()) continue
            val sliced = rows.asSequence()
                .filter { it.time in from..to }
                .toList()
            if (sliced.isNotEmpty()) out[tf] = sliced
        }
        return out
    }

    private fun JsonObject.intValue(key: String, fallback: Int = 0): Int {
        val el = get(key) ?: return fallback
        if (el.isJsonNull) return fallback
        return runCatching { el.asInt }.getOrDefault(fallback)
    }

    private fun JsonObject.doubleValue(key: String, fallback: Double = 0.0): Double {
        val el = get(key) ?: return fallback
        if (el.isJsonNull) return fallback
        return runCatching { el.asDouble }.getOrDefault(fallback)
    }

    private fun JsonObject.stringValue(key: String, fallback: String = ""): String {
        val el = get(key) ?: return fallback
        if (el.isJsonNull) return fallback
        return runCatching { el.asString }.getOrDefault(fallback)
    }

    private fun JsonObject.longValue(key: String, fallback: Long = 0L): Long {
        val el = get(key) ?: return fallback
        if (el.isJsonNull) return fallback
        return runCatching { el.asLong }.getOrDefault(fallback)
    }

    private fun JsonObject.arrayValue(key: String): JsonArray {
        val el = get(key) ?: return JsonArray()
        if (el.isJsonNull || !el.isJsonArray) return JsonArray()
        return el.asJsonArray
    }

    private fun normalizeDatasetIdForPython(datasetId: String): String {
        val cacheDay = Regex("^C-day-(\\d{4}-\\d{2}-\\d{2})$")
        val m = cacheDay.find(datasetId) ?: return datasetId
        val day = m.groupValues[1]
        // Python load_dataset expects actual file prefix; our downloaded mock files use M-day-YYYY-MM-DD.
        return "M-day-$day"
    }

    private fun logBacktestPayloadTrace(
        source: String,
        profileId: String,
        candles1m: List<LocalCandle>,
        mtf: Map<Int, List<LocalCandle>>
    ) {
        if (candles1m.isEmpty()) {
            Log.i(BT_TRACE_TAG, "run_backtest_input source=$source profile=$profileId candles=0")
            return
        }
        val first1m = candles1m.first().time
        val last1m = candles1m.last().time
        val hash = computePayloadHash(candles1m, mtf)
        val tail1m = candles1m.takeLast(3).joinToString(";") {
            "${it.time}:${it.open},${it.high},${it.low},${it.close}"
        }
        val mtfSummary = mtf.keys.sorted().joinToString(",") { tf ->
            val rows = mtf[tf].orEmpty()
            val first = rows.firstOrNull()?.time ?: 0L
            val last = rows.lastOrNull()?.time ?: 0L
            "${tf}m:${rows.size}:$first-$last"
        }
        Log.i(
            BT_TRACE_TAG,
            "run_backtest_input source=$source profile=$profileId hash=$hash 1m=${candles1m.size}:$first1m-$last1m tail=$tail1m mtf=$mtfSummary"
        )
    }

    private fun computePayloadHash(
        candles1m: List<LocalCandle>,
        mtf: Map<Int, List<LocalCandle>>
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun feed(text: String) = md.update(text.toByteArray(Charsets.UTF_8))
        for (c in candles1m) {
            feed("1m,${c.time},${c.open},${c.high},${c.low},${c.close};")
        }
        for (tf in mtf.keys.sorted()) {
            feed("tf=$tf|")
            for (c in mtf[tf].orEmpty()) {
                feed("${c.time},${c.open},${c.high},${c.low},${c.close};")
            }
        }
        return md.digest().joinToString(separator = "") { "%02x".format(it) }
    }
}
