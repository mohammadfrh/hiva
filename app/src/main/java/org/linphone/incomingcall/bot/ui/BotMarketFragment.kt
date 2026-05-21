package org.linphone.incomingcall.bot.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import com.github.mikephil.charting.data.CombinedData
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.incomingcall.IncomingCallApp
import org.linphone.incomingcall.R
import org.linphone.incomingcall.bot.prettyJson
import org.linphone.incomingcall.bot.local.LocalBotEngine
import org.linphone.incomingcall.bot.local.LocalBotIndicators
import org.linphone.incomingcall.hiva.HivaRoomClient
import org.linphone.incomingcall.hiva.HivaGoldClient
import org.linphone.incomingcall.hiva.HivaMazanehSocketClient
import org.linphone.incomingcall.hiva.HivaGoldApi
import org.linphone.incomingcall.hiva.MazanehTransactions
import org.linphone.incomingcall.hiva.MazanehPortfolio

class BotMarketFragment : Fragment(R.layout.fragment_bot_market) {
    private val tag = "BT_MARKET_UI"
    private val handler = Handler(Looper.getMainLooper())
    private var outRef: TextView? = null
    private var summaryRef: TextView? = null
    private var signalSummaryRef: TextView? = null
    private var tfRef: EditText? = null
    private var chartRef: CombinedChart? = null
    private val bidsAdapter = BotOrderLevelAdapter()
    private val asksAdapter = BotOrderLevelAdapter()
    private val posOrderAdapter = BotPositionOrderAdapter()
    private val mazanehWsClient by lazy { HivaMazanehSocketClient(HivaGoldClient.okHttpClient) }

    private val refresher = object : Runnable {
        override fun run() {
            refreshSnapshot()
            handler.postDelayed(this, 10000)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val out = view.findViewById<TextView>(R.id.textMarketOut)
        outRef = out
        val tf = view.findViewById<EditText>(R.id.editTf)
        tfRef = tf
        val direction = view.findViewById<EditText>(R.id.editDirection)
        val price = view.findViewById<EditText>(R.id.editPrice)
        val sl = view.findViewById<EditText>(R.id.editSl)
        val tp = view.findViewById<EditText>(R.id.editTp)
        val adoptPositionId = view.findViewById<EditText>(R.id.editAdoptPositionId)
        val adoptDirection = view.findViewById<EditText>(R.id.editAdoptDirection)
        val adoptFillPrice = view.findViewById<EditText>(R.id.editAdoptFillPrice)
        val adoptStopLoss = view.findViewById<EditText>(R.id.editAdoptStopLoss)
        val adoptTargets = view.findViewById<EditText>(R.id.editAdoptTargets)
        summaryRef = view.findViewById(R.id.textMarketSummary)
        signalSummaryRef = view.findViewById(R.id.textSignalSummary)
        chartRef = view.findViewById<CombinedChart>(R.id.candleChart).apply {
            setBackgroundColor(requireContext().getColor(R.color.hiva_card))
            description.isEnabled = false
            setDrawGridBackground(false)
            legend.isEnabled = false
            setPinchZoom(true)
            isDragEnabled = true
            setScaleEnabled(true)
            axisLeft.textColor = requireContext().getColor(R.color.hiva_text_secondary)
            axisRight.textColor = requireContext().getColor(R.color.hiva_text_secondary)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.textColor = requireContext().getColor(R.color.hiva_text_secondary)
            xAxis.setDrawGridLines(false)
            setDrawOrder(
                arrayOf(
                    CombinedChart.DrawOrder.CANDLE,
                    CombinedChart.DrawOrder.LINE
                )
            )
        }
        view.findViewById<RecyclerView>(R.id.recyclerBids).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = bidsAdapter
        }
        view.findViewById<RecyclerView>(R.id.recyclerAsks).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = asksAdapter
        }
        view.findViewById<RecyclerView>(R.id.recyclerPositionsOrders).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = posOrderAdapter
        }
        val signalBtn = view.findViewById<Button>(R.id.buttonMarketSignal)
        val indBtn = view.findViewById<Button>(R.id.buttonIndicators)
        val snapshotBtn = view.findViewById<Button>(R.id.buttonSnapshot)
        val marketBacktestBtn = view.findViewById<Button>(R.id.buttonMarketBacktest)
        val orderBtn = view.findViewById<Button>(R.id.buttonPlaceOrder)
        val posStatusBtn = view.findViewById<Button>(R.id.buttonPositionStatus)
        val posResetBtn = view.findViewById<Button>(R.id.buttonPositionReset)
        val posAdoptBtn = view.findViewById<Button>(R.id.buttonPositionAdopt)
        view.findViewById<Button>(R.id.buttonChartZoomIn).setOnClickListener {
            chartRef?.zoom(1.2f, 1f, 0f, 0f)
        }
        view.findViewById<Button>(R.id.buttonChartZoomOut).setOnClickListener {
            chartRef?.zoom(0.8f, 1f, 0f, 0f)
        }
        view.findViewById<Button>(R.id.buttonChartReset).setOnClickListener {
            chartRef?.fitScreen()
            chartRef?.invalidate()
        }

        signalBtn.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                out.text = getString(R.string.loading)
                try {
                    val candles = fetchCandles("1", 24)
                    val app = requireActivity().application as IncomingCallApp
                    val local = LocalBotEngine.evaluateSignal(candles, app.botPrefs.localProfileId)
                    val res = JsonObject().apply {
                        addProperty("type", local.type)
                        addProperty("direction", local.direction)
                        addProperty("entry_price", local.entryPrice)
                        addProperty("stop_loss", local.stopLoss)
                        addProperty("take_profit", local.takeProfit)
                        addProperty("setup_score", local.setupScore)
                        addProperty("reason", local.reason)
                    }
                    bindSignalSummary(res)
                    out.text = res.prettyJson()
                } catch (e: Exception) {
                    out.text = e.message ?: getString(R.string.network_error)
                }
            }
        }
        indBtn.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                out.text = getString(R.string.loading)
                try {
                    val timeframe = normalizeTimeframe(tf.text.toString())
                    tf.setText(timeframe)
                    val candles = fetchCandles(timeframe, 24)
                    val ind = LocalBotIndicators.build(candles)
                    val res = buildIndicatorsJson(timeframe, candles, ind)
                    bindChart(res)
                    out.text = res.prettyJson()
                } catch (e: Exception) {
                    out.text = e.message ?: getString(R.string.network_error)
                }
            }
        }
        snapshotBtn.setOnClickListener { refreshSnapshot() }
        marketBacktestBtn.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                out.text = getString(R.string.loading)
                try {
                    val app = requireActivity().application as IncomingCallApp
                    val candles = fetchCandles("1", 24)
                    val summary = LocalBotEngine.backtest(candles, app.botPrefs.localProfileId)
                    val res = JsonObject().apply {
                        addProperty("trade_count", summary.tradeCount)
                        addProperty("win_rate", summary.winRate)
                        addProperty("net_profit", summary.netProfit)
                        addProperty("ending_balance", summary.endingBalance)
                        addProperty("profit_factor", summary.profitFactor)
                        addProperty("max_drawdown_percent", summary.maxDrawdownPercent)
                    }
                    out.text = res.prettyJson()
                } catch (e: Exception) {
                    out.text = e.message ?: getString(R.string.network_error)
                }
            }
        }
        posStatusBtn.setOnClickListener { run(out) { HivaGoldClient.api.getMazanehTransactions(1) } }
        posResetBtn.setOnClickListener {
            val res = JsonObject().apply {
                addProperty("ok", true)
                addProperty("message", "local mode reset noop")
            }
            out.text = res.prettyJson()
        }
        posAdoptBtn.setOnClickListener {
            val fillPrice = adoptFillPrice.text.toString().toDoubleOrNull()
            val stopLoss = adoptStopLoss.text.toString().toDoubleOrNull()
            val targets = adoptTargets.text.toString()
                .split(",")
                .mapNotNull { it.trim().toDoubleOrNull() }
            val dir = adoptDirection.text.toString().trim().lowercase()
            if (adoptPositionId.text.toString().trim().isBlank() || fillPrice == null || stopLoss == null || targets.isEmpty()) {
                out.text = getString(R.string.bot_invalid_order_input)
                return@setOnClickListener
            }
            if (dir != "long" && dir != "short") {
                out.text = getString(R.string.bot_invalid_direction)
                return@setOnClickListener
            }
            val body = JsonObject().apply {
                addProperty("position_id", adoptPositionId.text.toString().trim())
                addProperty("direction", dir)
                addProperty("fill_price", fillPrice)
                addProperty("stop_loss", stopLoss)
                add("target_prices", com.google.gson.Gson().toJsonTree(targets))
                addProperty("note", "local mode adopt is informational")
            }
            out.text = body.prettyJson()
        }
        orderBtn.setOnClickListener {
            val orderPrice = price.text.toString().toDoubleOrNull()
            val stopLoss = sl.text.toString().toDoubleOrNull()
            val takeProfit = tp.text.toString().toDoubleOrNull()
            if (orderPrice == null || stopLoss == null || takeProfit == null) {
                out.text = getString(R.string.bot_invalid_order_input)
                return@setOnClickListener
            }
            val dir = direction.text.toString().trim().lowercase()
            if (dir != "long" && dir != "short") {
                out.text = getString(R.string.bot_invalid_direction)
                return@setOnClickListener
            }
            run(out) {
                val body = JsonObject().apply {
                    addProperty("action", if (dir == "long") "buy" else "sell")
                    addProperty("order_type", "limit")
                    addProperty("units", "1")
                    addProperty("price", orderPrice.toInt().toString())
                    addProperty("take_profit", takeProfit.toInt().toString())
                    addProperty("stop_loss", stopLoss.toInt().toString())
                    addProperty("signal_token", "")
                }
                HivaGoldClient.api.createMazanehOrder(body)
            }
        }

        mazanehWsClient.setSnapshotListener { wsSnap ->
            handler.post {
                updateWsStatus(wsSnap)
            }
        }
        mazanehWsClient.start()
    }

    private fun updateWsStatus(wsSnap: HivaMazanehSocketClient.MazanehSnapshot) {
        val summary = summaryRef ?: return
        val currentText = summary.text.toString()
        val lines = currentText.split("\n").toMutableList()
        
        // Update lines or just rebuild
        summary.text = buildString {
            appendLine("ws_connected: ${wsSnap.connected}")
            appendLine("price: ${wsSnap.price}")
            
            // Try to keep other info from poll if available
            val pnlLine = lines.find { it.contains("pnl:") }
            if (pnlLine != null) appendLine(pnlLine)
            
            val openPosLine = lines.find { it.contains("open_positions:") }
            if (openPosLine != null) appendLine(openPosLine)

            val pendingLine = lines.find { it.contains("pending_orders:") }
            if (pendingLine != null) appendLine(pendingLine)
        }

        // Update walls if available
        wsSnap.wall?.let { wall ->
            val bidRows = wall.buy.take(10).map { BotOrderLevelRow(it.price.toString(), it.volume.toString()) }
            val askRows = wall.sell.take(10).map { BotOrderLevelRow(it.price.toString(), it.volume.toString()) }
            bidsAdapter.submitList(bidRows)
            asksAdapter.submitList(askRows)
        }
    }

    override fun onResume() {
        super.onResume()
        mazanehWsClient.start()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        mazanehWsClient.stop()
        handler.removeCallbacks(refresher)
    }

    private fun run(out: TextView, block: suspend (HivaGoldApi) -> com.google.gson.JsonElement) {
        out.text = getString(R.string.loading)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res = block(HivaGoldClient.api)
                out.text = res.prettyJson()
            } catch (e: Exception) {
                out.text = e.message ?: getString(R.string.network_error)
            }
        }
    }

    private fun refreshSnapshot() {
        val out = outRef ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val tf = normalizeTimeframe(tfRef?.text?.toString().orEmpty())
                tfRef?.setText(tf)
                
                // Fetch new Mazaneh APIs
                val status = HivaGoldClient.api.getMazanehStatus()
                val txJson = HivaGoldClient.api.getMazanehTransactions(1)
                val openTx = txJson.getAsJsonArray("open") ?: com.google.gson.JsonArray()
                val closedTx = txJson.getAsJsonArray("closed") ?: com.google.gson.JsonArray()
                val legacyResults = txJson.getAsJsonArray("results")
                if (legacyResults != null && openTx.size() == 0 && closedTx.size() == 0) {
                    legacyResults.forEach {
                        val obj = it.asJsonObject
                        if (obj.get("status")?.asString == "open") openTx.add(obj) else closedTx.add(obj)
                    }
                }
                
                val orders = HivaGoldClient.api.getMazanehActiveOrders()
                val staticValues = HivaGoldClient.api.getMazanehStaticValues()
                val portfolio = try { HivaGoldClient.api.getMazanehActivePortfolio() } catch(e: Exception) { null }
                
                val candles = fetchCandles(tf, 24)
                val last = candles.lastOrNull()
                
                val snapshot = JsonObject().apply {
                    addProperty("active", status.active)
                    addProperty("reason", status.reason)
                    addProperty("notification", status.notification)
                    addProperty("price", last?.close ?: 0.0)
                    add("static_values", com.google.gson.Gson().toJsonTree(staticValues))
                    add("open_transactions", openTx)
                    add("closed_transactions", closedTx)
                    add("active_orders", com.google.gson.Gson().toJsonTree(orders))
                    add("active_portfolio", com.google.gson.Gson().toJsonTree(portfolio))
                }
                
                bindSnapshotSummary(snapshot, summaryRef)
                // bindOrderBook is now handled by WS listener mostly, but can be updated here too if needed
                bindPositionsOrdersFromHiva(openTx, orders)
                
                val ind = LocalBotIndicators.build(candles)
                val indicators = buildIndicatorsJson(tf, candles, ind)
                bindChart(indicators)
                
                out.text = snapshot.prettyJson()
            } catch (e: Exception) {
                out.text = e.message ?: getString(R.string.network_error)
            }
        }
    }

    private fun bindPositionsOrdersFromHiva(openTxs: com.google.gson.JsonArray, orders: List<JsonObject>) {
        val rows = mutableListOf<BotPositionOrderRow>()
        for (rowElem in openTxs) {
            val row = rowElem.asJsonObject
            val id = row.get("id")?.asString ?: "-"
            val action = row.get("action")?.asString ?: "-"
            val pnl = row.get("pnl")?.asString ?: "-"
            rows += BotPositionOrderRow("OPEN #$id $action", "pnl: $pnl")
        }
        for (row in orders) {
            val id = row.get("id")?.asString ?: "-"
            val action = row.get("action")?.asString ?: "-"
            val status = row.get("status")?.asString ?: "-"
            rows += BotPositionOrderRow("ORDER #$id $action", "status: $status")
        }
        posOrderAdapter.submitList(rows)
    }

    private suspend fun fetchCandles(tf: String, hours: Int): List<org.linphone.incomingcall.bot.local.LocalCandle> {
        val now = System.currentTimeMillis() / 1000L
        val from = now - hours * 3600L
        return withContext(Dispatchers.IO) { 
            val hivaBars = HivaGoldClient.api.getMazanehBars(
                "mazaneh",
                from,
                now,
                org.linphone.incomingcall.hiva.MazanehBarsResolution.toApi(tf)
            )
            hivaBars.map { org.linphone.incomingcall.bot.local.LocalCandle(it.time, it.open, it.high, it.low, it.close) }
        }
    }

    private fun buildIndicatorsJson(
        tf: String,
        candles: List<org.linphone.incomingcall.bot.local.LocalCandle>,
        ind: org.linphone.incomingcall.bot.local.LocalIndicatorSet
    ): JsonObject {
        val root = JsonObject()
        root.addProperty("tf", tf)
        val cArr = com.google.gson.JsonArray()
        candles.forEach { c ->
            cArr.add(JsonObject().apply {
                addProperty("time", c.time)
                addProperty("open", c.open)
                addProperty("high", c.high)
                addProperty("low", c.low)
                addProperty("close", c.close)
            })
        }
        root.add("candles", cArr)
        root.add("indicators", JsonObject().apply {
            add("ema_fast", toJsonArray(ind.emaFast))
            add("ema_slow", toJsonArray(ind.emaSlow))
        })
        return root
    }

    private fun toJsonArray(values: List<Double?>): com.google.gson.JsonArray {
        val arr = com.google.gson.JsonArray()
        values.forEach { v ->
            if (v == null) arr.add(com.google.gson.JsonNull.INSTANCE) else arr.add(v)
        }
        return arr
    }

    private fun bindSnapshotSummary(snapshot: JsonObject, summary: TextView?) {
        val wsConnected = snapshot.get("ws_connected")?.asString ?: "-"
        val price = snapshot.get("price")?.asString ?: "-"
        val bestBid = snapshot.get("best_bid")?.asString ?: "-"
        val bestAsk = snapshot.get("best_ask")?.asString ?: "-"
        val openCount = snapshot.getAsJsonArray("transactions_open_list")?.size() ?: 0
        val orderCount = snapshot.getAsJsonArray("user_orders_list")?.size() ?: 0
        summary?.text = buildString {
            appendLine("ws_connected: $wsConnected")
            appendLine("price: $price")
            appendLine("best_bid: $bestBid")
            appendLine("best_ask: $bestAsk")
            appendLine("open_positions: $openCount")
            append("pending_orders: $orderCount")
        }
    }

    private fun bindSignalSummary(signal: JsonObject) {
        val type = signal.get("type")?.asString ?: "no_signal"
        val direction = signal.get("direction")?.asString ?: "-"
        val entry = signal.get("entry_price")?.asString ?: "-"
        val stop = signal.get("stop_loss")?.asString ?: "-"
        val take = signal.get("take_profit")?.asString ?: "-"
        val score = signal.get("setup_score")?.asString ?: "-"
        val notes = signal.getAsJsonArray("setup_notes")?.joinToString(", ") { it.asString } ?: "-"
        signalSummaryRef?.text = buildString {
            appendLine("type: $type | direction: $direction")
            appendLine("entry: $entry | stop: $stop | take: $take")
            appendLine("score: $score")
            append("notes: $notes")
        }
    }

    private fun bindOrderBook(snapshot: JsonObject) {
        val bids = snapshot.getAsJsonArray("bids")
        val asks = snapshot.getAsJsonArray("asks")
        val bidRows = mutableListOf<BotOrderLevelRow>()
        val askRows = mutableListOf<BotOrderLevelRow>()
        if (bids != null) {
            for (i in 0 until minOf(10, bids.size())) {
                val row = bids[i].asJsonObject
                bidRows += BotOrderLevelRow(
                    price = row.get("price")?.asString ?: "-",
                    qty = row.get("qty")?.asString ?: "-"
                )
            }
        }
        if (asks != null) {
            for (i in 0 until minOf(10, asks.size())) {
                val row = asks[i].asJsonObject
                askRows += BotOrderLevelRow(
                    price = row.get("price")?.asString ?: "-",
                    qty = row.get("qty")?.asString ?: "-"
                )
            }
        }
        bidsAdapter.submitList(bidRows)
        asksAdapter.submitList(askRows)
    }

    private fun bindPositionsOrders(snapshot: JsonObject) {
        val rows = mutableListOf<BotPositionOrderRow>()
        val open = snapshot.getAsJsonArray("transactions_open_list")
        val orders = snapshot.getAsJsonArray("user_orders_list")
        if (open != null) {
            for (i in 0 until open.size()) {
                val row = open[i].asJsonObject
                val id = row.get("id")?.asString ?: "-"
                val action = row.get("action")?.asString ?: "-"
                val pnl = row.get("pnl")?.asString ?: "-"
                rows += BotPositionOrderRow(
                    title = "OPEN #$id $action",
                    subtitle = "pnl: $pnl"
                )
            }
        }
        if (orders != null) {
            for (i in 0 until orders.size()) {
                val row = orders[i].asJsonObject
                val id = row.get("id")?.asString ?: "-"
                val action = row.get("action")?.asString ?: "-"
                val status = row.get("status")?.asString ?: "-"
                rows += BotPositionOrderRow(
                    title = "ORDER #$id $action",
                    subtitle = "status: $status"
                )
            }
        }
        posOrderAdapter.submitList(rows)
    }

    private fun bindChart(indicators: JsonObject) {
        val chart = chartRef ?: return
        val candles = indicators.getAsJsonArray("candles") ?: return
        if (candles.size() == 0) {
            chart.clear()
            chart.setNoDataText("No candles")
            chart.invalidate()
            Log.w(tag, "bindChart skipped: candles array is empty")
            return
        }
        val entries = mutableListOf<CandleEntry>()
        for (i in 0 until candles.size()) {
            val c = candles[i].asJsonObject
            val open = c.get("open")?.asFloat ?: continue
            val high = c.get("high")?.asFloat ?: continue
            val low = c.get("low")?.asFloat ?: continue
            val close = c.get("close")?.asFloat ?: continue
            entries += CandleEntry(i.toFloat(), high, low, open, close)
        }
        if (entries.isEmpty()) {
            chart.clear()
            chart.setNoDataText("No valid candle points")
            chart.invalidate()
            Log.w(tag, "bindChart skipped: parsed candle entries are empty")
            return
        }
        val set = CandleDataSet(entries, "candles").apply {
            color = requireContext().getColor(R.color.hiva_text_secondary)
            shadowColor = color
            shadowWidth = 0.8f
            decreasingColor = requireContext().getColor(R.color.hiva_red)
            increasingColor = requireContext().getColor(R.color.hiva_green)
            neutralColor = requireContext().getColor(R.color.hiva_text_primary)
            setDrawValues(false)
        }
        val combined = CombinedData()
        combined.setData(CandleData(set))

        val indicatorObj = indicators.getAsJsonObject("indicators")
        val lineDataSets = mutableListOf<ILineDataSet>()
        val emaFast = indicatorObj?.getAsJsonArray("ema_fast")
        if (emaFast != null && emaFast.size() == candles.size()) {
            val emaEntries = mutableListOf<Entry>()
            for (i in 0 until emaFast.size()) {
                val v = emaFast[i]
                if (!v.isJsonNull) {
                    emaEntries += Entry(i.toFloat(), v.asFloat)
                }
            }
            if (emaEntries.isNotEmpty()) {
                val emaSet = LineDataSet(emaEntries, "EMA").apply {
                    color = requireContext().getColor(R.color.hiva_accent)
                    lineWidth = 1.4f
                    setDrawValues(false)
                    setDrawCircles(false)
                }
                lineDataSets += emaSet
            }
        }

        val emaSlow = indicatorObj?.getAsJsonArray("ema_slow")
        if (emaSlow != null && emaSlow.size() == candles.size()) {
            val emaSlowEntries = mutableListOf<Entry>()
            for (i in 0 until emaSlow.size()) {
                val v = emaSlow[i]
                if (!v.isJsonNull) {
                    emaSlowEntries += Entry(i.toFloat(), v.asFloat)
                }
            }
            if (emaSlowEntries.isNotEmpty()) {
                val emaSlowSet = LineDataSet(emaSlowEntries, "EMA Slow").apply {
                    color = requireContext().getColor(R.color.hiva_green)
                    lineWidth = 1.2f
                    setDrawValues(false)
                    setDrawCircles(false)
                }
                lineDataSets += emaSlowSet
            }
        }
        if (lineDataSets.isNotEmpty()) {
            combined.setData(LineData(lineDataSets))
        }

        chart.data = combined
        chart.invalidate()
    }

    private fun normalizeTimeframe(raw: String): String {
        return when (raw.trim()) {
            "1", "5", "15", "30", "60" -> raw.trim()
            "1m" -> "1"
            "5m" -> "5"
            "15m" -> "15"
            "30m" -> "30"
            "1h", "60m" -> "60"
            else -> "1"
        }
    }
}
