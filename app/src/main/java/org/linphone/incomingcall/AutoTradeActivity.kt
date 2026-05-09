package org.linphone.incomingcall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.switchmaterial.SwitchMaterial
import org.linphone.incomingcall.hiva.HivaGoldClient
import org.linphone.incomingcall.hiva.HivaRoomClient
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoTradeActivity : AppCompatActivity() {
    private val tag = "BT_AUTO_UI"
    private val profiles = listOf("baseline", "long_protection", "scaled_units")
    private val unitChoices = listOf(1, 2, 3, 4)
    private lateinit var textTop: TextView
    private lateinit var textSession: TextView
    private lateinit var textBacktestDay: TextView
    private lateinit var textBacktestSignals: TextView
    private lateinit var textSignal: TextView
    private lateinit var textDecision: TextView
    private lateinit var strategySpinner: Spinner
    private lateinit var unitsSpinner: Spinner
    private lateinit var testModeSwitch: SwitchMaterial
    private val moneyFormat = DecimalFormat("#,##0")
    private val uiDateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val handler = Handler(Looper.getMainLooper())
    private data class PendingStartRequest(
        val profile: String,
        val units: Int,
        val testMode: Boolean
    )
    private var pendingStartRequest: PendingStartRequest? = null
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingStartRequest
            if (granted && pending != null) {
                Log.i(
                    tag,
                    "notification permission granted, continue start profile=${pending.profile} units=${pending.units} testMode=${pending.testMode}"
                )
                AutoTradeService.start(this, pending.profile, pending.units, pending.testMode)
            } else {
                Log.w(tag, "notification permission denied, start canceled")
                Toast.makeText(
                    this,
                    "Notification permission is required to start foreground auto-trade service.",
                    Toast.LENGTH_LONG
                ).show()
            }
            pendingStartRequest = null
        }

    private val marketRefresher = object : Runnable {
        override fun run() {
            refreshPreStartMarketState()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_trade)

        textTop = findViewById(R.id.textAutoTop)
        textSession = findViewById(R.id.textAutoSession)
        textBacktestDay = findViewById(R.id.textAutoBacktestDay)
        textBacktestSignals = findViewById(R.id.textAutoBacktestSignals)
        textSignal = findViewById(R.id.textAutoSignal)
        textDecision = findViewById(R.id.textAutoDecision)
        strategySpinner = findViewById(R.id.spinnerAutoStrategy)
        unitsSpinner = findViewById(R.id.spinnerAutoUnits)
        testModeSwitch = findViewById(R.id.switchAutoTestMode)

        val app = application as IncomingCallApp
        val profileAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, profiles)
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        strategySpinner.adapter = profileAdapter
        strategySpinner.setSelection(profiles.indexOf(app.botPrefs.localProfileId).coerceAtLeast(0))
        val unitAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, unitChoices.map { it.toString() })
        unitAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        unitsSpinner.adapter = unitAdapter
        unitsSpinner.setSelection(2) // default 3 units

        findViewById<Button>(R.id.buttonStartTrade).setOnClickListener {
            val selected = strategySpinner.selectedItem?.toString().orEmpty().ifBlank { "baseline" }
            val units = unitsSpinner.selectedItem?.toString()?.toIntOrNull() ?: 3
            val testMode = testModeSwitch.isChecked
            app.botPrefs.localProfileId = selected
            Log.i(tag, "start clicked profile=$selected units=$units testMode=$testMode")
            startTradeWithNotificationPermission(selected, units, testMode)
        }
        findViewById<Button>(R.id.buttonStopTrade).setOnClickListener {
            Log.i(tag, "stop clicked closeAll=true")
            AutoTradeService.stopAndClose(this)
        }

        refreshPortfolioStateOnOpen()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AutoTradeStateStore.state.collect { state ->
                    Log.i(
                        tag,
                        "state running=${state.isRunning} profile=${state.profileId} ws=${state.wsConnected} price=${state.price} open=${state.openPositions} pending=${state.pendingOrders} decision=${state.lastDecision}"
                    )
                    bind(state)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i(tag, "onResume start pre-start market updates")
        handler.post(marketRefresher)
    }

    override fun onPause() {
        super.onPause()
        Log.i(tag, "onPause stop pre-start market updates")
        handler.removeCallbacks(marketRefresher)
    }

    private fun bind(state: AutoTradeUiState) {
        textTop.text = buildString {
            appendLine("running: ${state.isRunning}")
            appendLine("profile: ${state.profileId}")
            appendLine("ws connected: ${state.wsConnected}")
            appendLine("price: ${formatPrice(state.price)}")
            appendLine("best bid: ${formatPrice(state.bestBid)}")
            appendLine("best ask: ${formatPrice(state.bestAsk)}")
            appendLine("open position: ${state.openPositions}")
            appendLine("pending order: ${state.pendingOrders}")
            appendLine("has portfolio: ${state.hasPortfolio}")
            appendLine("portfolio id: ${state.portfolioId.ifBlank { "-" }}")
            appendLine("portfolio units: ${state.portfolioUnits}")
            append("user balance: ${formatPrice(state.userBalance)}")
        }

        val winRatePercent = state.sessionWinRate * 100.0
        textSession.text = buildString {
            appendLine("backtest session stats (since service start)")
            appendLine("trades: ${state.sessionTradeCount}")
            appendLine("pnl: ${formatSignedMoney(state.sessionNetPnl)}")
            appendLine("winrate: ${String.format(Locale.US, "%.2f", winRatePercent)}%")
            appendLine("wins/losses: ${state.sessionWinCount}/${state.sessionLossCount}")
            appendLine("orders placed: ${state.ordersPlaced}/${state.orderAttempts}")
            appendLine("last submit ok: ${state.lastSubmitOk?.toString() ?: "-"}")
            appendLine("last submit msg: ${state.lastSubmitMessage.ifBlank { "-" }}")
            appendLine("last submit at: ${formatEpochSec(state.lastSubmitAtEpochSec)}")
            appendLine("last live transaction id: ${state.lastLiveTransactionId.ifBlank { "-" }}")
            appendLine("last order id: ${state.lastOrderId.ifBlank { "-" }}")
            appendLine("last order action: ${state.lastOrderAction.ifBlank { "-" }}")
            appendLine("last order units: ${if (state.lastOrderUnits > 0) state.lastOrderUnits.toString() else "-"}")
            append("last order at: ${formatEpochSec(state.lastOrderAtEpochSec)}")
        }

        textSignal.text = buildString {
            appendLine("signal: ${state.signalType}")
            appendLine("direction: ${state.signalDirection}")
            append("reason: ${state.signalReason.ifBlank { "-" }}")
        }

        val dayWinRatePercent = state.dayBacktestWinRate * 100.0
        textBacktestDay.text = buildString {
            appendLine("backtest (last 24h)")
            appendLine("trades: ${state.dayBacktestTradeCount}")
            appendLine("pnl: ${formatSignedMoney(state.dayBacktestNetPnl)}")
            appendLine("winrate: ${String.format(Locale.US, "%.2f", dayWinRatePercent)}%")
            appendLine("wins/losses: ${state.dayBacktestWinCount}/${state.dayBacktestLossCount}")
            append("as of: ${formatEpochMs(state.updatedAtEpochMs)}")
        }

        textBacktestSignals.text = buildString {
            appendLine("signals (last 24h)")
            append(
                state.dayBacktestSignalsText.ifBlank {
                    "no closed trades in last 24h yet"
                }
            )
        }

        textDecision.text = buildString {
            appendLine("decision: ${state.lastDecision}")
            appendLine("gate trace: ${state.lastGateTrace.ifBlank { "-" }}")
            appendLine("live state: open=${state.openPositions} pending=${state.pendingOrders} freeUnits=${state.portfolioUnits}")
            if (state.lastError.isNotBlank()) {
                appendLine("error: ${state.lastError}")
            }
            append("updated: ${state.updatedAtEpochMs}")
        }
    }

    private fun formatPrice(value: Double): String {
        return if (value <= 0.0) "-" else moneyFormat.format(value)
    }

    private fun formatSignedMoney(value: Double): String {
        if (value == 0.0) return "0"
        val sign = if (value > 0) "+" else "-"
        return sign + moneyFormat.format(kotlin.math.abs(value))
    }

    private fun formatEpochMs(value: Long): String {
        if (value <= 0L) return "-"
        return uiDateTimeFormat.format(Date(value))
    }

    private fun formatEpochSec(value: Long): String {
        if (value <= 0L) return "-"
        return uiDateTimeFormat.format(Date(value * 1000L))
    }

    private fun refreshPortfolioStateOnOpen() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val port = HivaGoldClient.api.getMazanehActivePortfolio()
                    val userInfo = HivaGoldClient.api.getMazanehUserInfo()
                    port to userInfo
                }
            }
            result.onSuccess { (portfolio, userInfo) ->
                val hasPortfolio = true
                val portfolioId = portfolio.id.toString()
                val portfolioFree = portfolio.availableUnits.toInt()
                val userBalance = userInfo.doubleValue("balance", 0.0)
                AutoTradeStateStore.update {
                    it.copy(
                        hasPortfolio = hasPortfolio,
                        portfolioId = portfolioId,
                        portfolioUnits = portfolioFree,
                        userBalance = userBalance,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                }
                Log.i(
                    tag,
                    "portfolio onOpen has=$hasPortfolio id=$portfolioId free=$portfolioFree balance=$userBalance"
                )
            }.onFailure { err ->
                Log.e(tag, "portfolio onOpen failed: ${err.message}", err)
                AutoTradeStateStore.update {
                    it.copy(
                        lastError = "portfolio check failed: ${err.message}",
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                }
            }
        }
    }

    private fun startTradeWithNotificationPermission(profile: String, units: Int, testMode: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            AutoTradeService.start(this, profile, units, testMode)
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            AutoTradeService.start(this, profile, units, testMode)
            return
        }
        pendingStartRequest = PendingStartRequest(
            profile = profile,
            units = units,
            testMode = testMode
        )
        Log.i(tag, "requesting POST_NOTIFICATIONS before start")
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun refreshPreStartMarketState() {
        if (AutoTradeStateStore.state.value.isRunning) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val now = System.currentTimeMillis() / 1000L
                    val candles = HivaRoomClient.getBars("1", now - 3600L, now)
                    val fallbackPrice = candles.lastOrNull()?.close ?: 0.0
                    val txJson = HivaGoldClient.api.getMazanehTransactions(1)
                    val activeOrders = HivaGoldClient.api.getMazanehActiveOrders()
                    Triple(fallbackPrice, txJson, activeOrders)
                }
            }
            result.onSuccess { (fallbackPrice, txJson, activeOrders) ->
                val price = fallbackPrice
                val open = txJson.getAsJsonArray("results")?.filter { it.asJsonObject.stringValue("status", "") == "open" }?.size ?: 0
                val pending = activeOrders.size
                AutoTradeStateStore.update {
                    if (it.isRunning) it else it.copy(
                        wsConnected = false,
                        price = price,
                        bestBid = price,
                        bestAsk = price,
                        openPositions = open,
                        pendingOrders = pending,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                }
            }.onFailure { err ->
                Log.w(tag, "preStart market refresh failed: ${err.message}")
            }
        }
    }

    private fun JsonObject.stringValue(key: String, fallback: String): String {
        val el = get(key) ?: return fallback
        if (el.isJsonNull) return fallback
        return runCatching { el.asString }.getOrDefault(fallback)
    }

    private fun JsonObject.intValue(key: String, fallback: Int): Int {
        val el = get(key) ?: return fallback
        if (el.isJsonNull) return fallback
        return runCatching { el.asInt }.getOrDefault(fallback)
    }

    private fun JsonObject.doubleValue(key: String, fallback: Double): Double {
        val el = get(key) ?: return fallback
        if (el.isJsonNull) return fallback
        return runCatching { el.asDouble }.getOrDefault(fallback)
    }

    private fun JsonObject.boolValue(key: String, fallback: Boolean): Boolean {
        val el = get(key) ?: return fallback
        if (el.isJsonNull) return fallback
        return runCatching { el.asBoolean }.getOrDefault(fallback)
    }

    private fun JsonObject.arrayCount(vararg keys: String): Int {
        for (key in keys) {
            val el = get(key) ?: continue
            if (el.isJsonArray) return el.asJsonArray.size()
        }
        return 0
    }
}
