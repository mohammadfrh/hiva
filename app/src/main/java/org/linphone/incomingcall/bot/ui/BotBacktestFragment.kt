package org.linphone.incomingcall.bot.ui

import android.os.Bundle
import android.graphics.Color
import android.util.Base64
import androidx.core.text.HtmlCompat
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.incomingcall.R
import org.linphone.incomingcall.bot.local.LocalDataStore
import org.linphone.incomingcall.bot.local.LocalBotRuntime
import org.linphone.incomingcall.bot.prettyJson
import kotlin.math.abs
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.max

class BotBacktestFragment : Fragment(R.layout.fragment_bot_backtest) {
    private val tag = "BT_UI"
    /** Tehran — same zone as trade times in the UI. */
    private val tradeZone: ZoneId = ZoneId.of("Asia/Tehran")
    private val profiles = listOf(
        "baseline",
        "long_protection",
        "scaled_units",
        "scaled_units_long_hold"
    )
    private val localRows = mutableListOf<JsonObject>()
    private lateinit var datasetSpinner: Spinner
    private lateinit var datasetAdapter: ArrayAdapter<String>
    private lateinit var datasetOptions: MutableList<String>
    private lateinit var store: LocalDataStore

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val out = view.findViewById<TextView>(R.id.textBacktestOut)
        val allTradesTitle = view.findViewById<TextView>(R.id.textAllTradesTitle)
        datasetSpinner = view.findViewById(R.id.spinnerDataset)
        val profileSpinner = view.findViewById<Spinner>(R.id.spinnerProfile)
        val summary = view.findViewById<TextView>(R.id.textBacktestSummary)
        val run = view.findViewById<Button>(R.id.buttonRunBacktest)
        val get = view.findViewById<Button>(R.id.buttonGetBacktestResult)
        val list = view.findViewById<Button>(R.id.buttonBacktestList)
        val runAll = view.findViewById<Button>(R.id.buttonRunAllStrategies)
        store = LocalDataStore(requireContext())
        datasetOptions = mutableListOf()
        reloadDatasets()
        val tradeAdapter = BotTradeRowAdapter()
        val listAdapter = BotBacktestRowAdapter { row ->
            val dsIndex = datasetOptions.indexOf(row.dataset)
            if (dsIndex >= 0) datasetSpinner.setSelection(dsIndex)
            val idx = profiles.indexOf(row.profile)
            if (idx >= 0) profileSpinner.setSelection(idx)
            val hit = localRows.firstOrNull {
                it.get("dataset")?.asString == row.dataset && it.get("profile")?.asString == row.profile
            }
            bindTradeRows(tradeAdapter, hit?.getAsJsonArray("trades"), row.dataset, allTradesTitle)
        }
        view.findViewById<RecyclerView>(R.id.recyclerBacktestRows).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = listAdapter
        }
        view.findViewById<RecyclerView>(R.id.recyclerTradeRows).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = tradeAdapter
        }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, profiles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profileSpinner.adapter = adapter
        datasetAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, datasetOptions)
        datasetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        datasetSpinner.adapter = datasetAdapter

        run.setOnClickListener {
            val ds = datasetSpinner.selectedItem?.toString().orEmpty()
            if (ds == "NO_DATASET_DOWNLOADED") {
                out.text = "Download dataset from Data tab first."
                return@setOnClickListener
            }
            val pf = profileSpinner.selectedItem?.toString().orEmpty().ifBlank { "baseline" }
            Log.i(tag, "Run clicked dataset=$ds profile=$pf")
            Log.i(tag, "Backtest will use selected dataset=$ds")
            out.text = getString(R.string.loading)
            viewLifecycleOwner.lifecycleScope.launch {
                val res = LocalBotRuntime.marketBacktest(pf, ds, store)
                Log.i(
                    tag,
                    "Run result dataset=$ds profile=$pf trades=${res.get("trade_count")} win=${res.get("win_rate")} net=${res.get("net_profit")}"
                )
                val row = JsonObject().apply {
                    addProperty("dataset", ds)
                    addProperty("profile", pf)
                    add("trades", res.arrayValue("trades"))
                    val traceText = withContext(Dispatchers.Default) {
                        buildBacktestTraceText(ds, pf, res, store)
                    }
                    addProperty("trace_text", traceText)
                    add("summary", JsonObject().apply {
                        addProperty("trade_count", res.intValue("trade_count", 0))
                        addProperty("win_count", res.intValue("win_count", 0))
                        addProperty("loss_count", res.intValue("loss_count", 0))
                        addProperty("win_rate", res.doubleValue("win_rate", 0.0))
                        addProperty("net_profit", res.doubleValue("net_profit", 0.0))
                        addProperty("withdrawn_profit", res.doubleValue("withdrawn_profit", 0.0))
                        addProperty("ending_balance", res.doubleValue("ending_balance", 0.0))
                        addProperty("profit_factor", res.stringValue("profit_factor", "-"))
                        addProperty("max_drawdown_percent", res.doubleValue("max_drawdown_percent", 0.0))
                        addProperty("total_fees", res.doubleValue("total_fees", 0.0))
                    })
                }
                localRows.add(0, row)
                bindResultSummary(row, summary)
                bindBacktestRows(listAdapter, JsonArray().apply { localRows.forEach { add(it) } })
                bindTradeRows(tradeAdapter, row.getAsJsonArray("trades"), ds, allTradesTitle)
                val traceText = row.get("trace_text")?.asString.orEmpty()
                out.text = buildString {
                    appendLine(row.prettyJson())
                    appendLine()
                    append(traceText)
                }
            }
        }
        get.setOnClickListener {
            val ds = datasetSpinner.selectedItem?.toString().orEmpty()
            val pf = profileSpinner.selectedItem?.toString().orEmpty().ifBlank { "baseline" }
            Log.i(tag, "Get clicked dataset=$ds profile=$pf localRows=${localRows.size}")
            out.text = getString(R.string.loading)
            viewLifecycleOwner.lifecycleScope.launch {
                val hit = localRows.firstOrNull {
                    it.get("dataset")?.asString == ds && it.get("profile")?.asString == pf
                }
                if (hit != null) {
                    bindResultSummary(hit, summary)
                    bindTradeRows(tradeAdapter, hit.getAsJsonArray("trades"), ds, allTradesTitle)
                    val traceText = hit.get("trace_text")?.asString.orEmpty()
                    out.text = buildString {
                        appendLine(hit.prettyJson())
                        appendLine()
                        append(traceText)
                    }
                } else {
                    out.text = "No local result yet. Run backtest first."
                }
            }
        }
        list.setOnClickListener {
            bindBacktestRows(listAdapter, JsonArray().apply { localRows.forEach { add(it) } })
            val latest = localRows.firstOrNull()
            val latestTrades = latest?.getAsJsonArray("trades")
            val dsForList = latest?.get("dataset")?.asString ?: datasetSpinner.selectedItem?.toString().orEmpty()
            bindTradeRows(tradeAdapter, latestTrades, dsForList, allTradesTitle)
            out.text = JsonArray().apply { localRows.forEach { add(it) } }.prettyJson()
        }
        runAll.setOnClickListener {
            val ds = datasetSpinner.selectedItem?.toString().orEmpty()
            if (ds == "NO_DATASET_DOWNLOADED") {
                out.text = "Download dataset from Data tab first."
                return@setOnClickListener
            }
            Log.i(tag, "RunAll clicked dataset=$ds profiles=${profiles.joinToString(",")}")
            Log.i(tag, "RunAll will use selected dataset=$ds")
            out.text = getString(R.string.loading)
            viewLifecycleOwner.lifecycleScope.launch {
                val profilesRun = JsonArray()
                val listJson = JsonArray()
                profiles.forEach { pf ->
                    val s = LocalBotRuntime.marketBacktest(pf, ds, store)
                    Log.i(
                        tag,
                        "RunAll result dataset=$ds profile=$pf trades=${s.get("trade_count")} win=${s.get("win_rate")} net=${s.get("net_profit")}"
                    )
                    profilesRun.add(JsonObject().apply {
                        addProperty("profile", pf)
                        addProperty("trade_count", s.intValue("trade_count", 0))
                        addProperty("win_rate", s.doubleValue("win_rate", 0.0))
                        addProperty("net_profit", s.doubleValue("net_profit", 0.0))
                    })
                    val row = JsonObject().apply {
                        addProperty("dataset", ds)
                        addProperty("profile", pf)
                        add("trades", s.arrayValue("trades"))
                        val traceText = withContext(Dispatchers.Default) {
                            buildBacktestTraceText(ds, pf, s, store)
                        }
                        addProperty("trace_text", traceText)
                        add("summary", JsonObject().apply {
                            addProperty("trade_count", s.intValue("trade_count", 0))
                            addProperty("win_count", s.intValue("win_count", 0))
                            addProperty("loss_count", s.intValue("loss_count", 0))
                            addProperty("win_rate", s.doubleValue("win_rate", 0.0))
                            addProperty("net_profit", s.doubleValue("net_profit", 0.0))
                            addProperty("withdrawn_profit", s.doubleValue("withdrawn_profit", 0.0))
                            addProperty("ending_balance", s.doubleValue("ending_balance", 0.0))
                            addProperty("profit_factor", s.stringValue("profit_factor", "-"))
                            addProperty("max_drawdown_percent", s.doubleValue("max_drawdown_percent", 0.0))
                            addProperty("total_fees", s.doubleValue("total_fees", 0.0))
                        })
                    }
                    listJson.add(row)
                }
                val res = JsonObject().apply {
                    addProperty("dataset", ds)
                    add("profiles_run", profilesRun)
                }
                for (i in 0 until listJson.size()) localRows.add(0, listJson[i].asJsonObject)
                bindRunSummary(res, summary)
                bindBacktestRows(listAdapter, JsonArray().apply { localRows.forEach { add(it) } })
                val latest = localRows.firstOrNull()
                val latestTrades = latest?.getAsJsonArray("trades")
                bindTradeRows(tradeAdapter, latestTrades, ds, allTradesTitle)
                val latestTrace = localRows.firstOrNull()?.get("trace_text")?.asString.orEmpty()
                out.text = buildString {
                    appendLine(res.prettyJson())
                    if (latestTrace.isNotBlank()) {
                        appendLine()
                        append(latestTrace)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (this::datasetOptions.isInitialized) {
            reloadDatasets()
            datasetAdapter.notifyDataSetChanged()
        }
    }

    private fun reloadDatasets() {
        val selected = if (this::datasetSpinner.isInitialized) datasetSpinner.selectedItem?.toString() else null
        datasetOptions.clear()
        datasetOptions.addAll(store.listDatasets())
        if (datasetOptions.isEmpty()) datasetOptions += "NO_DATASET_DOWNLOADED"
        if (selected != null) {
            val idx = datasetOptions.indexOf(selected)
            if (idx >= 0 && this::datasetSpinner.isInitialized) datasetSpinner.setSelection(idx)
        }
    }

    private fun bindRunSummary(res: JsonObject, summary: TextView) {
        val dataset = res.get("dataset")?.asString ?: "-"
        val profilesRun = res.getAsJsonArray("profiles_run")
        if (profilesRun == null || profilesRun.size() == 0) {
            summary.text = "dataset: $dataset\nprofiles_run: 0"
            return
        }
        val details = buildString {
            appendLine("dataset: $dataset")
            appendLine("profiles_run: ${profilesRun.size()}")
            for (i in 0 until profilesRun.size()) {
                val row = profilesRun[i].asJsonObject
                val profile = row.get("profile")?.asString ?: "-"
                val trades = row.get("trade_count")?.asString ?: "0"
                val win = row.get("win_rate")?.asString ?: "0"
                val net = row.get("net_profit")?.asDouble ?: 0.0
                appendLine("• $profile | trades:$trades | win:$win | net:${formatCompactMoney(net, withSign = true)}")
            }
        }
        summary.text = details.trim()
    }

    private fun bindResultSummary(res: JsonObject, summary: TextView) {
        val s = res.getAsJsonObject("summary")
        if (s == null) {
            summary.text = "-"
            return
        }
        val trades = s.get("trade_count")?.asInt ?: 0
        val wins = s.get("win_count")?.asInt ?: 0
        val losses = s.get("loss_count")?.asInt ?: 0
        val winRate = s.get("win_rate")?.asDouble ?: 0.0
        val net = s.get("net_profit")?.asDouble ?: 0.0
        val withdrawn = s.get("withdrawn_profit")?.asDouble ?: 0.0
        val fees = s.get("total_fees")?.asDouble ?: 0.0
        val end = s.get("ending_balance")?.asDouble ?: 0.0
        val pf = s.get("profit_factor")?.asString ?: "-"
        val dd = (s.get("max_drawdown_percent")?.asDouble ?: 0.0) * 100.0
        val netColor = if (net >= 0) "#4CAF50" else "#EF5350"
        val datasetId = res.get("dataset")?.asString.orEmpty()
        val allTradesArr = res.arrayValue("trades")
        val filteredArr = filterTradesForDatasetCalendar(allTradesArr, datasetId)
        val filteredCount = filteredArr.size()
        val filteredNet = sumNetPnlMoney(filteredArr)
        val filteredNetColor = if (filteredNet >= 0) "#4CAF50" else "#EF5350"
        val calendarWindowBlock = if (datasetHasEntryDateFilter(datasetId)) {
            val mismatchHint = if (filteredCount != allTradesArr.size()) {
                "<br/><small>Full-run metrics above include all ${allTradesArr.size()} trades; " +
                    "window = entry date in dataset id (same as trade list).</small>"
            } else {
                ""
            }
            """<br/><b>Net (entries in calendar window):</b> <font color="$filteredNetColor">${
                formatCompactMoney(filteredNet, withSign = true)
            }</font> &nbsp;($filteredCount trades)$mismatchHint"""
        } else {
            ""
        }
        val html = """
            <b>Total trades:</b> $trades<br/>
            <b>Win rate:</b> ${formatPercent(winRate)}<br/>
            <b>Net profit:</b> <font color="$netColor">${formatCompactMoney(net, withSign = true)}</font> <small>(full simulation)</small><br/>
            <b>Total fees:</b> ${formatCompactMoney(fees, withSign = false)}<br/>
            <b>Withdrawn:</b> ${formatCompactMoney(withdrawn, withSign = false)}<br/>
            <b>End balance:</b> ${formatCompactMoney(end, withSign = false)}<br/>
            <b>Wins:</b> <font color="#4CAF50">$wins</font> &nbsp;&nbsp; <b>Losses:</b> <font color="#EF5350">$losses</font><br/>
            <b>Profit factor:</b> $pf &nbsp;&nbsp; <b>Max drawdown:</b> ${String.format(Locale.US, "%.1f%%", dd)}
            $calendarWindowBlock
        """.trimIndent()
        summary.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    private fun bindBacktestRows(adapter: BotBacktestRowAdapter, arr: JsonArray) {
        val rows = mutableListOf<BotBacktestRow>()
        for (i in 0 until arr.size()) {
            val e = arr[i]
            if (!e.isJsonObject) continue
            val o = e.asJsonObject
            val dataset = o.get("dataset")?.asString ?: continue
            val profile = o.get("profile")?.asString ?: o.get("profile_id")?.asString ?: "baseline"
            val summary = o.getAsJsonObject("summary")
            val subtitle = if (summary != null) {
                val trades = summary.get("trade_count")?.asInt ?: 0
                val net = summary.get("net_profit")?.asDouble ?: 0.0
                "trades:$trades | net:${formatCompactMoney(net, withSign = true)}"
            } else {
                "ready"
            }
            rows += BotBacktestRow(dataset, profile, subtitle)
        }
        adapter.submitList(rows)
    }

    /** Calendar window for the dataset id (entry time in [tradeZone]). Unmatched ids → no filtering. */
    private fun datasetEntryDatePredicate(datasetId: String): (LocalDate) -> Boolean {
        Regex("^([A-Za-z]+)-day-(\\d{4}-\\d{2}-\\d{2})$").matchEntire(datasetId)?.let {
            val d = LocalDate.parse(it.groupValues[2])
            return { ld -> ld == d }
        }
        Regex("^C-day-(\\d{4}-\\d{2}-\\d{2})$").matchEntire(datasetId)?.let {
            val d = LocalDate.parse(it.groupValues[1])
            return { ld -> ld == d }
        }
        Regex("^([A-Za-z]+)-month-(\\d{4}-\\d{2})$").matchEntire(datasetId)?.let {
            val ym = YearMonth.parse(it.groupValues[2])
            return { ld -> YearMonth.from(ld) == ym }
        }
        Regex("^([A-Za-z]+)-date-(\\d{4}-\\d{2}-\\d{2})_(\\d{4}-\\d{2}-\\d{2})$").matchEntire(datasetId)?.let {
            val start = LocalDate.parse(it.groupValues[2])
            val end = LocalDate.parse(it.groupValues[3])
            return { ld -> !ld.isBefore(start) && !ld.isAfter(end) }
        }
        return { true }
    }

    private fun datasetHasEntryDateFilter(datasetId: String): Boolean = when {
        datasetId.isBlank() || datasetId == "NO_DATASET_DOWNLOADED" -> false
        Regex("^([A-Za-z]+)-day-(\\d{4}-\\d{2}-\\d{2})$").matches(datasetId) -> true
        Regex("^C-day-(\\d{4}-\\d{2}-\\d{2})$").matches(datasetId) -> true
        Regex("^([A-Za-z]+)-month-(\\d{4}-\\d{2})$").matches(datasetId) -> true
        Regex("^([A-Za-z]+)-date-(\\d{4}-\\d{2}-\\d{2})_(\\d{4}-\\d{2}-\\d{2})$").matches(datasetId) -> true
        else -> false
    }

    private fun filterTradesForDatasetCalendar(arr: JsonArray, datasetId: String): JsonArray {
        val pred = datasetEntryDatePredicate(datasetId)
        val out = JsonArray()
        for (i in 0 until arr.size()) {
            val e = arr[i]
            if (!e.isJsonObject) continue
            val t = e.asJsonObject
            val entryEpoch = t.get("entry_time")?.asLong ?: t.get("entryTime")?.asLong ?: 0L
            if (entryEpoch <= 0L) continue
            val ld = Instant.ofEpochSecond(entryEpoch).atZone(tradeZone).toLocalDate()
            if (pred(ld)) out.add(t)
        }
        return out
    }

    private fun sumNetPnlMoney(trades: JsonArray): Double {
        var sum = 0.0
        for (i in 0 until trades.size()) {
            val e = trades[i]
            if (!e.isJsonObject) continue
            val t = e.asJsonObject
            sum += t.doubleValue("net_pnl_money", t.doubleValue("netPnlMoney", 0.0))
        }
        return sum
    }

    private fun bindTradeRows(
        adapter: BotTradeRowAdapter,
        arr: JsonArray?,
        datasetId: String,
        titleView: TextView? = null
    ) {
        if (arr == null) {
            titleView?.text = getString(R.string.bot_all_trades_title)
            adapter.submitList(emptyList())
            return
        }
        val filtered = filterTradesForDatasetCalendar(arr, datasetId)
        if (titleView != null) {
            val full = arr.size()
            val f = filtered.size()
            titleView.text = when {
                !datasetHasEntryDateFilter(datasetId) || f == full ->
                    getString(R.string.bot_all_trades_title)
                else ->
                    getString(R.string.bot_trades_calendar_filtered, f, full)
            }
        }
        val out = mutableListOf<BotTradeRow>()
        for (i in 0 until filtered.size()) {
            val e = filtered[i]
            if (!e.isJsonObject) continue
            val t = e.asJsonObject
            val entryEpoch = t.get("entry_time")?.asLong ?: t.get("entryTime")?.asLong ?: 0L
            val exitEpoch = t.get("exit_time")?.asLong ?: t.get("exitTime")?.asLong ?: 0L
            val pnl = t.get("net_pnl_money")?.asDouble ?: t.get("netPnlMoney")?.asDouble ?: 0.0
            val pnlColor = if (pnl >= 0) Color.parseColor("#4CAF50") else Color.parseColor("#EF5350")
            out += BotTradeRow(
                index = i + 1,
                direction = t.get("direction")?.asString ?: "-",
                entryTime = formatEpoch(entryEpoch),
                exitTime = formatEpoch(exitEpoch),
                entryPrice = String.format(Locale.US, "%,.0f", t.get("entry_price")?.asDouble ?: t.get("entryPrice")?.asDouble ?: 0.0),
                exitPrice = String.format(Locale.US, "%,.0f", t.get("exit_price")?.asDouble ?: t.get("exitPrice")?.asDouble ?: 0.0),
                pnl = formatCompactMoney(pnl, withSign = true),
                pnlColor = pnlColor,
                reason = t.get("exit_reason")?.asString ?: t.get("exitReason")?.asString ?: "-",
                score = (t.get("setup_score")?.asInt ?: t.get("setupScore")?.asInt ?: 0).toString()
            )
        }
        adapter.submitList(out)
    }

    private fun formatPercent(value: Double): String =
        String.format(Locale.US, "%.1f%%", value * 100.0)

    private fun formatCompactMoney(value: Double, withSign: Boolean): String {
        val absV = abs(value)
        val body = when {
            absV >= 1_000_000.0 -> String.format(Locale.US, "%.1fM", absV / 1_000_000.0)
            absV >= 1_000.0 -> String.format(Locale.US, "%.0fK", absV / 1_000.0)
            else -> String.format(Locale.US, "%.0f", absV)
        }
        val sign = when {
            !withSign -> ""
            value > 0 -> "+"
            value < 0 -> "-"
            else -> ""
        }
        return "$sign$body"
    }

    private fun formatEpoch(epochSec: Long): String {
        if (epochSec <= 0L) return "-"
        val fmt = DateTimeFormatter.ofPattern("MMM dd HH:mm:ss", Locale.US)
        return Instant.ofEpochSecond(epochSec).atZone(tradeZone).format(fmt)
    }

    private fun formatPrice(value: Double): String {
        if (value <= 0.0) return "-"
        return String.format(Locale.US, "%,.0f", value)
    }

    private suspend fun buildBacktestTraceText(
        dataset: String,
        profile: String,
        result: JsonObject,
        store: LocalDataStore
    ): String {
        val trades = result.arrayValue("trades")
        val tradeCount = result.intValue("trade_count", trades.size())
        val winCount = result.intValue("win_count", 0)
        val lossCount = result.intValue("loss_count", 0)
        val winRate = result.doubleValue("win_rate", 0.0)
        val net = result.doubleValue("net_profit", 0.0)
        val fees = result.doubleValue("total_fees", 0.0)

        val firstEntry = if (trades.size() > 0) {
            trades[0].asJsonObject.longValue("entry_time", trades[0].asJsonObject.longValue("entryTime", 0L))
        } else 0L
        val lastExit = if (trades.size() > 0) {
            val last = trades[trades.size() - 1].asJsonObject
            last.longValue("exit_time", last.longValue("exitTime", 0L))
        } else 0L

        val trace = buildString {
            appendLine("=== BACKTEST TRACE ===")
            appendLine("dataset=$dataset | profile=$profile")
            appendLine("trades=$tradeCount wins=$winCount losses=$lossCount winRate=${formatPercent(winRate)} net=${formatCompactMoney(net, withSign = true)} fees=${formatCompactMoney(fees, withSign = false)}")
            appendLine("firstEntry=${formatEpoch(firstEntry)} lastExit=${formatEpoch(lastExit)}")
            appendLine("--- TRADE ROWS ---")
            if (trades.size() == 0) {
                appendLine("no trades")
            } else {
                val startIndex = max(0, trades.size() - 200)
                for (i in startIndex until trades.size()) {
                    val t = trades[i].asJsonObject
                    val idx = i + 1
                    val dir = t.stringValue("direction", "-")
                    val entryTime = formatEpoch(t.longValue("entry_time", t.longValue("entryTime", 0L)))
                    val exitTime = formatEpoch(t.longValue("exit_time", t.longValue("exitTime", 0L)))
                    val entry = t.doubleValue("entry_price", t.doubleValue("entryPrice", 0.0))
                    val exit = t.doubleValue("exit_price", t.doubleValue("exitPrice", 0.0))
                    val sl = t.doubleValue("stop_loss", t.doubleValue("stopLoss", 0.0))
                    val tp = t.doubleValue("take_profit", t.doubleValue("takeProfit", 0.0))
                    val slStr = if (sl > 0.0) formatPrice(sl) else "-"
                    val tpStr = if (tp > 0.0) formatPrice(tp) else "-"
                    val pnlPoints = t.stringValue("pnl_price_points", t.stringValue("pnlPricePoints", "-"))
                    val netMoney = t.doubleValue("net_pnl_money", t.doubleValue("netPnlMoney", 0.0))
                    val reason = t.stringValue("exit_reason", t.stringValue("exitReason", "-"))
                    val score = t.stringValue("setup_score", t.stringValue("setupScore", "-"))
                    appendLine(
                        "#$idx | $dir | $entryTime -> $exitTime | e=${formatPrice(entry)} x=${formatPrice(exit)} | sl=$slStr tp=$tpStr | p=$pnlPoints | net=${
                            formatCompactMoney(
                                netMoney,
                                withSign = true
                            )
                        } | score=$score | reason=$reason"
                    )
                }
            }
            appendLine("--- AUTO TRADE CANDLE REPLAY ---")
            append(buildAutoTradeReplayTrace(dataset, profile, trades, store))
            appendLine()
            append("=== END TRACE ===")
        }

        trace.lineSequence().forEach { line ->
            Log.i(tag, "BT_TRACE $line")
        }
        if (profile == "scaled_units_long_hold") {
            logDatasetDumpForTuning(dataset, store)
        }
        return trace
    }

    private suspend fun logDatasetDumpForTuning(dataset: String, store: LocalDataStore) {
        val payload = JsonObject().apply {
            addProperty("dataset", dataset)
            add("1m", candlesToJsonArray(store.loadDatasetCandles(dataset, "1")))
            add("5m", candlesToJsonArray(store.loadDatasetCandles(dataset, "5")))
            add("15m", candlesToJsonArray(store.loadDatasetCandles(dataset, "15")))
            add("30m", candlesToJsonArray(store.loadDatasetCandles(dataset, "30")))
            add("60m", candlesToJsonArray(store.loadDatasetCandles(dataset, "60")))
        }.toString()
        val compressed = ByteArrayOutputStream().use { bytes ->
            GZIPOutputStream(bytes).use { gzip ->
                gzip.write(payload.toByteArray(Charsets.UTF_8))
            }
            bytes.toByteArray()
        }
        val encoded = Base64.encodeToString(compressed, Base64.NO_WRAP)
        val chunkSize = 3000
        val totalChunks = (encoded.length + chunkSize - 1) / chunkSize
        Log.i(
            tag,
            "BT_DATA_DUMP BEGIN dataset=$dataset encoding=gzip+base64 chunks=$totalChunks chars=${encoded.length}"
        )
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, encoded.length)
            Log.i(tag, "BT_DATA_DUMP chunk=${i + 1}/$totalChunks ${encoded.substring(start, end)}")
        }
        Log.i(tag, "BT_DATA_DUMP END dataset=$dataset")
    }

    private fun candlesToJsonArray(candles: List<org.linphone.incomingcall.bot.local.LocalCandle>): JsonArray {
        val arr = JsonArray()
        candles.forEach { candle ->
            arr.add(JsonObject().apply {
                addProperty("time", candle.time)
                addProperty("open", candle.open)
                addProperty("high", candle.high)
                addProperty("low", candle.low)
                addProperty("close", candle.close)
            })
        }
        return arr
    }

    private suspend fun buildAutoTradeReplayTrace(
        dataset: String,
        profile: String,
        trades: JsonArray,
        store: LocalDataStore
    ): String {
        val candles1m = store.loadDatasetCandles(dataset, "1")
        if (candles1m.isEmpty()) {
            return "replay=unavailable reason=empty_1m_dataset"
        }
        val mtfFull = mapOf(
            5 to store.loadDatasetCandles(dataset, "5"),
            15 to store.loadDatasetCandles(dataset, "15"),
            30 to store.loadDatasetCandles(dataset, "30"),
            60 to store.loadDatasetCandles(dataset, "60")
        )

        val tradeEntryTimes = mutableSetOf<Long>()
        for (i in 0 until trades.size()) {
            val tr = trades[i].asJsonObject
            val entry = tr.longValue("entry_time", tr.longValue("entryTime", 0L))
            if (entry > 0L) tradeEntryTimes += entry
        }
        val interestingTimes = mutableSetOf<Long>()
        if (tradeEntryTimes.isNotEmpty()) {
            interestingTimes += tradeEntryTimes
            tradeEntryTimes.forEach { t ->
                interestingTimes += (t - 60L)
                interestingTimes += (t + 60L)
            }
        }

        val lines = mutableListOf<String>()
        lines += "replay dataset=$dataset profile=$profile candles1m=${candles1m.size} mtf=5m:${mtfFull[5]?.size ?: 0},15m:${mtfFull[15]?.size ?: 0},30m:${mtfFull[30]?.size ?: 0},60m:${mtfFull[60]?.size ?: 0}"

        val indexByTime = candles1m.mapIndexed { index, candle -> candle.time to index }.toMap()
        val targetIndices = sortedSetOf<Int>()
        for (entry in tradeEntryTimes) {
            val center = indexByTime[entry] ?: continue
            for (delta in -2..2) {
                val idx = center + delta
                if (idx in candles1m.indices) targetIndices += idx
            }
        }
        if (targetIndices.isEmpty()) {
            // Fallback: inspect the last few candles if trade times are missing.
            val start = (candles1m.size - 8).coerceAtLeast(0)
            for (i in start until candles1m.size) targetIndices += i
        }

        var signalCount = 0
        var inspected = 0
        for (idx in targetIndices) {
            val closed = candles1m[idx]
            val visible1m = candles1m.subList(0, idx + 1)
            val mtfVisible = mtfFull.mapValues { (_, rows) ->
                rows.filter { it.time <= closed.time }
            }
            val signal = LocalBotRuntime.marketSignalFromCandles(
                profileId = profile,
                candles = visible1m,
                preloadedMtf = mtfVisible
            )
            val signalType = signal.stringValue("type", "no_signal")
            val signalReason = signal.stringValue("reason", signal.stringValue("exit_reason", "-")).ifBlank { "-" }
            val signalDir = signal.stringValue("direction", "-").ifBlank { "-" }
            val marker = when {
                tradeEntryTimes.contains(closed.time) -> "ENTRY_CANDLE"
                interestingTimes.contains(closed.time) -> "NEAR_ENTRY"
                else -> ""
            }
            inspected += 1
            if (signalType == "signal") signalCount += 1
            lines += "${formatEpoch(closed.time)} | candle=${closed.time} | type=$signalType dir=$signalDir reason=$signalReason ${if (marker.isBlank()) "" else "| $marker"}".trim()
        }
        lines += "replay_summary inspected=$inspected signal_count=$signalCount entry_candles=${tradeEntryTimes.size}"
        return lines.joinToString(separator = "\n")
    }

    private fun JsonObject.stringValue(key: String, fallback: String): String {
        val e: JsonElement = get(key) ?: return fallback
        return if (e.isJsonNull) fallback else e.asString
    }

    private fun JsonObject.intValue(key: String, fallback: Int): Int {
        val e: JsonElement = get(key) ?: return fallback
        return if (e.isJsonNull) fallback else e.asInt
    }

    private fun JsonObject.doubleValue(key: String, fallback: Double): Double {
        val e: JsonElement = get(key) ?: return fallback
        return if (e.isJsonNull) fallback else e.asDouble
    }

    private fun JsonObject.arrayValue(key: String): JsonArray {
        val e: JsonElement = get(key) ?: return JsonArray()
        if (e.isJsonNull || !e.isJsonArray) return JsonArray()
        return e.asJsonArray
    }

    private fun JsonObject.longValue(key: String, fallback: Long): Long {
        val e: JsonElement = get(key) ?: return fallback
        return if (e.isJsonNull) fallback else runCatching { e.asLong }.getOrDefault(fallback)
    }

}
