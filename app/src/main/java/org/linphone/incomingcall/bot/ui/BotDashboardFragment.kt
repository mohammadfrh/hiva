package org.linphone.incomingcall.bot.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.JsonObject
import kotlinx.coroutines.launch
import org.linphone.incomingcall.IncomingCallApp
import org.linphone.incomingcall.R
import org.linphone.incomingcall.bot.local.LocalBotRuntime
import org.linphone.incomingcall.bot.prettyJson

class BotDashboardFragment : Fragment(R.layout.fragment_bot_dashboard) {
    private val tag = "BT_DASH_UI"
    private val handler = Handler(Looper.getMainLooper())
    private var isRefreshScheduled = false
    private var isLoading = false
    private var outRef: TextView? = null
    private var healthRef: TextView? = null
    private var signalTypeRef: TextView? = null
    private var todayTradesRef: TextView? = null
    private var todayNetPnlRef: TextView? = null
    private var profileRef: TextView? = null
    private var autoTradeRef: TextView? = null
    private var recentSignalsRef: RecyclerView? = null
    private val signalAdapter = BotSignalAdapter()
    private val signalLogAdapter = BotTodaySignalLogAdapter()
    private val refresher = object : Runnable {
        override fun run() {
            outRef?.let { loadDashboard(it) }
            if (isRefreshScheduled) {
                handler.postDelayed(this, 5000)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val out = view.findViewById<TextView>(R.id.textDashboardOut)
        healthRef = view.findViewById(R.id.textHealth)
        signalTypeRef = view.findViewById(R.id.textSignalType)
        todayTradesRef = view.findViewById(R.id.textTodayTrades)
        todayNetPnlRef = view.findViewById(R.id.textTodayNetPnl)
        profileRef = view.findViewById(R.id.textDashboardProfile)
        autoTradeRef = view.findViewById(R.id.textDashboardAutoTrade)
        recentSignalsRef = view.findViewById<RecyclerView>(R.id.recyclerRecentSignals).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = signalAdapter
        }
        view.findViewById<RecyclerView>(R.id.recyclerSignalLog).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = signalLogAdapter
        }
        outRef = out
        val reload = view.findViewById<Button>(R.id.buttonReloadDashboard)

        reload.setOnClickListener { loadDashboard(out) }
        loadDashboard(out)
    }

    override fun onResume() {
        super.onResume()
        if (!isRefreshScheduled) {
            isRefreshScheduled = true
            handler.postDelayed(refresher, 5000)
        }
    }

    override fun onPause() {
        super.onPause()
        isRefreshScheduled = false
        handler.removeCallbacks(refresher)
    }

    private fun loadDashboard(out: TextView) {
        if (isLoading) {
            Log.d(tag, "dashboard load skipped: request already running")
            return
        }
        isLoading = true
        val app = requireActivity().application as IncomingCallApp
        val profileId = app.botPrefs.localProfileId
        out.text = getString(R.string.loading)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val merged: JsonObject = LocalBotRuntime.dashboard(profileId)
                Log.i(
                    tag,
                    "dashboard loaded profile=$profileId health=${merged.getAsJsonObject("health") != null} signal=${merged.getAsJsonObject("signal") != null} todaySummary=${merged.getAsJsonObject("today_summary") != null} todaySignals=${merged.getAsJsonArray("today_signals")?.size() ?: 0}"
                )
                bindSummary(merged)
                out.text = merged.prettyJson()
            } catch (e: Exception) {
                Log.e(tag, "dashboard load failed profile=$profileId error=${e.message}", e)
                out.text = e.message ?: getString(R.string.network_error)
            } finally {
                isLoading = false
            }
        }
    }

    private fun bindSummary(merged: JsonObject) {
        val health = merged.getAsJsonObject("health")
        val signal = merged.getAsJsonObject("signal")
        val summary = merged.getAsJsonObject("today_summary")
        val signals = merged.getAsJsonArray("today_signals")
        val config = merged.getAsJsonObject("config")

        val healthStatus = health.stringValue("status", "unknown")
        val signalType = signal.stringValue("type", "no_signal")
        val todayTrades = summary.intValue("closed_trade_count", 0).toString()
        val todayNet = summary.doubleValue("net_pnl_money", 0.0).toString()
        val profile = config.stringValue("profile_id", "baseline")
        val autoTrade = config.stringValue("auto_trade", "false")

        healthRef?.text = healthStatus
        signalTypeRef?.text = signalType
        todayTradesRef?.text = todayTrades
        todayNetPnlRef?.text = todayNet
        profileRef?.text = profile
        autoTradeRef?.text = autoTrade
        Log.i(
            tag,
            "dashboard bind health=$healthStatus signal=$signalType trades=$todayTrades net=$todayNet profile=$profile auto=$autoTrade signals=${signals?.size() ?: 0}"
        )

        val rows = mutableListOf<BotSignalRow>()
        val logRows = mutableListOf<BotTodaySignalLogRow>()
        if (signals != null && signals.size() > 0) {
            val total = minOf(5, signals.size())
            for (i in 0 until total) {
                val row = signals.get(i).asJsonObject
                rows += BotSignalRow(
                    type = row.stringValue("signal_type", "-"),
                    direction = row.stringValue("direction", "-"),
                    pnl = "pnl: " + row.stringValue("pnl_points", "-")
                )
            }
            for (i in signals.size() - 1 downTo 0) {
                val row = signals.get(i).asJsonObject
                val time = row.stringValue("recorded_at", "--:--:--").takeLast(8)
                val type = row.stringValue("signal_type", "-")
                val dir = row.stringValue("direction", "-")
                val entry = row.stringValue("entry_price", "-")
                val exit = row.stringValue("exit_price", "-")
                val pnl = row.stringValue("pnl_points", "-")
                val reason = row.stringValue("exit_reason", "-")
                Log.i(
                    tag,
                    "dashboard row[$i] time=$time type=$type dir=$dir entry=$entry exit=$exit pnl=$pnl reason=$reason"
                )
                logRows += BotTodaySignalLogRow(
                    title = "$time | $type | $dir | pnl:$pnl",
                    subtitle = "entry:$entry | exit:$exit | reason:$reason"
                )
            }
        }
        signalAdapter.submitList(rows)
        signalLogAdapter.submitList(logRows)
    }

    private fun JsonObject?.stringValue(key: String, fallback: String): String {
        val obj = this ?: return fallback
        val e = obj.get(key) ?: return fallback
        if (e.isJsonNull) return fallback
        return runCatching { e.asString }.getOrDefault(fallback)
    }

    private fun JsonObject?.intValue(key: String, fallback: Int): Int {
        val obj = this ?: return fallback
        val e = obj.get(key) ?: return fallback
        if (e.isJsonNull) return fallback
        return runCatching { e.asInt }.getOrDefault(fallback)
    }

    private fun JsonObject?.doubleValue(key: String, fallback: Double): Double {
        val obj = this ?: return fallback
        val e = obj.get(key) ?: return fallback
        if (e.isJsonNull) return fallback
        return runCatching { e.asDouble }.getOrDefault(fallback)
    }
}
