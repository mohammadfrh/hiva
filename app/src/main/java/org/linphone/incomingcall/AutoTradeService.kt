package org.linphone.incomingcall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.linphone.incomingcall.bot.local.LocalCandle
import org.linphone.incomingcall.bot.local.LocalBotRuntime
import org.linphone.incomingcall.hiva.HivaGoldClient
import org.linphone.incomingcall.hiva.HivaPriceSocketClient
import org.linphone.incomingcall.hiva.HivaRoomClient
import org.linphone.incomingcall.hiva.MazanehPortfolio
import org.linphone.incomingcall.hiva.MazanehStatus
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import retrofit2.HttpException

class AutoTradeService : Service() {
    companion object {
        const val ACTION_START = "org.linphone.incomingcall.autotrade.START"
        const val ACTION_STOP = "org.linphone.incomingcall.autotrade.STOP"
        const val ACTION_STOP_AND_CLOSE = "org.linphone.incomingcall.autotrade.STOP_AND_CLOSE"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_UNITS = "extra_units"
        const val EXTRA_TEST_MODE = "extra_test_mode"

        private const val TAG = "BT_AUTO_SERVICE"
        private const val CHANNEL_ID = "auto_trade_channel"
        private const val NOTIFICATION_ID = 1042
        private const val LOOP_INTERVAL_MS = 30_000L
        private const val MIN_ORDER_RATE_LIMIT_COOLDOWN_SEC = 1L
        private const val MAX_ORDER_RATE_LIMIT_COOLDOWN_SEC = 60L
        private const val MIN_CLOSE_RATE_LIMIT_COOLDOWN_SEC = 1L
        private const val MAX_CLOSE_RATE_LIMIT_COOLDOWN_SEC = 60L
        private const val ORDER_SUBMIT_DELAY_MS = 1_500L
        private const val FAST_RECHECK_INTERVAL_MS = 6_000L
        private const val FAST_RECHECK_WINDOW_SEC = 120L
        private const val FAST_RECHECK_TX_REFRESH_SEC = 12L
        private const val FAST_RECHECK_ORDERS_REFRESH_SEC = 12L
        private const val USER_INFO_REFRESH_SEC = 180L
        private const val PORTFOLIO_REFRESH_SEC = 180L
        private const val IO_TIMEOUT_MS = 8_000L
        private const val CYCLE_WARN_MS = 8_000L
        private const val CANDLE_CLOSE_GRACE_MS = 1_200L
        private const val CLOSED_CANDLE_STABILIZATION_MS = 4_000L
        private const val CLOSED_CANDLE_SIGNATURE_CONFIRMATIONS = 1
        private const val CLOSED_CANDLE_CONFIRMATION_POLL_MS = 2_000L
        private const val MIN_ADAPTIVE_WAIT_MS = 1_000L
        private const val MAX_ADAPTIVE_WAIT_MS = 65_000L
        private const val SERVER_CLOCK_DELTA_MIN_SEC = -15L
        private const val SERVER_CLOCK_DELTA_MAX_SEC = 180L
        private const val DUPLICATE_ORDER_COOLDOWN_SEC = 60L
        private const val PORTFOLIO_UNIT_MONEY = 4_600_000
        private const val LINE_VALUE_PER_KHAT = 23_000
        private const val DAY_WINDOW_SEC = 24 * 3600L
        private const val WARMUP_WINDOW_SEC = 24 * 3600L
        private const val DELTA_OVERLAP_1M_SEC = 5 * 60L
        private const val TEST_SIGNAL_DELAY_SEC = 60L
        private const val TEST_SIGNAL_TP_SL_POINTS = 10.0
        private const val TEST_RISK_EDIT_DELAY_MS = 10_000L
        private const val TEST_RISK_EDIT_OFFSET_POINTS = 1
        private const val WS_TX_WATCH_ATTEMPTS = 8
        private const val WS_TX_WATCH_DELAY_MS = 1_500L
        private const val MAX_BACKTEST_SIGNAL_ROWS = 25
        private const val MAX_BACKTEST_LOG_ROWS = 8
        private const val MAX_PERSISTED_GATE_TRACE_ROWS = 1000
        private const val MAX_PERSISTED_SIGNAL_INPUT_ROWS = 600
        private const val MAX_PERSISTED_AUDIT_DEBUG_ROWS = 1200
        private const val MAX_PERSISTED_BARS_FETCH_TRACE_ROWS = 2000
        private const val MAX_PERSISTED_CANDLE_REVISION_ROWS = 1200
        private const val MAX_PERSISTED_POSITION_UPDATE_PROMOTE_ROWS = 1200
        /** Bump when you want a clean prefs file on device (old XML left unused under shared_prefs). */
        private const val PREFS_NAME = "auto_trade_service_prefs_v34"
        private const val PREFS_NAME2 = "auto_trade_service_signal_payload_prefs_v34"
        private const val PREFS_NAME3 = "auto_trade_service_socket_prefs_v34"
        private val MTF_RESOLUTIONS = listOf(5, 15, 30, 60)

        fun start(context: Context, profileId: String, units: Int, testMode: Boolean) {
            val i = Intent(context, AutoTradeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROFILE_ID, profileId)
                putExtra(EXTRA_UNITS, units)
                putExtra(EXTRA_TEST_MODE, testMode)
            }
            Log.i(TAG, "dispatch startForegroundService profile=$profileId units=$units testMode=$testMode")
            runCatching {
                context.startForegroundService(i)
            }.onFailure { err ->
                Log.e(TAG, "startForegroundService failed, fallback startService: ${err.message}", err)
                runCatching { context.startService(i) }.onFailure { e2 ->
                    Log.e(TAG, "startService fallback failed: ${e2.message}", e2)
                }
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, AutoTradeService::class.java).apply { action = ACTION_STOP }
            Log.i(TAG, "dispatch stop action")
            context.startService(i)
        }

        fun stopAndClose(context: Context) {
            val i = Intent(context, AutoTradeService::class.java).apply { action = ACTION_STOP_AND_CLOSE }
            Log.i(TAG, "dispatch stopAndClose action")
            context.startService(i)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cycleMutex = Mutex()
    private var loopJob: Job? = null

    private var sessionStartEpochSec: Long = 0L
    private var lastSignalFingerprint: String = ""
    private var lastOrderSentAtEpochSec: Long = 0L
    private var orderRateLimitUntilEpochSec: Long = 0L
    private var consecutiveOrderRateLimitHits: Int = 0
    private var closeRateLimitUntilEpochSec: Long = 0L
    private var consecutiveCloseRateLimitHits: Int = 0
    private var orderAttempts: Int = 0
    private var ordersPlaced: Int = 0
    private var activeProfileId: String = "baseline"
    private var activeUnits: Int = 3
    private var testModeEnabled: Boolean = false
    private var testSignalInjected: Boolean = false
    private var testRiskEditDispatched: Boolean = false
    private var testRiskEditJob: Job? = null
    private val lastEditedStopLoss = mutableMapOf<String, Int>()
    private val lastEditedTakeProfit = mutableMapOf<String, Int>()
    private var lastProcessedCandleTimeSec: Long = 0L
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val prefs2 by lazy { getSharedPreferences(PREFS_NAME2, Context.MODE_PRIVATE) }
    private val prefs3 by lazy { getSharedPreferences(PREFS_NAME3, Context.MODE_PRIVATE) }
    private val marketWsClient by lazy { HivaPriceSocketClient(HivaGoldClient.okHttpClient, "mazaneh") }
    private val executedEntryKeys = mutableSetOf<String>()
    private val executedCloseKeys = mutableSetOf<String>()
    private var cacheReady: Boolean = false
    private val cache1m = mutableListOf<LocalCandle>()
    private val cacheMtf = mutableMapOf<Int, MutableList<LocalCandle>>()
    private var lastCacheSyncEpochSec: Long = 0L
    private var cachedTransactions: JsonObject? = null
    private var cachedActiveOrders: List<JsonObject> = emptyList()
    private var lastTransactionsFetchEpochSec: Long = 0L
    private var lastActiveOrdersFetchEpochSec: Long = 0L
    private var cachedUserInfo: JsonObject = JsonObject()
    private var cachedPortfolio: MazanehPortfolio? = null
    private var lastUserInfoFetchEpochSec: Long = 0L
    private var lastPortfolioFetchEpochSec: Long = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(
            TAG,
            "onStartCommand action=${intent?.action ?: "null"} profileExtra=${intent?.getStringExtra(EXTRA_PROFILE_ID)} unitsExtra=${intent?.getIntExtra(EXTRA_UNITS, -1)} testMode=${intent?.getBooleanExtra(EXTRA_TEST_MODE, false)}"
        )
        when (intent?.action) {
            ACTION_STOP -> stopTrading(closeAll = false)
            ACTION_STOP_AND_CLOSE -> stopTrading(closeAll = true)
            ACTION_START, null -> startTrading(
                profileIdArg = intent?.getStringExtra(EXTRA_PROFILE_ID),
                unitsArg = intent?.getIntExtra(EXTRA_UNITS, 3),
                testModeArg = intent?.getBooleanExtra(EXTRA_TEST_MODE, false)
            )
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        loopJob?.cancel()
        testRiskEditJob?.cancel()
        testRiskEditJob = null
        marketWsClient.setSnapshotListener(null)
        marketWsClient.stop()
        serviceScope.cancel()
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    private fun startTrading(profileIdArg: String?, unitsArg: Int?, testModeArg: Boolean?) {
        Log.i(TAG, "startTrading requested profileArg=$profileIdArg unitsArg=$unitsArg")
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Auto trade running"))

        val app = application as IncomingCallApp
        activeProfileId = profileIdArg?.ifBlank { null } ?: app.botPrefs.localProfileId
        activeUnits = (unitsArg ?: 3).coerceIn(1, 4)
        testModeEnabled = testModeArg == true
        testSignalInjected = false
        testRiskEditDispatched = false
        testRiskEditJob?.cancel()
        testRiskEditJob = null
        app.botPrefs.localProfileId = activeProfileId
        sessionStartEpochSec = System.currentTimeMillis() / 1000L
        lastSignalFingerprint = ""
        lastOrderSentAtEpochSec = prefs.getLong("last_order_sent_sec_$activeProfileId", 0L)
        orderRateLimitUntilEpochSec = 0L
        consecutiveOrderRateLimitHits = 0
        closeRateLimitUntilEpochSec = 0L
        consecutiveCloseRateLimitHits = 0
        orderAttempts = 0
        ordersPlaced = 0
        lastEditedStopLoss.clear()
        lastEditedTakeProfit.clear()
        cacheReady = false
        cache1m.clear()
        cacheMtf.clear()
        cachedTransactions = null
        cachedActiveOrders = emptyList()
        lastTransactionsFetchEpochSec = 0L
        lastActiveOrdersFetchEpochSec = 0L
        cachedUserInfo = JsonObject()
        cachedPortfolio = null
        lastUserInfoFetchEpochSec = 0L
        lastPortfolioFetchEpochSec = 0L
        lastProcessedCandleTimeSec = loadLastProcessedCandleTime(activeProfileId)
        executedEntryKeys.clear()
        executedEntryKeys.addAll(loadExecutedEntryKeys(activeProfileId))
        executedCloseKeys.clear()
        executedCloseKeys.addAll(loadExecutedCloseKeys(activeProfileId))
        val restoreAgeMin = if (lastProcessedCandleTimeSec > 0L) {
            ((sessionStartEpochSec - lastProcessedCandleTimeSec).coerceAtLeast(0L)) / 60L
        } else -1L
        Log.i(
            TAG,
            "startTrading initialized profile=$activeProfileId units=$activeUnits testMode=$testModeEnabled restoredLastCandle=$lastProcessedCandleTimeSec restoreAgeMin=$restoreAgeMin restoredIdemKeys=${executedEntryKeys.size} restoredCloseKeys=${executedCloseKeys.size}"
        )
        marketWsClient.setSnapshotListener { snapshot ->
            publishSocketMarketSnapshot(snapshot)
        }
        marketWsClient.rawMessageListener = { msg ->
            appendSocketRawLog(msg)
        }
        marketWsClient.transactionClosedListener = {
            Log.i(TAG, "socket event transaction_closed -> triggering state refresh")
            // The next runCycle will pick up the closed state via REST/WS
        }
        marketWsClient.start()
        if (lastProcessedCandleTimeSec <= 0L) {
            Log.i(TAG, "history checkpoint none: cold start, service will build context from fetched candles")
        } else {
            Log.i(
                TAG,
                "history checkpoint restored profile=$activeProfileId lastProcessed=$lastProcessedCandleTimeSec ageMin=$restoreAgeMin"
            )
        }

        loopJob?.cancel()
        loopJob = serviceScope.launch {
            val warmup = warmupCandleCache(nowSec = sessionStartEpochSec)
            if (!warmup.ok) {
                Log.w(TAG, "startTrading warmup incomplete reason=${warmup.error}")
            }
            val portfolioStatus = ensurePortfolioForSession(activeUnits)
            Log.i(
                TAG,
                "startTrading portfolioReady has=${portfolioStatus.hasPortfolio} id=${portfolioStatus.portfolioId} units=${portfolioStatus.units} balance=${portfolioStatus.userBalance} error=${portfolioStatus.error} warmup1m=${warmup.candles1m} warmupMtf=${
                    warmup.mtfCounts.entries.sortedBy { it.key }.joinToString(",") { "${it.key}m:${it.value}" }
                }"
            )
            AutoTradeStateStore.update {
                it.copy(
                    isRunning = true,
                    profileId = activeProfileId,
                    wsConnected = false,
                    startedAtEpochSec = sessionStartEpochSec,
                    hasPortfolio = portfolioStatus.hasPortfolio,
                    portfolioId = portfolioStatus.portfolioId,
                    portfolioUnits = portfolioStatus.units,
                    userBalance = portfolioStatus.userBalance,
                    lastDecision = if (portfolioStatus.hasPortfolio) "started" else "start failed: no portfolio",
                    lastError = mergeError(portfolioStatus.error, warmup.error),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            }
            while (isActive) {
                val startedAt = System.currentTimeMillis()
                Log.d(TAG, "cycle start profile=$activeProfileId")
                val targetIntervalMs = cycleMutex.withLock { runCycle() }
                val elapsed = System.currentTimeMillis() - startedAt
                val waitMs = (targetIntervalMs - elapsed).coerceAtLeast(0L)
                if (elapsed > CYCLE_WARN_MS) {
                    Log.w(TAG, "cycle slow elapsedMs=$elapsed thresholdMs=$CYCLE_WARN_MS")
                }
                Log.d(TAG, "cycle end elapsedMs=$elapsed targetIntervalMs=$targetIntervalMs nextInMs=$waitMs")
                delay(waitMs)
            }
        }
    }

    private fun stopTrading(closeAll: Boolean) {
        Log.i(TAG, "stopTrading closeAll=$closeAll")
        loopJob?.cancel()
        loopJob = null
        testRiskEditJob?.cancel()
        testRiskEditJob = null
        marketWsClient.setSnapshotListener(null)
        marketWsClient.stop()
        serviceScope.launch {
            val closeResult = if (closeAll) closeAllActiveTradesAndOrdersAndPortfolio() else ""
            Log.i(TAG, "stopTrading result closeAll=$closeAll closeResult=${closeResult.ifBlank { "ok" }}")
            AutoTradeStateStore.update {
                it.copy(
                    isRunning = false,
                    wsConnected = false,
                    lastDecision = if (closeAll) "stopped and close-all requested" else "stopped",
                    lastError = closeResult,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun publishSocketMarketSnapshot(snapshot: HivaPriceSocketClient.Snapshot) {
        AutoTradeStateStore.update { state ->
            if (!state.isRunning) return@update state
            val nextPrice = if (snapshot.price > 0.0) snapshot.price else state.price
            val nextBid = if (snapshot.bestBid > 0.0) snapshot.bestBid else state.bestBid
            val nextAsk = if (snapshot.bestAsk > 0.0) snapshot.bestAsk else state.bestAsk
            val hasMarketChange =
                nextPrice != state.price ||
                    nextBid != state.bestBid ||
                    nextAsk != state.bestAsk
            val hasConnectionChange = state.wsConnected != snapshot.connected
            val hasCountChange =
                state.openPositions != snapshot.openTransactions.size ||
                    state.pendingOrders != snapshot.pendingOrderCount
            if (!hasMarketChange && !hasConnectionChange && !hasCountChange) {
                return@update state
            }
            state.copy(
                price = nextPrice,
                bestBid = nextBid,
                bestAsk = nextAsk,
                wsConnected = snapshot.connected,
                openPositions = snapshot.openTransactions.size,
                pendingOrders = snapshot.pendingOrderCount,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        }
    }

    private suspend fun runCycle(): Long {
        val profileId = activeProfileId
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000L
        val prevProcessedBaseline = lastProcessedCandleTimeSec
        var errorMessage = ""
        marketWsClient.ensureConnected()
        val wsSnapshot = marketWsClient.getSnapshot()
        var wsConnected = wsSnapshot.connected

        val cacheSync = refreshCandleCache(nowSec)
        if (!cacheSync.ok) {
            errorMessage = mergeError(errorMessage, cacheSync.error)
        }
        val signalCandles = cache1m.toList()
        val lastPrice = signalCandles.lastOrNull()?.close ?: 0.0
        val currentClosedCandleCandidate = signalCandles.lastOrNull()?.time ?: 0L
        val fastRecheckCandidate = currentClosedCandleCandidate > 0L &&
            currentClosedCandleCandidate == lastProcessedCandleTimeSec &&
            (nowSec - currentClosedCandleCandidate) in 0L..FAST_RECHECK_WINDOW_SEC
        val shouldFetchTransactions = cachedTransactions == null ||
            !fastRecheckCandidate ||
            (nowSec - lastTransactionsFetchEpochSec) >= FAST_RECHECK_TX_REFRESH_SEC
        val shouldFetchActiveOrders = cachedActiveOrders.isEmpty() ||
            !fastRecheckCandidate ||
            (nowSec - lastActiveOrdersFetchEpochSec) >= FAST_RECHECK_ORDERS_REFRESH_SEC

        val shouldFetchUserInfo = cachedUserInfo.entrySet().isEmpty() ||
            (nowSec - lastUserInfoFetchEpochSec) >= USER_INFO_REFRESH_SEC
        val shouldFetchPortfolio = cachedPortfolio == null ||
            (nowSec - lastPortfolioFetchEpochSec) >= PORTFOLIO_REFRESH_SEC

        val parallel = try {
            coroutineScope {
                val transactionsDeferred = if (shouldFetchTransactions) async {
                    runCatching { withTimeoutOrNull(IO_TIMEOUT_MS) { HivaGoldClient.api.getMazanehTransactions(1) } }.getOrNull()
                } else null
                val activeOrdersDeferred = if (shouldFetchActiveOrders) async {
                    runCatching { withTimeoutOrNull(IO_TIMEOUT_MS) { HivaGoldClient.api.getMazanehActiveOrders() } }.getOrNull()
                } else null
                val userInfoDeferred = if (shouldFetchUserInfo) async {
                    runCatching { withTimeoutOrNull(IO_TIMEOUT_MS) { HivaGoldClient.api.getMazanehUserInfo() } }.getOrNull()
                } else null
                val portfolioDeferred = if (shouldFetchPortfolio) async {
                    runCatching { withTimeoutOrNull(IO_TIMEOUT_MS) { HivaGoldClient.api.getMazanehActivePortfolio() } }.getOrNull()
                } else null
                listOf(
                    transactionsDeferred?.await(),
                    activeOrdersDeferred?.await(),
                    userInfoDeferred?.await(),
                    portfolioDeferred?.await()
                )
            }
        } catch (e: Exception) {
            errorMessage = mergeError(errorMessage, "parallel api fetch failed: ${e.message}")
            Log.e(TAG, "runCycle parallel fetch failed", e)
            listOf(null, null, null, null)
        }

        val fetchedTxJson = parallel[0] as? JsonObject
        val fetchedActiveOrders = (parallel[1] as? List<*>)?.filterIsInstance<JsonObject>()
        val fetchedUserInfo = parallel[2] as? JsonObject
        val fetchedPortfolio = parallel[3] as? MazanehPortfolio
        if (fetchedTxJson != null) {
            cachedTransactions = fetchedTxJson
            lastTransactionsFetchEpochSec = nowSec
        }
        if (fetchedActiveOrders != null) {
            cachedActiveOrders = fetchedActiveOrders
            lastActiveOrdersFetchEpochSec = nowSec
        }
        val txJson = cachedTransactions
        val activeOrders = cachedActiveOrders
        if (fetchedUserInfo != null) {
            cachedUserInfo = fetchedUserInfo
            lastUserInfoFetchEpochSec = nowSec
        }
        if (fetchedPortfolio != null) {
            cachedPortfolio = fetchedPortfolio
            lastPortfolioFetchEpochSec = nowSec
        }
        val userInfo = cachedUserInfo
        val activePortfolio = cachedPortfolio

        if (signalCandles.isEmpty()) {
            errorMessage = mergeError(errorMessage, "market/history cache empty")
            Log.w(TAG, "history cache empty profile=$profileId nowSec=$nowSec cacheReady=$cacheReady")
        } else {
            val first = signalCandles.first().time
            val last = signalCandles.last().time
            val coverageMin = ((last - first).coerceAtLeast(0L)) / 60L
            val lagMin = ((nowSec - last).coerceAtLeast(0L)) / 60L
            Log.i(
                TAG,
                "history fetch ok profile=$profileId candles=${signalCandles.size} first=$first last=$last coverageMin=$coverageMin lagToNowMin=$lagMin"
            )
        }

        // Backtest-like cadence: only make strategy decisions on a new closed 1m candle.
        val currentClosedCandleTime = signalCandles.lastOrNull()?.time ?: 0L
        val hasNewClosedCandle = currentClosedCandleTime > 0L && currentClosedCandleTime > lastProcessedCandleTimeSec
        val currentClosedCandleAgeMs = if (currentClosedCandleTime > 0L) {
            (nowMs - (currentClosedCandleTime * 1000L)).coerceAtLeast(0L)
        } else {
            0L
        }
        // Parity mode: process on each newly closed candle.
        // Exception: allow same-candle reprocess only for flat-account drift recovery.
        val currentClosedSignatureAgeMs = 0L
        val currentClosedCandleStableChecks = 0
        val isClosedCandleEngineStable = hasNewClosedCandle
        val hasStableNewClosedCandle = hasNewClosedCandle
        val needsAdaptiveStabilizationWait = false
        var shouldReprocessClosedCandle = false
        var shouldProcessClosedCandle = hasNewClosedCandle
        val missedCandlesCount = if (lastProcessedCandleTimeSec > 0L && currentClosedCandleTime > lastProcessedCandleTimeSec) {
            (((currentClosedCandleTime - lastProcessedCandleTimeSec) / 60L) - 1L).coerceAtLeast(0L).toInt()
        } else 0
        val recoveryMode = hasStableNewClosedCandle && missedCandlesCount > 0
        Log.i(
            TAG,
            "cycle marketGate profile=$profileId ws=$wsConnected wsEndpoint=${wsSnapshot.endpoint.ifBlank { "-" }} wsErr=${wsSnapshot.lastError.ifBlank { "-" }} lastPrice=$lastPrice candles=${signalCandles.size} lastClosed=$currentClosedCandleTime prevProcessed=$lastProcessedCandleTimeSec newCandle=$hasNewClosedCandle stableNew=$hasStableNewClosedCandle engineStable=$isClosedCandleEngineStable candleAgeMs=$currentClosedCandleAgeMs stableChecks=$currentClosedCandleStableChecks sigAgeMs=$currentClosedSignatureAgeMs reprocess=$shouldReprocessClosedCandle missed=$missedCandlesCount recovery=$recoveryMode"
        )

        val openPositionsList = (txJson?.getAsJsonArray("open") ?: JsonArray()).map { it.asJsonObject }
        val openPositions = openPositionsList.size
        val pendingOrders = activeOrders.size
        val openPositionsRest = openPositions
        val pendingOrdersRest = pendingOrders
        val hasNoLiveExposureAtGate = openPositions == 0 &&
            pendingOrders == 0 &&
            openPositionsRest == 0 &&
            pendingOrdersRest == 0
        val shouldProbeSameCandleForDrift = !hasNewClosedCandle &&
            currentClosedCandleTime > 0L &&
            currentClosedCandleTime == lastProcessedCandleTimeSec &&
            hasNoLiveExposureAtGate &&
            (nowSec - currentClosedCandleTime) in 0L..FAST_RECHECK_WINDOW_SEC
        if (shouldProbeSameCandleForDrift) {
            shouldReprocessClosedCandle = true
            shouldProcessClosedCandle = true
            Log.w(
                TAG,
                "drift probe same-candle reprocess profile=$profileId candle=$currentClosedCandleTime ageSec=${nowSec - currentClosedCandleTime} lastProcessed=$lastProcessedCandleTimeSec"
            )
        }
        
        val userBalance = userInfo.doubleValue("balance", 0.0)
        val portfolioUnits = activePortfolio?.availableUnits?.toInt() ?: activeUnits
        val portfolioId = activePortfolio?.id?.toString() ?: ""
        val hasPortfolio = activePortfolio != null
        
        Log.i(
            TAG,
            "cycle hiva-mazaneh has=$hasPortfolio id=$portfolioId free=$portfolioUnits open=$openPositions pending=$pendingOrders balance=$userBalance"
        )
        val wsAccountReady = wsConnected && wsSnapshot.accountSnapshotReady
        val wsStaleVsRest = false
        val isGhostPosition = false
        val portfolio = userInfo // Use userInfo as a proxy for the missing portfolio JsonObject in logs
        val price = if (wsSnapshot.price > 0.0) wsSnapshot.price else lastPrice
        val bestBid = if (wsSnapshot.bestBid > 0.0) wsSnapshot.bestBid else lastPrice
        val bestAsk = if (wsSnapshot.bestAsk > 0.0) wsSnapshot.bestAsk else lastPrice
        Log.i(
            TAG,
            "cycle portfolio has=$hasPortfolio id=$portfolioId free=$portfolioUnits open=$openPositions pending=$pendingOrders balance=$userBalance wsAccountReady=$wsAccountReady"
        )

        val mtfSnapshot = snapshotMtfCache()
        val livePayloadHash = if (shouldProcessClosedCandle) {
            computeSignalPayloadHash(signalCandles, mtfSnapshot)
        } else {
            ""
        }
        if (shouldProcessClosedCandle) {
            appendSignalInputLog(
                profileId = profileId,
                nowSec = nowSec,
                candleTimeSec = currentClosedCandleTime,
                recoveryMode = recoveryMode,
                candles1m = signalCandles,
                mtf = mtfSnapshot
            )
        }
        var signal = if (shouldProcessClosedCandle) {
            runCatching { LocalBotRuntime.marketSignalFromCandles(profileId, signalCandles, mtfSnapshot) }.getOrElse { err ->
                errorMessage = mergeError(errorMessage, "signal failed: ${err.message}")
                JsonObject().apply {
                    addProperty("type", "no_signal")
                    addProperty("direction", "-")
                    addProperty("reason", "signal_error")
                }
            }
        } else {
            JsonObject().apply {
                addProperty("type", "no_signal")
                addProperty("direction", "-")
                addProperty("reason", "wait_new_candle")
            }
        }
        val shouldInjectTestSignal = testModeEnabled &&
            !testSignalInjected &&
            (nowSec - sessionStartEpochSec) >= TEST_SIGNAL_DELAY_SEC &&
            hasStableNewClosedCandle &&
            !recoveryMode
        val testSignalDelayedByRecovery = testModeEnabled &&
            !testSignalInjected &&
            (nowSec - sessionStartEpochSec) >= TEST_SIGNAL_DELAY_SEC &&
            hasStableNewClosedCandle &&
            recoveryMode
        if (testSignalDelayedByRecovery) {
            Log.w(
                TAG,
                "TEST_MODE injection delayed due to recovery profile=$profileId missed=$missedCandlesCount from=$lastProcessedCandleTimeSec to=$currentClosedCandleTime"
            )
        }
        var recoveryReplayMode = "-"
        var recoveryReplayRuns = 0
        if (shouldInjectTestSignal) {
            val basePrice = if (price > 0.0) price else lastPrice
            if (basePrice > 0.0) {
                val entry = basePrice.roundToInt().toDouble()
                val stop = (entry - TEST_SIGNAL_TP_SL_POINTS).coerceAtLeast(1.0)
                val take = entry + TEST_SIGNAL_TP_SL_POINTS
                signal = JsonObject().apply {
                    addProperty("type", "signal")
                    addProperty("direction", "long")
                    addProperty("entry_price", entry.toString())
                    addProperty("stop_loss", stop.toString())
                    addProperty("take_profit", take.toString())
                    addProperty("setup_score", 99)
                    addProperty("quantity", 1)
                    addProperty("reason", "test_mode_injected")
                }
                testSignalInjected = true
                Log.w(
                    TAG,
                    "TEST_MODE injected fake signal profile=$profileId entry=$entry stop=$stop take=$take units=1 afterSec=${nowSec - sessionStartEpochSec}"
                )
            } else {
                Log.w(TAG, "TEST_MODE pending but base price unavailable")
            }
        }
        if (recoveryMode) {
            val recovery = recoverSignalAfterGap(
                profileId = profileId,
                candles = signalCandles,
                fromExclusive = lastProcessedCandleTimeSec,
                toInclusive = currentClosedCandleTime,
                preloadedMtf = mtfSnapshot
            )
            recoveryReplayMode = recovery.mode
            recoveryReplayRuns = recovery.replayRuns
            signal = recovery.signal
            val replayed = signalCandles.count { it.time > lastProcessedCandleTimeSec && it.time <= currentClosedCandleTime }
            Log.i(
                TAG,
                "recovery replay profile=$profileId mode=${recovery.mode} missed=$missedCandlesCount replayed=$replayed replayRuns=${recovery.replayRuns} from=$lastProcessedCandleTimeSec to=$currentClosedCandleTime signal=${signal.stringValue("type", "no_signal")}"
            )
        }
        Log.i(TAG, "cycle signal ${summarizeSignal(signal)}")

        var signalType = signal.stringValue("type", "no_signal")
        var signalDirection = signal.stringValue("direction", "-")
        var signalReason = signal.stringValue("reason", signal.stringValue("exit_reason", ""))
        val rawEngineSignalType = signalType
        val rawEngineReason = signalReason.ifBlank { "-" }
        var recoveredFromPositionUpdateDrift = false
        var recoveredFromBrokerOpenEngineFlat = false
        val hasNoLiveExposure = hasNoLiveExposureAtGate
        if (hasNoLiveExposure) {
            clearLastEngineOpenSnapshot(profileId)
        }
        // Heal the "broker missed a fresh engine entry" drift.
        //
        // The engine's live_signal replays the full candle history each cycle. If a past
        // candle's OHLC data was revised after we first processed it (a common real-time
        // feed behavior), the engine may now see a valid entry on an earlier candle while
        // live, at the time, saw no_signal. On a later cycle the engine returns
        // `position_update` (position already opened in replay) with entry_time pointing
        // at the candle where it thinks the position activated.
        //
        // If the broker is still flat AND the engine's entry is on the current or just-
        // previous closed candle, we missed it in live. Promote to an entry signal now so
        // we take the trade like backtest would, instead of sitting on `position_update`
        // forever while the broker stays flat.
        val engineEntryTimeSec = signal.longValue("entry_time", 0L)
        val freshEngineEntry = engineEntryTimeSec > 0L &&
            currentClosedCandleTime > 0L &&
            (currentClosedCandleTime - engineEntryTimeSec) in 0L..60L
        val shouldAttemptPositionUpdatePromotion = shouldProcessClosedCandle &&
            signalType == "position_update" &&
            hasNoLiveExposure &&
            (recoveryMode || freshEngineEntry || shouldReprocessClosedCandle)
        val recoverEntryParity = if (shouldAttemptPositionUpdatePromotion) {
            verifyRecoverEntryPromotionParity(
                profileId = profileId,
                candles = signalCandles,
                preloadedMtf = mtfSnapshot,
                currentClosedCandleTime = currentClosedCandleTime,
                engineEntryTimeSec = engineEntryTimeSec,
                expectedDirection = signalDirection
            )
        } else {
            RecoverEntryParity(confirmed = false, code = "not_checked", detail = "not_checked")
        }
        val shouldPromotePositionUpdate = shouldAttemptPositionUpdatePromotion && recoverEntryParity.confirmed
        if (shouldPromotePositionUpdate) {
            val promotedDirection = signalDirection.ifBlank { "-" }
            val promotedEntry = signal.stringValue("entry_price", "")
            val promotedStop = signal.stringValue("stop_loss", "")
            val promotedTake = signal.stringValue("take_profit", "")
            val promotedScore = signal.stringValue("setup_score", "0")
            val promotedQty = signal.intValue("quantity", 0)
            val priorCandleTime = signal.longValue("candle_time", 0L)
            signal = JsonObject().apply {
                addProperty("type", "signal")
                addProperty("direction", promotedDirection)
                addProperty("entry_price", promotedEntry)
                addProperty("stop_loss", promotedStop)
                addProperty("take_profit", promotedTake)
                addProperty("setup_score", promotedScore)
                addProperty("quantity", promotedQty)
                addProperty("reason", "position_update_recover_entry")
                if (priorCandleTime > 0L) addProperty("candle_time", priorCandleTime)
                if (engineEntryTimeSec > 0L) addProperty("entry_time", engineEntryTimeSec)
            }
            signalType = "signal"
            signalDirection = promotedDirection
            signalReason = "position_update_recover_entry"
            recoveredFromPositionUpdateDrift = true
            val trigger = when {
                recoveryMode && freshEngineEntry -> "recovery+fresh"
                recoveryMode -> "recovery"
                shouldReprocessClosedCandle && freshEngineEntry -> "reprocess+fresh"
                shouldReprocessClosedCandle -> "reprocess"
                else -> "fresh"
            }
            appendPositionUpdatePromoteRow(
                profileId = profileId,
                nowSec = nowSec,
                candleTimeSec = currentClosedCandleTime,
                engineEntryTimeSec = engineEntryTimeSec,
                trigger = trigger,
                promoted = true,
                orderSent = "pending",
                detail = "sig=position_update brokerFlat=true reason=position_update_recover_entry parity=${sanitizeAuditToken(recoverEntryParity.detail).take(120)}"
            )
            Log.w(
                TAG,
                "state drift detected profile=$profileId candle=$currentClosedCandleTime " +
                    "signal=position_update but broker flat (wsOpen=$openPositions wsPending=$pendingOrders " +
                    "restOpen=$openPositionsRest restPending=$pendingOrdersRest) " +
                    "engineEntryTime=$engineEntryTimeSec deltaSec=${currentClosedCandleTime - engineEntryTimeSec} " +
                    "trigger=$trigger parity=${recoverEntryParity.detail}, promoting to entry signal"
            )
        } else if (shouldProcessClosedCandle &&
            signalType == "position_update" &&
            hasNoLiveExposure
        ) {
            // Engine says position_update but broker is flat and the engine's entry is NOT
            // fresh. We don't promote (would chase an old trade). Log why for audits.
            val skipReason = if (shouldAttemptPositionUpdatePromotion) "parity_reject" else "not_fresh"
            val skipTrigger = when {
                shouldAttemptPositionUpdatePromotion -> "parity_skip"
                recoveryMode -> "recovery_skip"
                else -> "stale_skip"
            }
            appendPositionUpdatePromoteRow(
                profileId = profileId,
                nowSec = nowSec,
                candleTimeSec = currentClosedCandleTime,
                engineEntryTimeSec = engineEntryTimeSec,
                trigger = skipTrigger,
                promoted = false,
                orderSent = "skip",
                detail = if (shouldAttemptPositionUpdatePromotion) {
                    "sig=position_update brokerFlat=true reason=parity_reject parity=${sanitizeAuditToken(recoverEntryParity.detail).take(120)}"
                } else {
                    "sig=position_update brokerFlat=true reason=not_fresh"
                }
            )
            if (skipReason == "parity_reject") {
                val parityDetail = sanitizeAuditToken(recoverEntryParity.detail).take(220)
                appendAuditDebugRow(
                    profileId,
                    "PARITY_REJECT|${nowSec}|${currentClosedCandleTime}|entryTime=$engineEntryTimeSec|code=${sanitizeAuditToken(recoverEntryParity.code)}|detail=$parityDetail"
                )
                Log.w(
                    TAG,
                    "PARITY_REJECT profile=$profileId candle=$currentClosedCandleTime entryTime=$engineEntryTimeSec code=${recoverEntryParity.code} detail=${recoverEntryParity.detail}"
                )
            }
            Log.w(
                TAG,
                "position_update skipped promotion profile=$profileId candle=$currentClosedCandleTime " +
                    "engineEntryTime=$engineEntryTimeSec deltaSec=${if (engineEntryTimeSec > 0L) currentClosedCandleTime - engineEntryTimeSec else -1L} " +
                    "recovery=$recoveryMode skip=$skipReason parity=${recoverEntryParity.detail}"
            )
        }
        if (shouldProcessClosedCandle &&
            signalType == "no_signal" &&
            !hasNoLiveExposure
        ) {
            val snap = loadLastEngineOpenSnapshot(profileId)
            val broDir = normalizedBrokerOpenDirection(wsSnapshot, openPositionsList, wsAccountReady)
            val snapDir = snap?.stringValue("direction", "")?.lowercase(Locale.US).orEmpty()
            if (snap != null &&
                snapDir.isNotBlank() &&
                (broDir.isNullOrBlank() || snapDir == broDir)
            ) {
                signal = snapshotToPositionUpdateSignal(snap, currentClosedCandleTime)
                signalType = "position_update"
                signalDirection = signal.stringValue("direction", "")
                signalReason = "engine_flat_reuse_snapshot"
                recoveredFromBrokerOpenEngineFlat = true
                Log.w(
                    TAG,
                    "state drift detected profile=$profileId candle=$currentClosedCandleTime engine=no_signal but broker has open=$openPositions (rest=$openPositionsRest); reusing last engine open snapshot dir=$snapDir"
                )
            } else {
                Log.w(
                    TAG,
                    "snapshot reuse skipped profile=$profileId candle=$currentClosedCandleTime " +
                        "engineRaw=$rawEngineSignalType brokerOpen=$openPositions restOpen=$openPositionsRest " +
                        "hasSnap=${snap != null} snapDir=${snapDir.ifBlank { "-" }} broDir=${broDir ?: "-"}"
                )
            }
        }
        if (shouldProcessClosedCandle) {
            if (signalType == "position_update") {
                saveLastEngineOpenSnapshot(profileId, signal)
            } else if (recoveredFromPositionUpdateDrift) {
                saveLastEngineOpenSnapshot(profileId, signal)
            }
            Log.i(
                TAG,
                "signal path profile=$profileId candle=$currentClosedCandleTime " +
                    "raw=$rawEngineSignalType reason=$rawEngineReason -> final=$signalType reason=${signalReason.ifBlank { "-" }} " +
                    "op=$openPositions healPromote=$recoveredFromPositionUpdateDrift healReuse=$recoveredFromBrokerOpenEngineFlat"
            )
        }
        val backtest = if (shouldProcessClosedCandle) {
            runCatching {
                LocalBotRuntime.marketBacktestFromCandles(
                    profileId = profileId,
                    candles = signalCandles,
                    preloadedMtf = mtfSnapshot
                )
            }.getOrElse { err ->
                errorMessage = mergeError(errorMessage, "backtest failed: ${err.message}")
                JsonObject().apply { add("trades", JsonArray()) }
            }
        } else {
            JsonObject().apply { add("trades", JsonArray()) }
        }
        if (shouldProcessClosedCandle) {
            val backtestPayloadHash = backtest.stringValue("_input_payload_hash", "")
            val parity = if (backtestPayloadHash.isNotBlank() && backtestPayloadHash == livePayloadHash) "MATCH" else "MISMATCH"
            Log.i(
                TAG,
                "payload parity profile=$profileId candle=$currentClosedCandleTime parity=$parity liveHash=$livePayloadHash btHash=${backtestPayloadHash.ifBlank { "-" }} 1m=${signalCandles.size}"
            )
        }
        val sessionStats = computeSessionStats(backtest, sessionStartEpochSec)
        val dayBacktest = if (shouldProcessClosedCandle) {
            computeDayBacktestSummary(backtest, nowSec)
        } else {
            null
        }
        if (dayBacktest != null) {
            Log.i(
                TAG,
                "day backtest trades=${dayBacktest.tradeCount} wins=${dayBacktest.winCount} losses=${dayBacktest.lossCount} pnl=${dayBacktest.netPnl}"
            )
            logBacktestDiagnostics(profileId, backtest, dayBacktest, nowSec)
        }
        if (shouldProcessClosedCandle && signalType != "close_signal" && !hasNoLiveExposure) {
            val brokerDir = normalizedBrokerOpenDirection(wsSnapshot, openPositionsList, wsAccountReady).orEmpty()
            val currentEngineEntryTime = signal.longValue("entry_time", 0L)
            val closeFromBacktest = buildCloseSignalFromBacktest(backtest, currentClosedCandleTime, brokerDir, currentEngineEntryTime)
            if (closeFromBacktest != null) {
                signal = closeFromBacktest
                signalType = "close_signal"
                signalDirection = signal.stringValue("direction", brokerDir)
                signalReason = signal.stringValue("exit_reason", signal.stringValue("reason", ""))
                Log.w(
                    TAG,
                    "close parity profile=$profileId candle=$currentClosedCandleTime using backtest replay close " +
                        "dir=${signalDirection.ifBlank { "-" }} reason=${signalReason.ifBlank { "-" }}"
                )
            }
        }

        var decision = if (shouldProcessClosedCandle) "no action" else "waiting for new 1m candle close"
        if (needsAdaptiveStabilizationWait) {
            decision = "waiting for candle stabilization"
        }
        var gateTrace = buildString {
            append("newCandle=").append(hasNewClosedCandle)
            append(" stableNew=").append(hasStableNewClosedCandle)
            append(" stableChecks=").append(currentClosedCandleStableChecks)
            append(" sigAgeMs=").append(currentClosedSignatureAgeMs)
            append(" reprocess=").append(shouldReprocessClosedCandle)
            append(" recovery=").append(recoveryMode)
            append(" signal=").append(signalType)
            append(" dir=").append(signalDirection)
            append(" reason=").append(signalReason.ifBlank { "-" })
            append(" open=").append(openPositions)
            append(" ghost=").append(isGhostPosition)
            append(" staleWS=").append(wsStaleVsRest)
            append(" pending=").append(pendingOrders)
            append(" free=").append(portfolioUnits)
            append(" ws=").append(wsConnected)
            append(" candle=").append(currentClosedCandleTime)
            append(" candleAgeMs=").append(currentClosedCandleAgeMs)
            append(" miss=").append(missedCandlesCount)
            val engineEntryTimeSec = signal.longValue("entry_time", 0L)
            if (engineEntryTimeSec > 0L) {
                append(" entryTime=").append(engineEntryTimeSec)
            }
            if (recoveredFromPositionUpdateDrift) {
                append(" drift=position_update_without_open")
            }
            if (recoveredFromBrokerOpenEngineFlat) {
                append(" drift=broker_open_engine_flat")
            }
        }
        if (shouldProcessClosedCandle && signalType == "close_signal") {
            val closeOutcome = executeEngineCloseSignal(
                signal = signal,
                profileId = profileId,
                portfolioId = portfolioId,
                candleTimeSec = currentClosedCandleTime,
                nowSec = nowSec,
                wsSnapshot = wsSnapshot,
                openPositionsList = openPositionsList,
                wsAccountReady = wsAccountReady
            )
            decision = closeOutcome.decision
            gateTrace += closeOutcome.gateSuffix
        }
        var lastOrderIdUpdate: String? = null
        var lastOrderActionUpdate: String? = null
        var lastOrderUnitsUpdate: Int? = null
        var lastOrderAtUpdate: Long? = null
        var lastSubmitOkUpdate: Boolean? = null
        var lastSubmitMessageUpdate: String? = null
        var lastSubmitAtUpdate: Long? = null
        var lastLiveTransactionIdUpdate: String? = wsSnapshot.lastTransactionId.takeIf { it.isNotBlank() }
        val hasOpenPositionForRisk =
            openPositions > 0 || openPositionsRest > 0
        val riskUpdateDecision = if (shouldProcessClosedCandle && hasOpenPositionForRisk) {
            maybeUpdateOpenPositionRisk(
                signal = signal,
                wsSnapshot = wsSnapshot,
                openPositionsList = openPositionsList,
                wsAccountReady = wsAccountReady,
                auditProfileId = profileId,
                auditNowSec = nowSec,
                auditCandleSec = currentClosedCandleTime
            )
        } else {
            ""
        }
        if (riskUpdateDecision.isNotBlank()) {
            decision = riskUpdateDecision
            Log.i(TAG, "cycle riskUpdate decision=$riskUpdateDecision")
            gateTrace += " riskUpdate=true"
        }
        // Do not block entries after a gap: replay already ends at currentClosedCandleTime (same as backtest's last bar).
        if (recoveryMode && signalType != "close_signal") {
            gateTrace += " recovery_catchup=allow miss=$missedCandlesCount"
        }
        if (shouldProcessClosedCandle && signalType == "signal") {
                val entry = signal.stringValue("entry_price", "").toDoubleOrNull()
                val stop = signal.stringValue("stop_loss", "").toDoubleOrNull()
                val take = signal.stringValue("take_profit", "").toDoubleOrNull()
                val setupScore = signal.intValue("setup_score", 0)
                val signalQty = signal.intValue("quantity", 0)
                val signalEntryTimeSec = signal.longValue("entry_time", 0L)
                val expectedEntryTimeSec = if (signalEntryTimeSec > 0L) signalEntryTimeSec else currentClosedCandleTime
                val entryBacktestParity = if (testModeEnabled && signalReason == "test_mode_injected") {
                    EntryBacktestParity(
                        confirmed = true,
                        code = "bypass_test_mode",
                        detail = "test_mode_injected"
                    )
                } else {
                    verifyEntryAgainstBacktest(
                        backtest = backtest,
                        expectedEntryTimeSec = expectedEntryTimeSec,
                        expectedDirection = signalDirection
                    )
                }
                val desiredUnits = if (testModeEnabled && signalReason == "test_mode_injected") {
                    1.coerceAtMost(portfolioUnits.coerceAtLeast(0))
                } else {
                    computeOrderUnits(profileId, setupScore, portfolioUnits, signalQty)
                }
                if (entry == null || stop == null || take == null) {
                    decision = "skip: invalid signal prices"
                    gateTrace += " skip=invalid_signal_prices"
                    appendAuditDebugRow(
                        profileId,
                        "ENTRY_ABORT|${nowSec}|${currentClosedCandleTime}|invalid_prices|why=${sanitizeAuditToken(signalReason)}"
                    )
                } else if (!entryBacktestParity.confirmed) {
                    decision = "skip: backtest parity reject (${entryBacktestParity.code})"
                    gateTrace += " skip=bt_parity code=${entryBacktestParity.code}"
                    appendAuditDebugRow(
                        profileId,
                        "ENTRY_SKIP|${nowSec}|${currentClosedCandleTime}|bt_parity_reject|entryTime=$expectedEntryTimeSec|code=${sanitizeAuditToken(entryBacktestParity.code)}|detail=${sanitizeAuditToken(entryBacktestParity.detail).take(180)}"
                    )
                    Log.w(
                        TAG,
                        "ENTRY_PARITY_REJECT profile=$profileId candle=$currentClosedCandleTime expectedEntry=$expectedEntryTimeSec " +
                            "dir=${signalDirection.ifBlank { "-" }} code=${entryBacktestParity.code} detail=${entryBacktestParity.detail}"
                    )
                } else if (desiredUnits <= 0) {
                    decision = "skip: no free units"
                    gateTrace += " skip=no_free_units desired=$desiredUnits"
                    appendAuditDebugRow(
                        profileId,
                        "ENTRY_ABORT|${nowSec}|${currentClosedCandleTime}|no_free_units|desired=$desiredUnits|free=$portfolioUnits"
                    )
                } else if (openPositions > 0 || pendingOrders > 0) {
                    decision = "skip: existing open/pending order"
                    gateTrace += " skip=open_or_pending"
                    appendAuditDebugRow(
                        profileId,
                        "ENTRY_ABORT|${nowSec}|${currentClosedCandleTime}|open_or_pending|op=$openPositions|pd=$pendingOrders"
                    )
                } else {
                    val fingerprint = "${signalDirection.lowercase()}|${entry.roundToInt()}|${stop.roundToInt()}|${take.roundToInt()}"
                    val idempotencyKey = buildEntryIdempotencyKey(
                        profileId = profileId,
                        portfolioId = portfolioId,
                        candleTime = currentClosedCandleTime,
                        fingerprint = fingerprint
                    )
                    if (executedEntryKeys.contains(idempotencyKey)) {
                        decision = "skip: idempotent entry already executed"
                        gateTrace += " skip=idempotent key=$idempotencyKey"
                        appendAuditDebugRow(
                            profileId,
                            "ENTRY_SKIP|${nowSec}|${currentClosedCandleTime}|idem|${sanitizeAuditToken(idempotencyKey)}|fp=${sanitizeAuditToken(fingerprint)}"
                        )
                    } else if (fingerprint == lastSignalFingerprint && nowSec - lastOrderSentAtEpochSec < DUPLICATE_ORDER_COOLDOWN_SEC) {
                        decision = "skip: duplicate signal cooldown"
                        gateTrace += " skip=duplicate_cooldown"
                        appendAuditDebugRow(
                            profileId,
                            "ENTRY_SKIP|${nowSec}|${currentClosedCandleTime}|cooldown|fp=${sanitizeAuditToken(fingerprint)}|ageSec=${nowSec - lastOrderSentAtEpochSec}"
                        )
                    } else if (nowSec < orderRateLimitUntilEpochSec) {
                        val remainSec = (orderRateLimitUntilEpochSec - nowSec).coerceAtLeast(1L)
                        decision = "skip: rate-limited cooldown ${remainSec}s"
                        gateTrace += " skip=rate_limit_cooldown rem=${remainSec}s"
                        appendAuditDebugRow(
                            profileId,
                            "ENTRY_SKIP|${nowSec}|${currentClosedCandleTime}|rate_limit_cooldown|remain=${remainSec}s|fp=${sanitizeAuditToken(fingerprint)}"
                        )
                    } else {
                        orderAttempts += 1
                        val action = if (signalDirection.lowercase() == "long") "buy" else "sell"
                        var driftRecoverMarketRef: Int? = null
                        var submitTake = take.roundToInt()
                        var submitStop = stop.roundToInt()
                        if (signalReason == "position_update_recover_entry") {
                            val marketRef = marketReferenceForVerbalOrder(
                                action = action,
                                bestBid = bestBid,
                                bestAsk = bestAsk,
                                lastPrice = price,
                                entryFallback = entry.roundToInt()
                            )
                            driftRecoverMarketRef = marketRef
                            val rebased = rebaseDriftRecoverTpSl(
                                action = action,
                                entry = entry,
                                stop = stop,
                                take = take,
                                marketRef = marketRef
                            )
                            submitTake = rebased.first
                            submitStop = rebased.second
                            Log.w(
                                TAG,
                                "DRIFT_RECOVER rebase action=$action simEntry=${entry.roundToInt()} marketRef=$marketRef " +
                                    "rawTp=${take.roundToInt()} rawSl=${stop.roundToInt()} -> tp=$submitTake sl=$submitStop"
                            )
                        }
                        if (testModeEnabled && signalReason == "test_mode_injected") {
                            val marketRef = when {
                                action == "buy" && bestAsk > 0.0 -> bestAsk.roundToInt()
                                action == "sell" && bestBid > 0.0 -> bestBid.roundToInt()
                                price > 0.0 -> price.roundToInt()
                                else -> entry.roundToInt()
                            }
                            if (action == "buy") {
                                submitTake = submitTake.coerceAtLeast(marketRef + 2)
                                submitStop = submitStop.coerceAtMost((marketRef - 2).coerceAtLeast(1))
                            } else {
                                submitTake = submitTake.coerceAtMost((marketRef - 2).coerceAtLeast(1))
                                submitStop = submitStop.coerceAtLeast(marketRef + 2)
                            }
                            Log.w(
                                TAG,
                                "TEST_MODE submit price-guard action=$action marketRef=$marketRef rawTp=${take.roundToInt()} rawSl=${stop.roundToInt()} guardedTp=$submitTake guardedSl=$submitStop"
                            )
                        }
                        // Keep order contract aligned with Hiva web panel (verbal order payload).
                        val body = JsonObject().apply {
                            addProperty("order_type", "verbal")
                            addProperty("action", action)
                            addProperty("units", desiredUnits)
                            addProperty("take_profit", submitTake)
                            addProperty("stop_loss", submitStop)
                        }
                        appendAuditDebugRow(
                            profileId,
                            buildString {
                                append("ORDER_ATTEMPT|").append(nowSec).append("|").append(currentClosedCandleTime).append("|")
                                append(sanitizeAuditToken(action)).append("|").append(desiredUnits).append("|")
                                append(setupScore).append("|")
                                append(entry.roundToInt()).append("|").append(stop.roundToInt()).append("|").append(take.roundToInt()).append("|")
                                append("subTP=").append(submitTake).append("|subSL=").append(submitStop).append("|")
                                append("why=").append(sanitizeAuditToken(signalReason).take(64)).append("|")
                                append("idem=").append(sanitizeAuditToken(idempotencyKey)).append("|")
                                append("fp=").append(sanitizeAuditToken(fingerprint)).append("|")
                                append("mktDrift=").append(driftRecoverMarketRef).append("|")
                                append("test=").append(testModeEnabled)
                            }
                        )
                        delay(ORDER_SUBMIT_DELAY_MS)
                        runCatching { HivaGoldClient.api.createMazanehOrder(body) }.onSuccess { res ->
                        consecutiveOrderRateLimitHits = 0
                        orderRateLimitUntilEpochSec = 0L
                        val submitOk = res.has("detail") // Mazaneh returns {"detail": "..."} on success
                        val submitMessage = res.stringValue("detail", res.stringValue("message", ""))
                        lastSubmitOkUpdate = submitOk
                        lastSubmitMessageUpdate = submitMessage
                        lastSubmitAtUpdate = nowSec
                        if (submitOk) {
                            ordersPlaced += 1
                            lastSignalFingerprint = fingerprint
                            lastOrderSentAtEpochSec = nowSec
                            prefs.edit()
                                .putLong("last_order_sent_sec_$profileId", nowSec)
                                .putInt("pre_order_free_units_$profileId", portfolioUnits)
                                .apply()
                            recordExecutedEntryKey(profileId, idempotencyKey)
                            val orderId = extractSubmitOrderId(res)
                            lastOrderIdUpdate = orderId
                            lastOrderActionUpdate = action
                            lastOrderUnitsUpdate = desiredUnits
                            lastOrderAtUpdate = nowSec
                            decision = "order sent action=$action units=$desiredUnits id=$orderId"
                            gateTrace += " submit=ok action=$action units=$desiredUnits id=$orderId"
                            Log.i(
                                TAG,
                                "auto order submitted action=$action units=$desiredUnits profile=$profileId score=$setupScore fingerprint=$fingerprint id=$orderId idem=$idempotencyKey tp=$submitTake sl=$submitStop response=$res"
                            )
                            appendAuditDebugRow(
                                profileId,
                                "ORDER_OK|${nowSec}|${currentClosedCandleTime}|${sanitizeAuditToken(action)}|id=${sanitizeAuditToken(orderId)}|tp=$submitTake|sl=$submitStop|idem=${sanitizeAuditToken(idempotencyKey)}|mktDrift=$driftRecoverMarketRef"
                            )
                            val realOrderId = fetchTransactionIdAfterSubmit(
                                phase = "primary",
                                profileId = profileId,
                                action = action,
                                baselineLastTx = wsSnapshot.lastTransactionId
                            )
                            if (realOrderId.isNotBlank() && realOrderId != orderId) {
                                lastOrderIdUpdate = realOrderId
                            }
                            if (testModeEnabled && signalReason == "test_mode_injected") {
                                scheduleTestModeRiskEdit(
                                    action = action,
                                    take = submitTake,
                                    stop = submitStop,
                                    profileId = profileId,
                                    nowSec = nowSec
                                )
                            }
                        } else {
                            decision = "order rejected: ${submitMessage.ifBlank { "unknown" }}"
                            gateTrace += " submit=rejected msg=${submitMessage.ifBlank { "unknown" }}"
                            errorMessage = mergeError(errorMessage, decision)
                            Log.w(
                                TAG,
                                "auto order rejected action=$action units=$desiredUnits profile=$profileId tp=$submitTake sl=$submitStop response=$res"
                            )
                            appendAuditDebugRow(
                                profileId,
                                "ORDER_REJECT|${nowSec}|${currentClosedCandleTime}|${sanitizeAuditToken(submitMessage.take(220))}|tp=$submitTake|sl=$submitStop|idem=${sanitizeAuditToken(idempotencyKey)}"
                            )
                        }
                    }.onFailure { err ->
                        lastSubmitOkUpdate = false
                        lastSubmitMessageUpdate = err.message ?: "submit failed"
                        lastSubmitAtUpdate = nowSec
                        if (testModeEnabled && signalReason == "test_mode_injected" && err is HttpException && err.code() >= 500) {
                            Log.w(
                                TAG,
                                "TEST_MODE first submit failed http=${err.code()} retrying once with proven verbal payload"
                            )
                            appendAuditDebugRow(
                                profileId,
                                "ORDER_HTTP_ERR|${nowSec}|${currentClosedCandleTime}|code=${err.code()}|retry=1|tp=$submitTake|sl=$submitStop|idem=${sanitizeAuditToken(idempotencyKey)}"
                            )
                            val retryBody = JsonObject().apply {
                                addProperty("order_type", "verbal")
                                addProperty("action", action)
                                addProperty("units", desiredUnits)
                                addProperty("take_profit", submitTake)
                                addProperty("stop_loss", submitStop)
                            }
                            delay(ORDER_SUBMIT_DELAY_MS)
                            runCatching { HivaGoldClient.api.createMazanehOrder(retryBody) }.onSuccess { retryRes ->
                                consecutiveOrderRateLimitHits = 0
                                orderRateLimitUntilEpochSec = 0L
                                val retryOk = retryRes.has("detail")
                                val retryMessage = retryRes.stringValue("detail", retryRes.stringValue("message", ""))
                                lastSubmitOkUpdate = retryOk
                                lastSubmitMessageUpdate = retryMessage
                                lastSubmitAtUpdate = nowSec
                                if (retryOk) {
                                    ordersPlaced += 1
                                    lastSignalFingerprint = fingerprint
                                    lastOrderSentAtEpochSec = nowSec
                                    prefs.edit().putLong("last_order_sent_sec_$profileId", nowSec).apply()
                                    recordExecutedEntryKey(profileId, idempotencyKey)
                                    val retryOrderId = extractSubmitOrderId(retryRes)
                                    lastOrderIdUpdate = retryOrderId
                                    lastOrderActionUpdate = action
                                    lastOrderUnitsUpdate = desiredUnits
                                    lastOrderAtUpdate = nowSec
                                    decision = "order sent (retry) action=$action units=$desiredUnits id=$retryOrderId"
                                    gateTrace += " submit=ok_retry action=$action units=$desiredUnits id=$retryOrderId"
                                    Log.i(
                                        TAG,
                                        "TEST_MODE retry submit succeeded action=$action units=$desiredUnits profile=$profileId id=$retryOrderId tp=$submitTake sl=$submitStop response=$retryRes"
                                    )
                                    appendAuditDebugRow(
                                        profileId,
                                        "ORDER_OK_RETRY|${nowSec}|${currentClosedCandleTime}|${sanitizeAuditToken(action)}|id=${sanitizeAuditToken(retryOrderId)}|tp=$submitTake|sl=$submitStop|idem=${sanitizeAuditToken(idempotencyKey)}"
                                    )
                                    val realOrderId = fetchTransactionIdAfterSubmit(
                                        phase = "retry",
                                        profileId = profileId,
                                        action = action,
                                        baselineLastTx = wsSnapshot.lastTransactionId
                                    )
                                    if (realOrderId.isNotBlank() && realOrderId != retryOrderId) {
                                        lastOrderIdUpdate = realOrderId
                                    }
                                    scheduleTestModeRiskEdit(
                                        action = action,
                                        take = submitTake,
                                        stop = submitStop,
                                        profileId = profileId,
                                        nowSec = nowSec
                                    )
                                } else {
                                    decision = "order rejected (retry): ${retryMessage.ifBlank { "unknown" }}"
                                    gateTrace += " submit=rejected_retry msg=${retryMessage.ifBlank { "unknown" }}"
                                    errorMessage = mergeError(errorMessage, decision)
                                    Log.w(
                                        TAG,
                                        "TEST_MODE retry submit rejected action=$action units=$desiredUnits profile=$profileId tp=$submitTake sl=$submitStop response=$retryRes"
                                    )
                                    appendAuditDebugRow(
                                        profileId,
                                        "ORDER_REJECT_RETRY|${nowSec}|${currentClosedCandleTime}|${sanitizeAuditToken(retryMessage.take(220))}|tp=$submitTake|sl=$submitStop"
                                    )
                                }
                            }.onFailure { retryErr ->
                                decision = "order failed: ${retryErr.message}"
                                gateTrace += " submit=failed_retry err=${retryErr.message ?: "unknown"}"
                                errorMessage = mergeError(errorMessage, decision)
                                if (retryErr is HttpException && retryErr.code() == 429) {
                                    val retryAfterSec = parseRetryAfterSeconds(retryErr)
                                    val waitSec = applyOrderRateLimitBackoff(nowSec, retryAfterSec)
                                    decision = "order rate-limited (429), cooldown ${waitSec}s"
                                    gateTrace += " rate_limit=retry wait=${waitSec}s"
                                }
                                lastSubmitOkUpdate = false
                                lastSubmitMessageUpdate = retryErr.message ?: "submit failed"
                                lastSubmitAtUpdate = nowSec
                                Log.e(TAG, "TEST_MODE retry submit failed profile=$profileId error=${retryErr.message}", retryErr)
                                appendAuditDebugRow(
                                    profileId,
                                    "ORDER_FAIL_RETRY|${nowSec}|${currentClosedCandleTime}|${sanitizeAuditToken(retryErr.message ?: "unknown")}|tp=$submitTake|sl=$submitStop"
                                )
                            }
                        } else {
                            decision = "order failed: ${err.message}"
                            gateTrace += " submit=failed err=${err.message ?: "unknown"}"
                            errorMessage = mergeError(errorMessage, decision)
                            if (err is HttpException && err.code() == 429) {
                                val retryAfterSec = parseRetryAfterSeconds(err)
                                val waitSec = applyOrderRateLimitBackoff(nowSec, retryAfterSec)
                                decision = "order rate-limited (429), cooldown ${waitSec}s"
                                gateTrace += " rate_limit=primary wait=${waitSec}s"
                            }
                            Log.e(TAG, "auto order failed profile=$profileId error=${err.message}", err)
                            appendAuditDebugRow(
                                profileId,
                                "ORDER_FAIL|${nowSec}|${currentClosedCandleTime}|${sanitizeAuditToken(err.message ?: "unknown")}|tp=$submitTake|sl=$submitStop|idem=${sanitizeAuditToken(idempotencyKey)}"
                            )
                        }
                    }
                }
            }
        } else if (shouldProcessClosedCandle && signalType != "close_signal") {
            decision = "skip: signal=$signalType"
            gateTrace += " skip=signal_$signalType"
        }
        if (shouldProcessClosedCandle && signalReason == "position_update_recover_entry") {
            val submitState = when (lastSubmitOkUpdate) {
                true -> "ok"
                false -> "failed"
                else -> "skip"
            }
            val trigger = when {
                recoveryMode && freshEngineEntry -> "recovery+fresh"
                recoveryMode -> "recovery"
                freshEngineEntry -> "fresh"
                else -> "unknown"
            }
            appendPositionUpdatePromoteRow(
                profileId = profileId,
                nowSec = nowSec,
                candleTimeSec = currentClosedCandleTime,
                engineEntryTimeSec = engineEntryTimeSec,
                trigger = trigger,
                promoted = recoveredFromPositionUpdateDrift,
                orderSent = submitState,
                detail = "decision=${sanitizeAuditToken(decision).take(120)}"
            )
        }

        if (hasStableNewClosedCandle) {
            lastProcessedCandleTimeSec = currentClosedCandleTime
            saveLastProcessedCandleTime(activeProfileId, currentClosedCandleTime)
        }
        Log.i(
            TAG,
            "audit profile=$profileId candle=$currentClosedCandleTime newCandle=$hasNewClosedCandle stableNew=$hasStableNewClosedCandle signal=$signalType dir=$signalDirection open=$openPositions pending=$pendingOrders decision=$decision ws=$wsConnected"
        )
        Log.i(TAG, "gate trace profile=$profileId $gateTrace")
        appendGateTraceLog(
            profileId = profileId,
            nowSec = nowSec,
            candleTimeSec = currentClosedCandleTime,
            decision = decision,
            gateTrace = gateTrace
        )
        if (shouldProcessClosedCandle) {
            val closeExitWhy = if (signalType == "close_signal") {
                signal.stringValue("exit_reason", signal.stringValue("reason", ""))
            } else {
                ""
            }
            val closeExitPx = if (signalType == "close_signal") {
                val ep = signal.doubleValue("exit_price", 0.0)
                if (ep > 0.0) ep.roundToInt() else 0
            } else {
                0
            }
            appendAuditCyclePrefRow(
                profileId = profileId,
                nowSec = nowSec,
                candleSec = currentClosedCandleTime,
                prevBaseline = prevProcessedBaseline,
                recoveryMode = recoveryMode,
                missed = missedCandlesCount,
                recoveryReplayMode = recoveryReplayMode,
                recoveryReplayRuns = recoveryReplayRuns,
                signalType = signalType,
                signalDir = signalDirection,
                signalReason = signalReason,
                driftPromoted = recoveredFromPositionUpdateDrift || recoveredFromBrokerOpenEngineFlat,
                setupScore = signal.intValue("setup_score", 0),
                simEntry = signal.stringValue("entry_price", ""),
                simStop = signal.stringValue("stop_loss", ""),
                simTake = signal.stringValue("take_profit", ""),
                engineCandleTime = signal.longValue("candle_time", 0L),
                closeExitWhy = closeExitWhy,
                closeExitPx = closeExitPx,
                openWs = openPositions,
                pendWs = pendingOrders,
                openRest = openPositionsRest,
                pendRest = pendingOrdersRest,
                freeUnits = portfolioUnits,
                wsConnected = wsConnected,
                wsAccountReady = wsAccountReady,
                bid = bestBid,
                ask = bestAsk,
                lastPx = price,
                portfolioId = portfolioId,
                cache1mCount = signalCandles.size,
                cacheReadyFlag = cacheReady,
                decision = decision,
                err = errorMessage,
                dayBt = dayBacktest,
                sessTrades = sessionStats.tradeCount,
                sessNet = sessionStats.netPnl
            )
        }

        AutoTradeStateStore.update {
            it.copy(
                isRunning = true,
                profileId = profileId,
                price = price,
                bestBid = bestBid,
                bestAsk = bestAsk,
                openPositions = openPositions,
                pendingOrders = pendingOrders,
                wsConnected = wsConnected,
                hasPortfolio = hasPortfolio,
                portfolioId = portfolioId,
                portfolioUnits = portfolioUnits,
                userBalance = userBalance,
                signalType = if (shouldProcessClosedCandle) signalType else it.signalType,
                signalDirection = if (shouldProcessClosedCandle) signalDirection else it.signalDirection,
                signalReason = if (shouldProcessClosedCandle) signalReason else it.signalReason,
                sessionTradeCount = if (shouldProcessClosedCandle) sessionStats.tradeCount else it.sessionTradeCount,
                sessionWinCount = if (shouldProcessClosedCandle) sessionStats.winCount else it.sessionWinCount,
                sessionLossCount = if (shouldProcessClosedCandle) sessionStats.lossCount else it.sessionLossCount,
                sessionWinRate = if (shouldProcessClosedCandle) sessionStats.winRate else it.sessionWinRate,
                sessionNetPnl = if (shouldProcessClosedCandle) sessionStats.netPnl else it.sessionNetPnl,
                dayBacktestTradeCount = if (shouldProcessClosedCandle) dayBacktest?.tradeCount ?: 0 else it.dayBacktestTradeCount,
                dayBacktestWinCount = if (shouldProcessClosedCandle) dayBacktest?.winCount ?: 0 else it.dayBacktestWinCount,
                dayBacktestLossCount = if (shouldProcessClosedCandle) dayBacktest?.lossCount ?: 0 else it.dayBacktestLossCount,
                dayBacktestWinRate = if (shouldProcessClosedCandle) dayBacktest?.winRate ?: 0.0 else it.dayBacktestWinRate,
                dayBacktestNetPnl = if (shouldProcessClosedCandle) dayBacktest?.netPnl ?: 0.0 else it.dayBacktestNetPnl,
                dayBacktestSignalsText = if (shouldProcessClosedCandle) dayBacktest?.signalsText.orEmpty() else it.dayBacktestSignalsText,
                orderAttempts = orderAttempts,
                ordersPlaced = ordersPlaced,
                lastOrderId = lastOrderIdUpdate ?: it.lastOrderId,
                lastOrderAction = lastOrderActionUpdate ?: it.lastOrderAction,
                lastOrderUnits = lastOrderUnitsUpdate ?: it.lastOrderUnits,
                lastOrderAtEpochSec = lastOrderAtUpdate ?: it.lastOrderAtEpochSec,
                lastLiveTransactionId = lastLiveTransactionIdUpdate ?: it.lastLiveTransactionId,
                lastSubmitOk = lastSubmitOkUpdate ?: it.lastSubmitOk,
                lastSubmitMessage = lastSubmitMessageUpdate ?: it.lastSubmitMessage,
                lastSubmitAtEpochSec = lastSubmitAtUpdate ?: it.lastSubmitAtEpochSec,
                lastDecision = decision,
                lastGateTrace = gateTrace,
                lastError = errorMessage,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        }
        updateNotification(decision)
        val fastRecheckEnabled = !hasNewClosedCandle &&
            shouldReprocessClosedCandle &&
            hasNoLiveExposureAtGate &&
            signalType == "no_signal" &&
            errorMessage.isBlank() &&
            nowSec >= orderRateLimitUntilEpochSec &&
            nowSec >= closeRateLimitUntilEpochSec
        if (fastRecheckEnabled) {
            Log.i(
                TAG,
                "fast recheck enabled profile=$profileId candle=$currentClosedCandleTime intervalMs=$FAST_RECHECK_INTERVAL_MS"
            )
        }
        return computeNextCycleIntervalMs(
            nowSec = nowSec,
            currentClosedCandleTimeSec = currentClosedCandleTime,
            hasObservedNewClosedCandle = hasNewClosedCandle,
            isNewClosedCandleStable = hasStableNewClosedCandle,
            currentClosedCandleAgeMs = currentClosedCandleAgeMs,
            currentClosedCandleStableChecks = currentClosedCandleStableChecks,
            recoveryMode = recoveryMode,
            hadError = errorMessage.isNotBlank(),
            fastRecheckEnabled = fastRecheckEnabled
        )
    }

    private fun computeNextCycleIntervalMs(
        nowSec: Long,
        currentClosedCandleTimeSec: Long,
        hasObservedNewClosedCandle: Boolean,
        isNewClosedCandleStable: Boolean,
        currentClosedCandleAgeMs: Long,
        currentClosedCandleStableChecks: Int,
        recoveryMode: Boolean,
        hadError: Boolean,
        fastRecheckEnabled: Boolean
    ): Long {
        if (hadError) return LOOP_INTERVAL_MS
        if (recoveryMode) return LOOP_INTERVAL_MS
        if (fastRecheckEnabled) return FAST_RECHECK_INTERVAL_MS
        if (hasObservedNewClosedCandle && !isNewClosedCandleStable) {
            val remainingMs = if (currentClosedCandleAgeMs < CLOSED_CANDLE_STABILIZATION_MS) {
                (CLOSED_CANDLE_STABILIZATION_MS - currentClosedCandleAgeMs)
                    .coerceIn(MIN_ADAPTIVE_WAIT_MS, LOOP_INTERVAL_MS)
            } else {
                CLOSED_CANDLE_CONFIRMATION_POLL_MS
            }
            Log.d(
                TAG,
                "adaptive wait stabilize: ageMs=$currentClosedCandleAgeMs checks=$currentClosedCandleStableChecks remainMs=$remainingMs lastClosed=$currentClosedCandleTimeSec"
            )
            return remainingMs
        }
        if (hasObservedNewClosedCandle) return LOOP_INTERVAL_MS
        if (currentClosedCandleTimeSec <= 0L) return LOOP_INTERVAL_MS

        val deltaFromServerClose = nowSec - currentClosedCandleTimeSec
        if (deltaFromServerClose >= 60L) {
            Log.w(
                TAG,
                "adaptive wait stale-no-new-candle: deltaSec=$deltaFromServerClose nowSec=$nowSec lastClosed=$currentClosedCandleTimeSec -> fallback=$LOOP_INTERVAL_MS"
            )
            return LOOP_INTERVAL_MS
        }
        val isClockOrFeedSuspicious = deltaFromServerClose < SERVER_CLOCK_DELTA_MIN_SEC ||
            deltaFromServerClose > SERVER_CLOCK_DELTA_MAX_SEC
        if (isClockOrFeedSuspicious) {
            Log.w(
                TAG,
                "adaptive wait fallback: suspicious clock/feed deltaSec=$deltaFromServerClose nowSec=$nowSec lastClosed=$currentClosedCandleTimeSec"
            )
            return LOOP_INTERVAL_MS
        }

        val nextCloseSec = currentClosedCandleTimeSec + 60L
        val waitMs = ((nextCloseSec - nowSec) * 1000L) + CANDLE_CLOSE_GRACE_MS
        val adaptive = waitMs.coerceIn(MIN_ADAPTIVE_WAIT_MS, MAX_ADAPTIVE_WAIT_MS)
        Log.d(
            TAG,
            "adaptive wait server-based nowSec=$nowSec lastClosed=$currentClosedCandleTimeSec nextClose=$nextCloseSec deltaSec=$deltaFromServerClose waitMs=$adaptive"
        )
        return adaptive
    }

    private fun computeSessionStats(backtest: JsonObject, startedAtSec: Long): SessionStats {
        val trades = backtest.arrayValue("trades")
        var count = 0
        var wins = 0
        var losses = 0
        var pnl = 0.0
        for (i in 0 until trades.size()) {
            val el = trades[i]
            if (!el.isJsonObject) continue
            val tr = el.asJsonObject
            val exitTime = tr.longValue("exit_time", tr.longValue("exitTime", 0L))
            if (exitTime < startedAtSec) continue
            val net = tr.doubleValue("net_pnl_money", tr.doubleValue("netPnlMoney", 0.0))
            count += 1
            pnl += net
            if (net > 0.0) wins += 1 else losses += 1
        }
        val winRate = if (count > 0) wins.toDouble() / count.toDouble() else 0.0
        return SessionStats(count, wins, losses, winRate, pnl)
    }

    private fun computeDayBacktestSummary(backtest: JsonObject, nowSec: Long): DayBacktestSummary {
        val trades = backtest.arrayValue("trades")
        val fromSec = nowSec - DAY_WINDOW_SEC
        var count = 0
        var wins = 0
        var losses = 0
        var pnl = 0.0
        val rows = mutableListOf<String>()
        for (i in 0 until trades.size()) {
            val el = trades[i]
            if (!el.isJsonObject) continue
            val tr = el.asJsonObject
            val exitTime = tr.longValue("exit_time", tr.longValue("exitTime", 0L))
            val entryTime = tr.longValue("entry_time", tr.longValue("entryTime", 0L))
            val pivotTime = when {
                exitTime > 0L -> exitTime
                entryTime > 0L -> entryTime
                else -> 0L
            }
            if (pivotTime <= 0L || pivotTime < fromSec) continue
            val net = tr.doubleValue("net_pnl_money", tr.doubleValue("netPnlMoney", 0.0))
            count += 1
            pnl += net
            val isWin = net > 0.0
            if (isWin) wins += 1 else losses += 1

            if (rows.size < MAX_BACKTEST_SIGNAL_ROWS) {
                val dir = tr.stringValue("direction", tr.stringValue("action", "-")).ifBlank { "-" }
                val entry = tr.doubleValue("entry_price", tr.doubleValue("entryPrice", 0.0))
                val exit = tr.doubleValue("exit_price", tr.doubleValue("exitPrice", 0.0))
                val reason = tr.stringValue("reason", tr.stringValue("exit_reason", "-")).ifBlank { "-" }
                val ts = formatEpochSec(pivotTime)
                val status = if (isWin) "WIN" else "LOSS"
                rows += "$ts | $dir | e=${formatPriceCompact(entry)} x=${formatPriceCompact(exit)} | net=${formatSignedMoneyCompact(net)} | $status | $reason"
            }
        }
        val winRate = if (count > 0) wins.toDouble() / count.toDouble() else 0.0
        val signalsText = if (rows.isEmpty()) "" else rows.joinToString(separator = "\n")
        return DayBacktestSummary(
            tradeCount = count,
            winCount = wins,
            lossCount = losses,
            winRate = winRate,
            netPnl = pnl,
            signalsText = signalsText
        )
    }

    private fun logBacktestDiagnostics(
        profileId: String,
        backtest: JsonObject,
        dayBacktest: DayBacktestSummary,
        nowSec: Long
    ) {
        val trades = backtest.arrayValue("trades")
        val fromSec = nowSec - DAY_WINDOW_SEC
        val totalTrades = trades.size()
        val inWindow = mutableListOf<String>()
        for (i in 0 until trades.size()) {
            val el = trades[i]
            if (!el.isJsonObject) continue
            val tr = el.asJsonObject
            val exitTime = tr.longValue("exit_time", tr.longValue("exitTime", 0L))
            val entryTime = tr.longValue("entry_time", tr.longValue("entryTime", 0L))
            val pivotTime = when {
                exitTime > 0L -> exitTime
                entryTime > 0L -> entryTime
                else -> 0L
            }
            if (pivotTime <= 0L || pivotTime < fromSec) continue
            val dir = tr.stringValue("direction", tr.stringValue("action", "-")).ifBlank { "-" }
            val entry = tr.doubleValue("entry_price", tr.doubleValue("entryPrice", 0.0))
            val exit = tr.doubleValue("exit_price", tr.doubleValue("exitPrice", 0.0))
            val net = tr.doubleValue("net_pnl_money", tr.doubleValue("netPnlMoney", 0.0))
            val reason = tr.stringValue("reason", tr.stringValue("exit_reason", "-")).ifBlank { "-" }
            val status = if (net > 0.0) "WIN" else "LOSS"
            inWindow += "${formatEpochSec(pivotTime)}|$dir|e=${formatPriceCompact(entry)}|x=${formatPriceCompact(exit)}|net=${formatSignedMoneyCompact(net)}|$status|$reason"
        }

        Log.i(
            TAG,
            "backtest diag profile=$profileId totalTrades=$totalTrades dayTrades=${dayBacktest.tradeCount} dayWins=${dayBacktest.winCount} dayLosses=${dayBacktest.lossCount} dayWinRate=${String.format(Locale.US, "%.4f", dayBacktest.winRate)} dayPnl=${formatSignedMoneyCompact(dayBacktest.netPnl)} fromSec=$fromSec nowSec=$nowSec"
        )

        if (inWindow.isEmpty()) {
            Log.i(TAG, "backtest diag profile=$profileId day trade rows: empty")
            return
        }

        val recentRows = inWindow.takeLast(MAX_BACKTEST_LOG_ROWS)
        for ((idx, row) in recentRows.withIndex()) {
            Log.i(TAG, "backtest row[$idx/${recentRows.size}] profile=$profileId $row")
        }
    }

    private fun updateNotification(decision: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(decision))
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.auto_trade_notification_title))
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.auto_trade_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun extractArrayCount(obj: JsonObject, vararg keys: String): Int {
        for (key in keys) {
            val el = obj.get(key) ?: continue
            if (el.isJsonArray) return el.asJsonArray.size()
        }
        return 0
    }

    private fun extractDouble(obj: JsonObject, keys: List<String>, fallback: Double): Double {
        for (key in keys) {
            val el = obj.get(key) ?: continue
            if (el.isJsonNull) continue
            val value = runCatching { el.asDouble }.getOrNull()
            if (value != null) return value
            val text = runCatching { el.asString }.getOrNull()
            val parsed = text?.toDoubleOrNull()
            if (parsed != null) return parsed
        }
        return fallback
    }

    private fun extractObjectArray(obj: JsonObject, vararg keys: String): List<JsonObject> {
        for (key in keys) {
            val el = obj.get(key) ?: continue
            if (!el.isJsonArray) continue
            val arr = el.asJsonArray
            val rows = mutableListOf<JsonObject>()
            for (i in 0 until arr.size()) {
                val row = arr[i]
                if (row.isJsonObject) rows += row.asJsonObject
            }
            return rows
        }
        return emptyList()
    }

    private fun mergeError(current: String, next: String): String {
        if (current.isBlank()) return next
        return "$current | $next"
    }

    private suspend fun warmupCandleCache(nowSec: Long): CacheSyncResult {
        val fromSec = (nowSec - WARMUP_WINDOW_SEC).coerceAtLeast(0L)
        val oneMinuteFetch = fetchBarsForCacheTrace(
            profileId = activeProfileId,
            nowSec = nowSec,
            candleTimeSec = cache1m.lastOrNull()?.time ?: 0L,
            resolution = "1",
            fromSec = fromSec,
            toSec = nowSec
        )
        val oneMinute = oneMinuteFetch.candles
        if (oneMinute.isEmpty()) {
            cacheReady = false
            return CacheSyncResult(
                ok = false,
                error = if (oneMinuteFetch.error.isBlank()) "warmup failed: 1m empty" else mergeError("warmup failed: 1m empty", oneMinuteFetch.error),
                candles1m = 0,
                mtfCounts = emptyMap()
            )
        }

        val mtfLoaded = mutableMapOf<Int, Int>()
        cache1m.clear()
        cache1m += normalizeCandles(oneMinute, fromSec)
        rebuildMtfCacheFrom1m(
            nowSec = nowSec,
            candleTimeSec = cache1m.lastOrNull()?.time ?: 0L,
            minWindowSec = fromSec,
            outCounts = mtfLoaded
        )
        cacheReady = true
        lastCacheSyncEpochSec = nowSec
        Log.i(
            TAG,
            "cache warmup ok profile=$activeProfileId from=$fromSec to=$nowSec 1m=${cache1m.size} mtf=${mtfLoaded.entries.sortedBy { it.key }.joinToString(",") { "${it.key}m:${it.value}" }}"
        )
        return CacheSyncResult(
            ok = true,
            error = "",
            candles1m = cache1m.size,
            mtfCounts = mtfLoaded
        )
    }

    private suspend fun refreshCandleCache(nowSec: Long): CacheSyncResult {
        if (!cacheReady || cache1m.isEmpty()) {
            return warmupCandleCache(nowSec)
        }
        val minWindowSec = (nowSec - WARMUP_WINDOW_SEC).coerceAtLeast(0L)
        var error = ""
        val cycleBaselineCandle = cache1m.lastOrNull()?.time ?: 0L

        // Keep a wider overlap window so delayed feed revisions on recent bars are merged into cache.
        val oneMinuteFrom = (cache1m.last().time - DELTA_OVERLAP_1M_SEC).coerceAtLeast(minWindowSec)
        val oneMinuteFetch = fetchBarsForCacheTrace(
            profileId = activeProfileId,
            nowSec = nowSec,
            candleTimeSec = cycleBaselineCandle,
            resolution = "1",
            fromSec = oneMinuteFrom,
            toSec = nowSec
        )
        val oneMinuteDelta = oneMinuteFetch.candles
        if (oneMinuteDelta.isEmpty()) {
            error = mergeError(error, "cache refresh 1m empty")
            if (oneMinuteFetch.error.isNotBlank()) {
                error = mergeError(error, "cache refresh 1m err=${oneMinuteFetch.error}")
            }
        } else {
            val oneMinuteStats = mergeIntoCache(cache1m, oneMinuteDelta, minWindowSec)
            appendCandleRevisionRow(
                profileId = activeProfileId,
                nowSec = nowSec,
                candleTimeSec = cycleBaselineCandle,
                tfLabel = "1m",
                stats = oneMinuteStats
            )
        }

        val mtfCounts = mutableMapOf<Int, Int>()
        rebuildMtfCacheFrom1m(
            nowSec = nowSec,
            candleTimeSec = cycleBaselineCandle,
            minWindowSec = minWindowSec,
            outCounts = mtfCounts
        )

        lastCacheSyncEpochSec = nowSec
        val ok = cache1m.isNotEmpty()
        if (!ok) cacheReady = false
        if (ok) {
            Log.d(
                TAG,
                "cache refresh ok profile=$activeProfileId 1m=${cache1m.size} mtf=${mtfCounts.entries.sortedBy { it.key }.joinToString(",") { "${it.key}m:${it.value}" }} lastSync=$lastCacheSyncEpochSec"
            )
        }
        return CacheSyncResult(
            ok = ok,
            error = error,
            candles1m = cache1m.size,
            mtfCounts = mtfCounts
        )
    }

    private fun snapshotMtfCache(): Map<Int, List<LocalCandle>> {
        return cacheMtf.mapValues { (_, v) -> v.toList() }
    }

    private fun rebuildMtfCacheFrom1m(
        nowSec: Long,
        candleTimeSec: Long,
        minWindowSec: Long,
        outCounts: MutableMap<Int, Int>
    ) {
        for (tf in MTF_RESOLUTIONS) {
            val previous = cacheMtf[tf].orEmpty()
            val rebuilt = buildAggregatedCandlesFrom1m(
                candles1m = cache1m,
                timeframeMin = tf,
                minKeepTimeSec = minWindowSec
            )
            val stats = diffRevisionStats(previous, rebuilt)
            appendCandleRevisionRow(
                profileId = activeProfileId,
                nowSec = nowSec,
                candleTimeSec = candleTimeSec,
                tfLabel = "${tf}m",
                stats = stats
            )
            cacheMtf[tf] = rebuilt.toMutableList()
            outCounts[tf] = rebuilt.size
        }
    }

    private fun buildAggregatedCandlesFrom1m(
        candles1m: List<LocalCandle>,
        timeframeMin: Int,
        minKeepTimeSec: Long
    ): List<LocalCandle> {
        if (candles1m.isEmpty() || timeframeMin <= 1) {
            return normalizeCandles(candles1m, minKeepTimeSec)
        }
        val tfSec = timeframeMin * 60L
        val grouped = linkedMapOf<Long, MutableList<LocalCandle>>()
        for (c in candles1m) {
            if (c.time < minKeepTimeSec) continue
            val bucket = (c.time / tfSec) * tfSec
            val rows = grouped.getOrPut(bucket) { mutableListOf() }
            rows += c
        }
        val out = mutableListOf<LocalCandle>()
        for ((bucket, rows) in grouped) {
            if (rows.size < timeframeMin) continue
            val ordered = rows.sortedBy { it.time }
            val open = ordered.first().open
            var high = ordered.first().high
            var low = ordered.first().low
            for (r in ordered) {
                if (r.high > high) high = r.high
                if (r.low < low) low = r.low
            }
            val close = ordered.last().close
            out += LocalCandle(
                time = bucket,
                open = open,
                high = high,
                low = low,
                close = close
            )
        }
        return out
    }

    private fun diffRevisionStats(
        previous: List<LocalCandle>,
        current: List<LocalCandle>
    ): MergeRevisionStats {
        val oldByTime = previous.associateBy { it.time }
        var revisedCount = 0
        var addedCount = 0
        val revisedSamples = mutableListOf<String>()
        for (next in current.sortedBy { it.time }) {
            val old = oldByTime[next.time]
            if (old == null) {
                addedCount += 1
                continue
            }
            val changed = old.open != next.open ||
                old.high != next.high ||
                old.low != next.low ||
                old.close != next.close
            if (!changed) continue
            revisedCount += 1
            if (revisedSamples.size < 5) {
                revisedSamples += "${next.time}:${compactOhlc(old)}->${compactOhlc(next)}"
            }
        }
        return MergeRevisionStats(
            revisedCount = revisedCount,
            addedCount = addedCount,
            beforeSize = previous.size,
            afterSize = current.size,
            revisedSamples = revisedSamples
        )
    }

    private fun mergeIntoCache(
        target: MutableList<LocalCandle>,
        incoming: List<LocalCandle>,
        minKeepTimeSec: Long
    ): MergeRevisionStats {
        if (incoming.isEmpty()) {
            return MergeRevisionStats(
                revisedCount = 0,
                addedCount = 0,
                beforeSize = target.size,
                afterSize = target.size,
                revisedSamples = emptyList()
            )
        }
        val beforeSize = target.size
        val oldByTime = target.associateBy { it.time }
        var revisedCount = 0
        var addedCount = 0
        val revisedSamples = mutableListOf<String>()
        incoming.sortedBy { it.time }.forEach { next ->
            val old = oldByTime[next.time]
            if (old == null) {
                addedCount += 1
                return@forEach
            }
            val changed = old.open != next.open ||
                old.high != next.high ||
                old.low != next.low ||
                old.close != next.close
            if (!changed) return@forEach
            revisedCount += 1
            if (revisedSamples.size < 5) {
                revisedSamples += "${next.time}:${compactOhlc(old)}->${compactOhlc(next)}"
            }
        }
        target += incoming
        val normalized = normalizeCandles(target, minKeepTimeSec)
        target.clear()
        target += normalized
        return MergeRevisionStats(
            revisedCount = revisedCount,
            addedCount = addedCount,
            beforeSize = beforeSize,
            afterSize = target.size,
            revisedSamples = revisedSamples
        )
    }

    private fun normalizeCandles(candles: List<LocalCandle>, minKeepTimeSec: Long): List<LocalCandle> {
        if (candles.isEmpty()) return emptyList()
        val byTime = LinkedHashMap<Long, LocalCandle>()
        candles.sortedBy { it.time }.forEach { c ->
            if (c.time >= minKeepTimeSec) byTime[c.time] = c
        }
        return byTime.values.toList()
    }

    private suspend fun recoverSignalAfterGap(
        profileId: String,
        candles: List<org.linphone.incomingcall.bot.local.LocalCandle>,
        fromExclusive: Long,
        toInclusive: Long,
        preloadedMtf: Map<Int, List<LocalCandle>>
    ): RecoveryResult {
        if (candles.isEmpty()) {
            return RecoveryResult(
                signal = JsonObject().apply { addProperty("type", "no_signal") },
                mode = "empty",
                replayRuns = 0
            )
        }
        val replayTimes = candles.asSequence()
            .map { it.time }
            .filter { it > fromExclusive && it <= toInclusive }
            .toList()
        if (replayTimes.isEmpty()) {
            return RecoveryResult(
                signal = JsonObject().apply { addProperty("type", "no_signal") },
                mode = "none",
                replayRuns = 0
            )
        }

        val replayPlan = replayTimes
        var last = JsonObject().apply { addProperty("type", "no_signal") }
        for (t in replayPlan) {
            val slice = candles.takeWhile { it.time <= t }
            if (slice.size < 2) continue
            Log.d(TAG, "replay step mode=full candle=$t size=${slice.size}")
            last = runCatching { LocalBotRuntime.marketSignalFromCandles(profileId, slice, preloadedMtf) }
                .getOrElse {
                    JsonObject().apply {
                        addProperty("type", "no_signal")
                        addProperty("reason", "replay_signal_error")
                    }
                }
        }
        return RecoveryResult(
            signal = last,
            mode = "full",
            replayRuns = replayPlan.size
        )
    }

    private suspend fun verifyRecoverEntryPromotionParity(
        profileId: String,
        candles: List<LocalCandle>,
        preloadedMtf: Map<Int, List<LocalCandle>>?,
        currentClosedCandleTime: Long,
        engineEntryTimeSec: Long,
        expectedDirection: String
    ): RecoverEntryParity {
        if (engineEntryTimeSec <= 0L) {
            return RecoverEntryParity(confirmed = false, code = "missing_entry_time", detail = "missing_entry_time")
        }
        if (engineEntryTimeSec > currentClosedCandleTime) {
            return RecoverEntryParity(
                confirmed = false,
                code = "future_entry",
                detail = "future_entry entry=$engineEntryTimeSec candle=$currentClosedCandleTime"
            )
        }
        val replaySlice = candles.takeWhile { it.time <= engineEntryTimeSec }
        if (replaySlice.size < 2) {
            return RecoverEntryParity(
                confirmed = false,
                code = "insufficient_slice",
                detail = "insufficient_slice size=${replaySlice.size}"
            )
        }
        val replaySignal = runCatching {
            LocalBotRuntime.marketSignalFromCandles(
                profileId = profileId,
                candles = replaySlice,
                preloadedMtf = preloadedMtf
            )
        }.getOrElse { err ->
            return RecoverEntryParity(
                confirmed = false,
                code = "replay_error",
                detail = "replay_error ${sanitizeAuditToken(err.message ?: "unknown")}"
            )
        }
        val replayType = replaySignal.stringValue("type", "no_signal")
        val replayEntryTime = replaySignal.longValue("entry_time", 0L)
        val replayCandleTime = replaySignal.longValue("candle_time", 0L)
        val replayDirection = replaySignal.stringValue("direction", "").lowercase(Locale.US)
        val expectedDir = expectedDirection.lowercase(Locale.US)
        val sameEntry = replayEntryTime > 0L && replayEntryTime == engineEntryTimeSec
        val sameCandle = replayCandleTime <= 0L || replayCandleTime == engineEntryTimeSec
        val sameDirection = expectedDir.isBlank() || replayDirection == expectedDir
        val confirmed = replayType == "signal" && sameEntry && sameCandle && sameDirection
        val code = when {
            confirmed -> "ok"
            replayType != "signal" -> "mismatch_type"
            !sameEntry -> "mismatch_entry_time"
            !sameCandle -> "mismatch_candle_time"
            !sameDirection -> "mismatch_direction"
            else -> "mismatch_unknown"
        }
        val detail = buildString {
            append("type=").append(replayType)
            append(" entry=").append(replayEntryTime)
            append(" candle=").append(replayCandleTime)
            append(" dir=").append(if (replayDirection.isBlank()) "-" else replayDirection)
            append(" sameEntry=").append(sameEntry)
            append(" sameCandle=").append(sameCandle)
            append(" sameDir=").append(sameDirection)
        }
        return RecoverEntryParity(confirmed = confirmed, code = code, detail = detail)
    }

    private fun computeOrderUnits(profileId: String, setupScore: Int, freeUnits: Int, signalQuantityHint: Int): Int {
        val profileUnits = when {
            signalQuantityHint > 0 -> signalQuantityHint
            profileId == "scaled_units" -> when {
                setupScore >= 12 -> 3
                setupScore >= 10 -> 2
                else -> 1
            }
            else -> 1
        }
        return profileUnits.coerceAtMost(freeUnits.coerceAtLeast(0)).coerceAtLeast(0)
    }

    /**
     * Best reference for where a verbal (market) order will fill: ask for buys, bid for sells, else last.
     */
    private fun marketReferenceForVerbalOrder(
        action: String,
        bestBid: Double,
        bestAsk: Double,
        lastPrice: Double,
        entryFallback: Int
    ): Int = when {
        action == "buy" && bestAsk > 0.0 -> bestAsk.roundToInt()
        action == "sell" && bestBid > 0.0 -> bestBid.roundToInt()
        lastPrice > 0.0 -> lastPrice.roundToInt()
        else -> entryFallback
    }

    /**
     * When promoting [position_update] to a live entry, TP/SL from the engine are anchored to the
     * *simulated* entry. Verbal orders fill near [marketRef]. Preserve SL/TP *distances in points*
     * from the simulated entry (same geometry as backtest for that trade), then apply the same
     * minimal offset guard as test mode so the broker sees a valid bracket.
     */
    private fun rebaseDriftRecoverTpSl(
        action: String,
        entry: Double,
        stop: Double,
        take: Double,
        marketRef: Int
    ): Pair<Int, Int> {
        val e = entry.roundToInt()
        val s = stop.roundToInt()
        val t = take.roundToInt()
        val m = marketRef.coerceAtLeast(1)
        val (rawTake, rawStop) = if (action == "buy") {
            val slDist = (e - s).coerceAtLeast(0)
            val tpDist = (t - e).coerceAtLeast(0)
            Pair((m + tpDist).coerceAtLeast(1), (m - slDist).coerceAtLeast(1))
        } else {
            val slDist = (s - e).coerceAtLeast(0)
            val tpDist = (e - t).coerceAtLeast(0)
            Pair((m - tpDist).coerceAtLeast(1), (m + slDist).coerceAtLeast(1))
        }
        return if (action == "buy") {
            val t2 = rawTake.coerceAtLeast(m + 2)
            val s2 = rawStop.coerceAtMost((m - 2).coerceAtLeast(1))
            Pair(t2, s2)
        } else {
            val t2 = rawTake.coerceAtMost((m - 2).coerceAtLeast(1))
            val s2 = rawStop.coerceAtLeast(m + 2)
            Pair(t2, s2)
        }
    }

    private fun loadLastProcessedCandleTime(profileId: String): Long {
        return prefs.getLong("last_processed_candle_sec_$profileId", 0L)
    }

    private fun saveLastProcessedCandleTime(profileId: String, candleTime: Long) {
        prefs.edit().putLong("last_processed_candle_sec_$profileId", candleTime).apply()
    }

    private fun engineOpenSnapshotPrefsKey(profileId: String) = "last_engine_open_snapshot_json_$profileId"

    private fun clearLastEngineOpenSnapshot(profileId: String) {
        prefs.edit().remove(engineOpenSnapshotPrefsKey(profileId)).apply()
    }

    /**
     * Persist last known engine TP/SL geometry while a live position should exist.
     * Used when replay returns [no_signal] but the broker still shows an open position
     * (common after [position_update_recover_entry] + market fill vs OHLC-only replay).
     */
    private fun saveLastEngineOpenSnapshot(profileId: String, signal: JsonObject) {
        val entry = signal.stringValue("entry_price", "")
        val stop = signal.stringValue("stop_loss", "")
        val take = signal.stringValue("take_profit", "")
        val dir = signal.stringValue("direction", "").lowercase(Locale.US)
        if (entry.isBlank() || stop.isBlank() || take.isBlank() || (dir != "long" && dir != "short")) {
            return
        }
        val snap = JsonObject().apply {
            addProperty("direction", dir)
            addProperty("entry_price", entry)
            addProperty("stop_loss", stop)
            addProperty("take_profit", take)
            addProperty("setup_score", signal.intValue("setup_score", 0))
            addProperty("quantity", signal.intValue("quantity", 0))
            addProperty("entry_time", signal.longValue("entry_time", 0L))
        }
        prefs.edit().putString(engineOpenSnapshotPrefsKey(profileId), snap.toString()).apply()
    }

    private fun loadLastEngineOpenSnapshot(profileId: String): JsonObject? {
        val raw = prefs.getString(engineOpenSnapshotPrefsKey(profileId), null)?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
    }

    private fun snapshotToPositionUpdateSignal(snap: JsonObject, candleTimeSec: Long): JsonObject {
        return JsonObject().apply {
            addProperty("type", "position_update")
            addProperty("direction", snap.stringValue("direction", ""))
            addProperty("entry_price", snap.stringValue("entry_price", ""))
            addProperty("stop_loss", snap.stringValue("stop_loss", ""))
            addProperty("take_profit", snap.stringValue("take_profit", ""))
            addProperty("setup_score", snap.intValue("setup_score", 0))
            addProperty("quantity", snap.intValue("quantity", 0))
            addProperty("entry_time", snap.longValue("entry_time", 0L))
            addProperty("candle_time", candleTimeSec)
        }
    }

    private fun normalizedBrokerOpenDirection(
        wsSnapshot: HivaPriceSocketClient.Snapshot,
        openPositionsList: List<JsonObject>,
        wsAccountReady: Boolean
    ): String? {
        val refs = resolveOpenPositionRefs(wsSnapshot, openPositionsList, wsAccountReady)
        if (refs.isEmpty()) return null
        val a = refs.first().action.lowercase(Locale.US)
        return when {
            a.contains("buy") || a.contains("long") -> "long"
            a.contains("sell") || a.contains("short") -> "short"
            else -> null
        }
    }

    private fun buildCloseSignalFromBacktest(
        backtest: JsonObject,
        candleTimeSec: Long,
        expectedDirection: String,
        liveEntryTimeSec: Long
    ): JsonObject? {
        val trades = backtest.arrayValue("trades")
        if (trades.size() == 0) return null
        var picked: JsonObject? = null
        for (i in trades.size() - 1 downTo 0) {
            val el = trades[i]
            if (!el.isJsonObject) continue
            val tr = el.asJsonObject
            val exitTime = tr.longValue("exit_time", tr.longValue("exitTime", 0L))
            if (exitTime > candleTimeSec) continue
            
            val entryTime = tr.longValue("entry_time", tr.longValue("entryTime", 0L))
            if (liveEntryTimeSec > 0L && entryTime > 0L && entryTime != liveEntryTimeSec) {
                continue
            }
            if (liveEntryTimeSec <= 0L && (candleTimeSec - exitTime) > 1800L) {
                continue
            }

            // `endOfData` is a synthetic backtest-only close at dataset boundary.
            // Never promote it to a real broker close in live parity flow.
            val exitReason = tr.stringValue("exit_reason", tr.stringValue("exitReason", "backtest_close"))
            if (exitReason.equals("endOfData", ignoreCase = true)) continue

            val dir = tr.stringValue("direction", "").lowercase(Locale.US)
            if (expectedDirection.isNotBlank() && dir.isNotBlank() && dir != expectedDirection) continue
            picked = tr
            break
        }
        val row = picked ?: return null
        val direction = row.stringValue("direction", expectedDirection)
        val exitReason = row.stringValue("exit_reason", row.stringValue("exitReason", "backtest_close"))
        val exitPrice = row.doubleValue("exit_price", row.doubleValue("exitPrice", 0.0))
        val pnlPts = row.doubleValue("pnl_price_points", row.doubleValue("pnlPricePoints", 0.0))
        return JsonObject().apply {
            addProperty("type", "close_signal")
            addProperty("direction", direction)
            addProperty("exit_reason", exitReason)
            addProperty("reason", "backtest_replay_close")
            addProperty("exit_price", if (exitPrice > 0.0) exitPrice else 0.0)
            addProperty("pnl_price_points", pnlPts)
            addProperty("candle_time", candleTimeSec)
        }
    }

    private fun verifyEntryAgainstBacktest(
        backtest: JsonObject,
        expectedEntryTimeSec: Long,
        expectedDirection: String
    ): EntryBacktestParity {
        if (expectedEntryTimeSec <= 0L) {
            return EntryBacktestParity(
                confirmed = false,
                code = "missing_entry_time",
                detail = "expected_entry_time_missing"
            )
        }
        val trades = backtest.arrayValue("trades")
        if (trades.size() == 0) {
            return EntryBacktestParity(
                confirmed = false,
                code = "backtest_empty",
                detail = "no_trades_in_backtest"
            )
        }
        val expectedDir = expectedDirection.lowercase(Locale.US)
        for (i in trades.size() - 1 downTo 0) {
            val el = trades[i]
            if (!el.isJsonObject) continue
            val tr = el.asJsonObject
            val entryTime = tr.longValue("entry_time", tr.longValue("entryTime", 0L))
            if (entryTime != expectedEntryTimeSec) continue
            val dir = tr.stringValue("direction", tr.stringValue("action", "")).lowercase(Locale.US)
            val sameDirection = expectedDir.isBlank() || dir == expectedDir
            if (!sameDirection) {
                return EntryBacktestParity(
                    confirmed = false,
                    code = "direction_mismatch",
                    detail = "entry=$entryTime expectedDir=${if (expectedDir.isBlank()) "-" else expectedDir} btDir=${if (dir.isBlank()) "-" else dir}"
                )
            }
            return EntryBacktestParity(
                confirmed = true,
                code = "ok",
                detail = "entry=$entryTime dir=${if (dir.isBlank()) "-" else dir}"
            )
        }
        return EntryBacktestParity(
            confirmed = false,
            code = "entry_not_found",
            detail = "entry=$expectedEntryTimeSec dir=${if (expectedDir.isBlank()) "-" else expectedDir} trades=${trades.size()}"
        )
    }

    private fun loadExecutedEntryKeys(profileId: String): Set<String> {
        val key = "executed_entry_keys_$profileId"
        return prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()
    }

    private fun recordExecutedEntryKey(profileId: String, entryKey: String) {
        if (entryKey.isBlank()) return
        executedEntryKeys += entryKey
        // Keep bounded set so prefs size doesn't grow forever.
        if (executedEntryKeys.size > 300) {
            val trimCount = executedEntryKeys.size - 300
            val toRemove = executedEntryKeys.take(trimCount)
            toRemove.forEach { executedEntryKeys.remove(it) }
        }
        val key = "executed_entry_keys_$profileId"
        prefs.edit().putStringSet(key, executedEntryKeys.toSet()).apply()
        Log.i(TAG, "idempotency record profile=$profileId key=$entryKey totalKeys=${executedEntryKeys.size}")
    }

    private fun loadExecutedCloseKeys(profileId: String): Set<String> {
        val key = "executed_close_keys_$profileId"
        return prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()
    }

    private fun recordExecutedCloseKey(profileId: String, closeKey: String) {
        if (closeKey.isBlank()) return
        executedCloseKeys += closeKey
        if (executedCloseKeys.size > 300) {
            val trimCount = executedCloseKeys.size - 300
            val toRemove = executedCloseKeys.take(trimCount)
            toRemove.forEach { executedCloseKeys.remove(it) }
        }
        val key = "executed_close_keys_$profileId"
        prefs.edit().putStringSet(key, executedCloseKeys.toSet()).apply()
        Log.i(TAG, "close idempotency record profile=$profileId key=$closeKey totalKeys=${executedCloseKeys.size}")
    }

    /**
     * When Python engine emits [close_signal] (same replay as backtest: stopLoss, takeProfit, trailingStop, strategyExit),
     * close matching broker position(s) via [HivaGoldApi.closeFuturesTransaction] — same endpoint as the demo site.
     * Position ids come from WS snapshot [HivaPriceSocketClient.Snapshot.openTransactions] (see [HivaPriceSocketClient.TransactionRef]),
     * or from REST `checkPortfolio` open list if WS list is empty / not ready — never from order-book `data_buy` ids.
     */
    private suspend fun executeEngineCloseSignal(
        signal: JsonObject,
        profileId: String,
        portfolioId: String,
        candleTimeSec: Long,
        nowSec: Long,
        wsSnapshot: HivaPriceSocketClient.Snapshot,
        openPositionsList: List<JsonObject>,
        wsAccountReady: Boolean
    ): EngineCloseOutcome {
        val exitReason = signal.stringValue("exit_reason", signal.stringValue("reason", "")).ifBlank { "unknown" }
        val signalDirection = signal.stringValue("direction", "").lowercase()
        val exitPrice = signal.doubleValue("exit_price", 0.0)
        val pnlPtsStr = signal.stringValue("pnl_price_points", "").ifBlank {
            val d = signal.doubleValue("pnl_price_points", 0.0)
            if (d != 0.0) String.format(Locale.US, "%.2f", d) else ""
        }
        val exitPxKey = if (exitPrice > 0.0) exitPrice.roundToInt() else 0
        val idemKey = "$profileId|$portfolioId|$candleTimeSec|close|$exitReason|$signalDirection|$exitPxKey"
        if (executedCloseKeys.contains(idemKey)) {
            appendAuditDebugRow(
                profileId,
                "CLOSE_IDEM|${nowSec}|${candleTimeSec}|${sanitizeAuditToken(idemKey.take(200))}|why=${sanitizeAuditToken(exitReason)}|dir=$signalDirection|engPx=$exitPxKey"
            )
            logCloseEnginePrefRow(
                profileId, nowSec, candleTimeSec,
                "outcome=idempotent; reason=$exitReason; dir=$signalDirection; engine_exit_px=${formatPriceCompact(exitPrice)}; pnl_pts=$pnlPtsStr"
            )
            return EngineCloseOutcome(
                decision = "skip: close_signal idempotent key=$idemKey",
                gateSuffix = " skip=close_idempotent"
            )
        }
        val candidates = mutableListOf<HivaPriceSocketClient.TransactionRef>()
        val allOpenRefs = resolveOpenPositionRefs(wsSnapshot, openPositionsList, wsAccountReady)
        for (tx in allOpenRefs) {
            if (signalDirection.isBlank() || engineDirectionMatchesBrokerAction(signalDirection, tx.action)) {
                candidates += tx
            }
        }
        if (candidates.isEmpty()) {
            appendAuditDebugRow(
                profileId,
                "CLOSE_NOOP|${nowSec}|${candleTimeSec}|why=${sanitizeAuditToken(exitReason)}|dir=$signalDirection|engPx=$exitPxKey|pnlPts=${sanitizeAuditToken(pnlPtsStr)}|wsAcc=$wsAccountReady"
            )
            logCloseEnginePrefRow(
                profileId, nowSec, candleTimeSec,
                "outcome=noop_no_open_broker; reason=$exitReason; dir=$signalDirection; engine_exit_px=${formatPriceCompact(exitPrice)}; pnl_pts=$pnlPtsStr"
            )
            return EngineCloseOutcome(
                decision = "close_signal: no matching open position (engine=$exitReason @${formatPriceCompact(exitPrice)})",
                gateSuffix = " close=noop reason=$exitReason"
            )
        }
        var ok = 0
        var errMsg = ""
        val emptyCloseBody = JsonObject()
        for (tx in candidates) {
            if (nowSec < closeRateLimitUntilEpochSec) {
                val remainSec = (closeRateLimitUntilEpochSec - nowSec).coerceAtLeast(1L)
                errMsg = mergeError(errMsg, "close cooldown ${remainSec}s")
                Log.w(TAG, "engine close_signal skip id=${tx.id} cooldown=${remainSec}s")
                continue
            }
            val idLong = tx.id.toLongOrNull() ?: 0L
            runCatching { HivaGoldClient.api.closeMazanehTransaction(idLong, emptyCloseBody) }
                .onSuccess { res ->
                    consecutiveCloseRateLimitHits = 0
                    closeRateLimitUntilEpochSec = 0L
                    val st = res.boolValue("status", true)
                    val msg = res.stringValue("message", "")
                    if (st) {
                        ok += 1
                        lastEditedStopLoss.remove(tx.id)
                        lastEditedTakeProfit.remove(tx.id)
                    } else {
                        errMsg = mergeError(errMsg, msg.ifBlank { "close rejected" })
                    }
                    Log.i(TAG, "engine close_signal positionId=${tx.id} exitReason=$exitReason exitRef=$exitPxKey ok=$st response=$res")
                }
                .onFailure { err ->
                    if (err is HttpException && err.code() == 429) {
                        val retryAfterSec = parseRetryAfterSeconds(err)
                        val waitSec = applyCloseRateLimitBackoff(nowSec, retryAfterSec)
                        errMsg = mergeError(errMsg, "HTTP 429 cooldown ${waitSec}s")
                    }
                    errMsg = mergeError(errMsg, err.message ?: "close failed")
                    Log.w(TAG, "engine close_signal failed id=${tx.id} err=${err.message}")
                }
        }
        if (ok > 0) {
            recordExecutedCloseKey(profileId, idemKey)
        }
        val closedIds = candidates.map { it.id }.joinToString(",")
        appendAuditDebugRow(
            profileId,
            buildString {
                append("CLOSE_BROKER|").append(nowSec).append("|").append(candleTimeSec).append("|")
                append("ok=").append(ok).append("|n=").append(candidates.size).append("|")
                append("why=").append(sanitizeAuditToken(exitReason).take(48)).append("|")
                append("dir=").append(signalDirection).append("|")
                append("engPx=").append(exitPxKey).append("|")
                append("ids=").append(sanitizeAuditToken(closedIds.take(220))).append("|")
                append("err=").append(sanitizeAuditToken(errMsg.take(160)))
            }
        )
        logCloseEnginePrefRow(
            profileId, nowSec, candleTimeSec,
            buildString {
                append("outcome=")
                append(if (ok > 0) "broker_close_sent" else "broker_close_failed")
                append("; reason=").append(exitReason)
                append("; dir=").append(signalDirection)
                append("; engine_exit_px=").append(formatPriceCompact(exitPrice))
                append("; pnl_pts=").append(pnlPtsStr.ifBlank { "-" })
                append("; pos_ids=").append(closedIds)
                append("; closed_ok=").append(ok)
                append("; n=").append(candidates.size)
                if (errMsg.isNotBlank()) append("; err=").append(errMsg.replace(';', ','))
            }
        )
        val decisionBody = buildString {
            append("close_signal: closed=$ok/$exitReason")
            if (exitPxKey > 0) append(" refPx=$exitPxKey")
            if (errMsg.isNotBlank()) append(" err=").append(errMsg)
        }
        return EngineCloseOutcome(
            decision = decisionBody,
            gateSuffix = " close=engine n=$ok reason=$exitReason"
        )
    }

    /** Same prefs storage as [appendGateTraceLog] (`gate_trace_rows_$profileId`), row tag `CLOSE_ENGINE`. */
    private fun logCloseEnginePrefRow(profileId: String, nowSec: Long, candleTimeSec: Long, detail: String) {
        val safeDetail = detail.replace('\n', ' ').trim()
        appendGateTraceRawRow(profileId, "$nowSec|$candleTimeSec|CLOSE_ENGINE|$safeDetail")
        Log.i(TAG, "CLOSE_ENGINE profile=$profileId candle=$candleTimeSec detail=$safeDetail")
    }

    private fun appendGateTraceRawRow(profileId: String, row: String) {
        val storageKey = "gate_trace_rows_$profileId"
        val previous = prefs.getString(storageKey, "").orEmpty()
        val rows = if (previous.isBlank()) mutableListOf() else previous.split('\n').toMutableList()
        val safe = row.replace('\n', ' ').trim()
        rows += safe
        if (rows.size > MAX_PERSISTED_GATE_TRACE_ROWS) {
            val dropCount = rows.size - MAX_PERSISTED_GATE_TRACE_ROWS
            repeat(dropCount) { rows.removeAt(0) }
        }
        prefs.edit().putString(storageKey, rows.joinToString(separator = "\n")).apply()
    }

    private suspend fun fetchBarsForCacheTrace(
        profileId: String,
        nowSec: Long,
        candleTimeSec: Long,
        resolution: String,
        fromSec: Long,
        toSec: Long
    ): BarsFetchResult {
        val startedAtMs = System.currentTimeMillis()
        var fetchError = ""
        val bars = runCatching {
            withTimeoutOrNull(IO_TIMEOUT_MS) { HivaRoomClient.getBars(resolution, fromSec, toSec) }
        }.getOrElse { err ->
            fetchError = err.message ?: err::class.java.simpleName
            null
        } ?: run {
            if (fetchError.isBlank()) fetchError = "timeout_or_empty_response"
            emptyList()
        }
        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        appendBarsFetchTraceRow(
            profileId = profileId,
            nowSec = nowSec,
            candleTimeSec = candleTimeSec,
            resolution = resolution,
            fromSec = fromSec,
            toSec = toSec,
            candles = bars,
            elapsedMs = elapsedMs,
            err = fetchError
        )
        return BarsFetchResult(
            candles = bars,
            elapsedMs = elapsedMs,
            error = fetchError
        )
    }

    private fun compactOhlc(c: LocalCandle): String {
        return "${c.open.roundToInt()},${c.high.roundToInt()},${c.low.roundToInt()},${c.close.roundToInt()}"
    }

    private fun appendBarsFetchTraceRow(
        profileId: String,
        nowSec: Long,
        candleTimeSec: Long,
        resolution: String,
        fromSec: Long,
        toSec: Long,
        candles: List<LocalCandle>,
        elapsedMs: Long,
        err: String
    ) {
        val storageKey = "bars_fetch_trace_rows_$profileId"
        val previous = prefs.getString(storageKey, "").orEmpty()
        val rows = if (previous.isBlank()) mutableListOf() else previous.split('\n').toMutableList()
        val firstTs = candles.firstOrNull()?.time ?: 0L
        val lastTs = candles.lastOrNull()?.time ?: 0L
        rows += buildString {
            append(nowSec).append("|")
            append(candleTimeSec).append("|")
            append("res=").append(resolution).append("|")
            append("from=").append(fromSec).append("|")
            append("to=").append(toSec).append("|")
            append("n=").append(candles.size).append("|")
            append("first=").append(firstTs).append("|")
            append("last=").append(lastTs).append("|")
            append("ms=").append(elapsedMs).append("|")
            append("err=").append(sanitizeAuditToken(err).take(160))
        }
        if (rows.size > MAX_PERSISTED_BARS_FETCH_TRACE_ROWS) {
            val dropCount = rows.size - MAX_PERSISTED_BARS_FETCH_TRACE_ROWS
            repeat(dropCount) { rows.removeAt(0) }
        }
        prefs.edit().putString(storageKey, rows.joinToString(separator = "\n")).apply()
    }

    private fun appendCandleRevisionRow(
        profileId: String,
        nowSec: Long,
        candleTimeSec: Long,
        tfLabel: String,
        stats: MergeRevisionStats
    ) {
        val storageKey = "candle_revision_rows_$profileId"
        val previous = prefs.getString(storageKey, "").orEmpty()
        val rows = if (previous.isBlank()) mutableListOf() else previous.split('\n').toMutableList()
        rows += buildString {
            append(nowSec).append("|")
            append(candleTimeSec).append("|")
            append("tf=").append(tfLabel).append("|")
            append("before=").append(stats.beforeSize).append("|")
            append("incoming=").append(stats.revisedCount + stats.addedCount).append("|")
            append("added=").append(stats.addedCount).append("|")
            append("revised=").append(stats.revisedCount).append("|")
            append("after=").append(stats.afterSize).append("|")
            append("samples=").append(stats.revisedSamples.joinToString(separator = ";") { sanitizeAuditToken(it) })
        }
        if (rows.size > MAX_PERSISTED_CANDLE_REVISION_ROWS) {
            val dropCount = rows.size - MAX_PERSISTED_CANDLE_REVISION_ROWS
            repeat(dropCount) { rows.removeAt(0) }
        }
        prefs.edit().putString(storageKey, rows.joinToString(separator = "\n")).apply()
    }

    private fun appendPositionUpdatePromoteRow(
        profileId: String,
        nowSec: Long,
        candleTimeSec: Long,
        engineEntryTimeSec: Long,
        trigger: String,
        promoted: Boolean,
        orderSent: String,
        detail: String
    ) {
        val storageKey = "position_update_promote_rows_$profileId"
        val previous = prefs.getString(storageKey, "").orEmpty()
        val rows = if (previous.isBlank()) mutableListOf() else previous.split('\n').toMutableList()
        val deltaSec = if (engineEntryTimeSec > 0L) candleTimeSec - engineEntryTimeSec else -1L
        rows += buildString {
            append(nowSec).append("|")
            append(candleTimeSec).append("|")
            append("entry=").append(engineEntryTimeSec).append("|")
            append("deltaSec=").append(deltaSec).append("|")
            append("trigger=").append(sanitizeAuditToken(trigger)).append("|")
            append("promoted=").append(promoted).append("|")
            append("order=").append(sanitizeAuditToken(orderSent)).append("|")
            append("detail=").append(sanitizeAuditToken(detail).take(220))
        }
        if (rows.size > MAX_PERSISTED_POSITION_UPDATE_PROMOTE_ROWS) {
            val dropCount = rows.size - MAX_PERSISTED_POSITION_UPDATE_PROMOTE_ROWS
            repeat(dropCount) { rows.removeAt(0) }
        }
        prefs.edit().putString(storageKey, rows.joinToString(separator = "\n")).apply()
    }

    private fun engineDirectionMatchesBrokerAction(engineDir: String, brokerAction: String): Boolean {
        val b = brokerAction.lowercase()
        val wantLong = engineDir == "long"
        val wantShort = engineDir == "short"
        val brokerLong = b.contains("buy") || b.contains("long")
        val brokerShort = b.contains("sell") || b.contains("short")
        return (wantLong && brokerLong) || (wantShort && brokerShort)
    }

    private data class EngineCloseOutcome(val decision: String, val gateSuffix: String)

    private fun appendGateTraceLog(
        profileId: String,
        nowSec: Long,
        candleTimeSec: Long,
        decision: String,
        gateTrace: String
    ) {
        val safeDecision = decision.replace('\n', ' ').trim()
        val safeTrace = gateTrace.replace('\n', ' ').trim()
        appendGateTraceRawRow(profileId, "$nowSec|$candleTimeSec|$safeDecision|$safeTrace")
    }

    /** Rolling debug log in prefs [PREFS_NAME] key `audit_debug_rows_$profileId` — pull via adb for post-mortem. */
    private fun appendAuditDebugRow(profileId: String, row: String) {
        val storageKey = "audit_debug_rows_$profileId"
        val previous = prefs.getString(storageKey, "").orEmpty()
        val rows = if (previous.isBlank()) mutableListOf() else previous.split('\n').toMutableList()
        val safe = row.replace('\n', ' ').trim()
        if (safe.isBlank()) return
        rows += safe
        if (rows.size > MAX_PERSISTED_AUDIT_DEBUG_ROWS) {
            val dropCount = rows.size - MAX_PERSISTED_AUDIT_DEBUG_ROWS
            repeat(dropCount) { rows.removeAt(0) }
        }
        prefs.edit().putString(storageKey, rows.joinToString(separator = "\n")).apply()
    }

    /** Single-line overwrite for quick `adb shell cat ... prefs` — same content as latest AUDIT_CYCLE when present. */
    private fun saveLastAuditSnapshot(profileId: String, line: String) {
        prefs.edit().putString("last_audit_snapshot_$profileId", line.replace('\n', ' ').trim().take(4000)).apply()
    }

    private fun sanitizeAuditToken(s: String): String = s.replace('|', '/').replace('\n', ' ').trim()

    @Suppress("LongParameterList")
    private fun appendAuditCyclePrefRow(
        profileId: String,
        nowSec: Long,
        candleSec: Long,
        prevBaseline: Long,
        recoveryMode: Boolean,
        missed: Int,
        recoveryReplayMode: String,
        recoveryReplayRuns: Int,
        signalType: String,
        signalDir: String,
        signalReason: String,
        driftPromoted: Boolean,
        setupScore: Int,
        simEntry: String,
        simStop: String,
        simTake: String,
        engineCandleTime: Long,
        closeExitWhy: String,
        closeExitPx: Int,
        openWs: Int,
        pendWs: Int,
        openRest: Int,
        pendRest: Int,
        freeUnits: Int,
        wsConnected: Boolean,
        wsAccountReady: Boolean,
        bid: Double,
        ask: Double,
        lastPx: Double,
        portfolioId: String,
        cache1mCount: Int,
        cacheReadyFlag: Boolean,
        decision: String,
        err: String,
        dayBt: DayBacktestSummary?,
        sessTrades: Int,
        sessNet: Double
    ) {
        val dShort = sanitizeAuditToken(decision).take(260)
        val eShort = sanitizeAuditToken(err).take(120)
        val engLagMin = when {
            engineCandleTime <= 0L -> -1
            else -> ((candleSec - engineCandleTime) / 60L).toInt()
        }
        val spreadPts = if (bid > 0.0 && ask > 0.0) (ask - bid) else Double.NaN
        val line = buildString {
            append("AUDIT_CYCLE|")
            append(nowSec).append("|")
            append(candleSec).append("|")
            append("prev=").append(prevBaseline).append("|")
            append("recv=").append(recoveryMode).append("|")
            append("miss=").append(missed).append("|")
            append("recMode=").append(sanitizeAuditToken(recoveryReplayMode)).append("|")
            append("recRuns=").append(recoveryReplayRuns).append("|")
            append("sig=").append(sanitizeAuditToken(signalType)).append("|")
            append("dir=").append(sanitizeAuditToken(signalDir)).append("|")
            append("why=").append(sanitizeAuditToken(signalReason).take(80)).append("|")
            append("test=").append(testModeEnabled).append("|")
            append("drift=").append(driftPromoted).append("|")
            append("score=").append(setupScore).append("|")
            append("simE=").append(sanitizeAuditToken(simEntry)).append("|")
            append("simSL=").append(sanitizeAuditToken(simStop)).append("|")
            append("simTP=").append(sanitizeAuditToken(simTake)).append("|")
            append("engCt=").append(engineCandleTime).append("|")
            append("engLagMin=").append(engLagMin).append("|")
            append("exR=").append(sanitizeAuditToken(closeExitWhy).take(48)).append("|")
            append("exPx=").append(closeExitPx).append("|")
            append("spr=").append(spreadPts).append("|")
            append("op=").append(openWs).append("|")
            append("pd=").append(pendWs).append("|")
            append("rOp=").append(openRest).append("|")
            append("rPd=").append(pendRest).append("|")
            append("free=").append(freeUnits).append("|")
            append("ws=").append(wsConnected).append("|")
            append("wsAcc=").append(wsAccountReady).append("|")
            append("bid=").append(bid).append("|")
            append("ask=").append(ask).append("|")
            append("px=").append(lastPx).append("|")
            append("pf=").append(sanitizeAuditToken(portfolioId)).append("|")
            append("c1m=").append(cache1mCount).append("|")
            append("cacheOk=").append(cacheReadyFlag).append("|")
            append("btN=").append(dayBt?.tradeCount ?: -1).append("|")
            append("btPnl=").append(dayBt?.netPnl ?: Double.NaN).append("|")
            append("sessN=").append(sessTrades).append("|")
            append("sessPnl=").append(sessNet).append("|")
            append("dec=").append(dShort).append("|")
            append("err=").append(eShort)
        }
        appendAuditDebugRow(profileId, line)
        saveLastAuditSnapshot(profileId, line)
        Log.i(TAG, "audit_debug_cycle profile=$profileId $line")
    }

    private fun appendSocketRawLog(msg: String) {
        val nowSec = System.currentTimeMillis() / 1000L
        val prefKey = "socket_raw_logs_$activeProfileId"
        val previous = prefs3.getString(prefKey, "").orEmpty()
        val rows = if (previous.isBlank()) mutableListOf() else previous.split('\n').toMutableList()
        val shortMsg = msg.take(1500)
        rows.add("$nowSec|$shortMsg")
        while (rows.size > 200) rows.removeAt(0)
        prefs3.edit().putString(prefKey, rows.joinToString("\n")).apply()
    }

    private fun appendSignalInputLog(
        profileId: String,
        nowSec: Long,
        candleTimeSec: Long,
        recoveryMode: Boolean,
        candles1m: List<LocalCandle>,
        mtf: Map<Int, List<LocalCandle>>
    ) {
        if (candles1m.isEmpty()) return
        val storageKey = "signal_input_rows_$profileId"
        val previous = prefs.getString(storageKey, "").orEmpty()
        val rows = if (previous.isBlank()) mutableListOf() else previous.split('\n').toMutableList()
        val first1m = candles1m.firstOrNull()?.time ?: 0L
        val last1m = candles1m.lastOrNull()?.time ?: 0L
        val payloadHash = computeSignalPayloadHash(candles1m, mtf)
        val tail1m = candles1m.takeLast(3).joinToString(separator = ";") {
            "${it.time}:${it.open.roundToInt()},${it.high.roundToInt()},${it.low.roundToInt()},${it.close.roundToInt()}"
        }
        val mtfSummary = mtf.keys.sorted().joinToString(separator = ",") { tf ->
            val rowsTf = mtf[tf].orEmpty()
            val firstTf = rowsTf.firstOrNull()?.time ?: 0L
            val lastTf = rowsTf.lastOrNull()?.time ?: 0L
            "${tf}m:${rowsTf.size}:$firstTf-$lastTf"
        }
        val row = buildString {
            append(nowSec).append("|")
            append(candleTimeSec).append("|")
            append("recovery=").append(recoveryMode).append("|")
            append("hash=").append(payloadHash).append("|")
            append("1m=").append(candles1m.size).append(":").append(first1m).append("-").append(last1m).append("|")
            append("tail=").append(tail1m).append("|")
            append("mtf=").append(mtfSummary)
        }
        rows += row
        if (rows.size > MAX_PERSISTED_SIGNAL_INPUT_ROWS) {
            val dropCount = rows.size - MAX_PERSISTED_SIGNAL_INPUT_ROWS
            repeat(dropCount) { rows.removeAt(0) }
        }
        prefs.edit().putString(storageKey, rows.joinToString(separator = "\n")).apply()
        Log.i(
            TAG,
            "signal input saved profile=$profileId candle=$candleTimeSec hash=$payloadHash 1m=${candles1m.size} mtf=$mtfSummary"
        )
        appendSignalPayloadLogPrefs2(
            profileId = profileId,
            nowSec = nowSec,
            candleTimeSec = candleTimeSec,
            recoveryMode = recoveryMode,
            payloadHash = payloadHash,
            candles1m = candles1m,
            mtf = mtf
        )
    }

    private fun appendSignalPayloadLogPrefs2(
        profileId: String,
        nowSec: Long,
        candleTimeSec: Long,
        recoveryMode: Boolean,
        payloadHash: String,
        candles1m: List<LocalCandle>,
        mtf: Map<Int, List<LocalCandle>>
    ) {
        val storageKey = "signal_payload_rows_$profileId"
        val previous = prefs2.getString(storageKey, "").orEmpty()
        val rows = if (previous.isBlank()) mutableListOf() else previous.split('\n').toMutableList()
        val payload = JsonObject().apply {
            addProperty("sent_at", nowSec)
            addProperty("candle_time", candleTimeSec)
            addProperty("profile_id", profileId)
            addProperty("recovery_mode", recoveryMode)
            addProperty("payload_hash", payloadHash)
            add("candles_1m", JsonArray().apply {
                for (c in candles1m) {
                    add(JsonObject().apply {
                        addProperty("time", c.time)
                        addProperty("open", c.open)
                        addProperty("high", c.high)
                        addProperty("low", c.low)
                        addProperty("close", c.close)
                    })
                }
            })
            add("mtf", JsonObject().apply {
                for (tf in mtf.keys.sorted()) {
                    val key = "${tf}m"
                    val tfRows = mtf[tf].orEmpty()
                    add(key, JsonArray().apply {
                        for (c in tfRows) {
                            add(JsonObject().apply {
                                addProperty("time", c.time)
                                addProperty("open", c.open)
                                addProperty("high", c.high)
                                addProperty("low", c.low)
                                addProperty("close", c.close)
                            })
                        }
                    })
                }
            })
        }
        rows += payload.toString()
        prefs2.edit().putString(storageKey, rows.joinToString(separator = "\n")).apply()
        prefs2.edit().putString("last_signal_payload_$profileId", payload.toString()).apply()
        Log.i(
            TAG,
            "signal payload saved prefs2 profile=$profileId candle=$candleTimeSec hash=$payloadHash rows=${rows.size}"
        )
    }

    private fun computeSignalPayloadHash(
        candles1m: List<LocalCandle>,
        mtf: Map<Int, List<LocalCandle>>
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun feed(text: String) {
            md.update(text.toByteArray(Charsets.UTF_8))
        }
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

    private fun buildEntryIdempotencyKey(
        profileId: String,
        portfolioId: String,
        candleTime: Long,
        fingerprint: String
    ): String {
        return "$profileId|$portfolioId|$candleTime|$fingerprint"
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

    /**
     * Prefer WebSocket open list (same ids as web); if empty, fall back to REST portfolio — matches [executeEngineCloseSignal].
     */
    private fun resolveOpenPositionRefs(
        wsSnapshot: HivaPriceSocketClient.Snapshot,
        openPositionsList: List<JsonObject>,
        wsAccountReady: Boolean
    ): List<HivaPriceSocketClient.TransactionRef> {
        if (wsAccountReady && wsSnapshot.openTransactions.isNotEmpty()) {
            return wsSnapshot.openTransactions
        }
        val out = mutableListOf<HivaPriceSocketClient.TransactionRef>()
        for (row in openPositionsList) {
            val id = row.stringValue("id", "")
            if (id.isBlank()) continue
            
            val action = row.stringValue("action", "").lowercase()
            
            out += HivaPriceSocketClient.TransactionRef(id = id, action = action)
        }
        return out
    }

    private suspend fun maybeUpdateOpenPositionRisk(
        signal: JsonObject,
        wsSnapshot: HivaPriceSocketClient.Snapshot,
        openPositionsList: List<JsonObject>,
        wsAccountReady: Boolean,
        auditProfileId: String,
        auditNowSec: Long,
        auditCandleSec: Long
    ): String {
        val signalType = signal.stringValue("type", "no_signal")
        if (signalType != "signal" && signalType != "position_update") return ""
        val stop = signal.stringValue("stop_loss", "").toDoubleOrNull()?.roundToInt() ?: return ""
        val take = signal.stringValue("take_profit", "").toDoubleOrNull()?.roundToInt() ?: return ""
        val signalDirection = signal.stringValue("direction", "").lowercase()

        val openRows = resolveOpenPositionRefs(wsSnapshot, openPositionsList, wsAccountReady)
        if (openRows.isEmpty()) {
            Log.d(TAG, "risk update skipped: no open position ids (ws empty, rest empty)")
            return ""
        }
        val source = if (wsAccountReady && wsSnapshot.openTransactions.isNotEmpty()) "ws" else "rest"
        Log.i(
            TAG,
            "risk update candidates=${openRows.size} source=$source signalType=$signalType signalDir=$signalDirection stop=$stop take=$take"
        )

        var changed = 0
        for (row in openRows) {
            val positionId = row.id
            if (positionId.isBlank()) continue
            val positionDir = row.action.lowercase()
            if (signalDirection.isNotBlank() && positionDir.isNotBlank()) {
                val normalizedPosDir = if (positionDir.contains("buy") || positionDir.contains("long")) "long" else "short"
                if (normalizedPosDir != signalDirection) continue
            }

            val lastSl = lastEditedStopLoss[positionId]
            val lastTp = lastEditedTakeProfit[positionId]
            val slChanged = lastSl == null || lastSl != stop
            val tpChanged = lastTp == null || lastTp != take

            if (slChanged || tpChanged) {
                val editBody = JsonObject().apply {
                    addProperty("stop_loss", stop)
                    addProperty("take_profit", take)
                }
                val idLong = positionId.toLongOrNull() ?: 0L
                if (idLong > 0) {
                    runCatching { HivaGoldClient.api.editMazanehTransaction(idLong, editBody) }.onSuccess {
                        lastEditedStopLoss[positionId] = stop
                        lastEditedTakeProfit[positionId] = take
                        changed += 1
                        Log.i(TAG, "risk update position=$positionId stop=$stop take=$take")
                    }.onFailure { err ->
                        Log.w(TAG, "risk update failed position=$positionId error=${err.message}")
                    }
                }
            }
        }
        if (changed > 0) {
            appendAuditDebugRow(
                auditProfileId,
                "RISK_SYNC|${auditNowSec}|${auditCandleSec}|changed=$changed|src=$source|sigType=$signalType|sigSL=$stop|sigTP=$take|dir=$signalDirection"
            )
            return "updated tp/sl on open position(s)"
        }
        return ""
    }

    private fun scheduleTestModeRiskEdit(
        action: String,
        take: Int,
        stop: Int,
        profileId: String,
        nowSec: Long
    ) {
        if (testRiskEditDispatched) {
            Log.d(TAG, "TEST_MODE risk-edit already dispatched, skipping duplicate schedule")
            return
        }
        testRiskEditDispatched = true
        testRiskEditJob?.cancel()
        testRiskEditJob = serviceScope.launch {
            Log.w(
                TAG,
                "TEST_MODE risk-edit scheduled profile=$profileId action=$action take=$take stop=$stop delayMs=$TEST_RISK_EDIT_DELAY_MS"
            )
            delay(TEST_RISK_EDIT_DELAY_MS)
            runCatching {
                triggerTestModeRiskEdit(action = action, take = take, stop = stop, profileId = profileId, nowSec = nowSec)
            }.onFailure { err ->
                Log.e(TAG, "TEST_MODE risk-edit failed profile=$profileId error=${err.message}", err)
            }
        }
    }

    private suspend fun fetchTransactionIdAfterSubmit(
        phase: String,
        profileId: String,
        action: String,
        baselineLastTx: String
    ): String {
        val desiredDir = if (action.lowercase().contains("buy")) "buy" else "sell"
        Log.w(
            TAG,
            "TX_WATCH start phase=$phase profile=$profileId action=$action"
        )
        for (attempt in 1..WS_TX_WATCH_ATTEMPTS) {
            val txJson = runCatching { HivaGoldClient.api.getMazanehTransactions(1) }.getOrNull()
            val openRows = txJson?.getAsJsonArray("open") ?: JsonArray()
            val matchedOpenId = openRows.map { it.asJsonObject }
                .firstOrNull { it.stringValue("action").contains(desiredDir) }
                ?.stringValue("id", "") ?: ""

            Log.w(
                TAG,
                "TX_WATCH attempt=$attempt/$WS_TX_WATCH_ATTEMPTS phase=$phase openCount=${openRows.size()} matchedOpenId=${matchedOpenId.ifBlank { "-" }}"
            )
            if (matchedOpenId.isNotBlank()) {
                Log.w(
                    TAG,
                    "TX_WATCH resolved phase=$phase profile=$profileId action=$action txId=$matchedOpenId"
                )
                return matchedOpenId
            }
            delay(WS_TX_WATCH_DELAY_MS)
        }
        Log.e(TAG, "TX_WATCH timeout phase=$phase profile=$profileId action=$action")
        return ""
    }

    private suspend fun triggerTestModeRiskEdit(
        action: String,
        take: Int,
        stop: Int,
        profileId: String,
        nowSec: Long
    ) {
        val desiredDir = if (action.lowercase() == "buy") "buy" else "sell"
        val newTake = take + TEST_RISK_EDIT_OFFSET_POINTS
        val newStop = stop + TEST_RISK_EDIT_OFFSET_POINTS
        var selectedId = ""
        for (attempt in 0 until 8) {
            val txJson = runCatching { HivaGoldClient.api.getMazanehTransactions(1) }.getOrNull()
            val openRows = txJson?.getAsJsonArray("open") ?: JsonArray()
            selectedId = openRows.map { it.asJsonObject }
                .firstOrNull { it.stringValue("action").contains(desiredDir) }
                ?.stringValue("id", "") ?: ""
            Log.w(
                TAG,
                "TEST_MODE risk-edit lookup attempt=${attempt + 1}/8 profile=$profileId dir=$desiredDir openCount=${openRows.size()} selectedId=${selectedId.ifBlank { "-" }}"
            )
            if (selectedId.isNotBlank()) break
            delay(2_000L)
        }
        if (selectedId.isBlank()) {
            Log.e(TAG, "TEST_MODE risk-edit aborted profile=$profileId reason=no_open_transaction_id")
            return
        }

        val idLong = selectedId.toLongOrNull() ?: 0L
        if (idLong > 0) {
            val body = JsonObject().apply {
                addProperty("take_profit", newTake)
                addProperty("stop_loss", newStop)
            }
            runCatching { HivaGoldClient.api.editMazanehTransaction(idLong, body) }
                .onSuccess { res -> Log.w(TAG, "TEST_MODE risk-edit ok txId=$selectedId response=$res") }
                .onFailure { err -> Log.e(TAG, "TEST_MODE risk-edit failed txId=$selectedId error=${err.message}", err) }
        }
    }

    private suspend fun closeAllActiveTradesAndOrdersAndPortfolio(): String {
        var errors = ""
        val txJson = runCatching { HivaGoldClient.api.getMazanehTransactions(1) }.getOrElse { err ->
            return "close-all failed: transactions unavailable ${err.message}"
        }
        val openRows = txJson.getAsJsonArray("open") ?: JsonArray()
        val orderRows = runCatching { HivaGoldClient.api.getMazanehActiveOrders() }.getOrElse { emptyList() }
        
        Log.i(TAG, "close-all start open=${openRows.size()} pending=${orderRows.size}")

        for (rowEl in openRows) {
            val row = rowEl.asJsonObject
            val id = row.stringValue("id", "")
            if (id.isBlank()) continue
            val idLong = id.toLongOrNull() ?: 0L
            runCatching { HivaGoldClient.api.closeMazanehTransaction(idLong, JsonObject()) }
                .onSuccess { Log.i(TAG, "close-all closed position id=$id") }
                .onFailure { err ->
                    errors = mergeError(errors, "close position $id failed: ${err.message}")
                    Log.e(TAG, "close-all failed to close position id=$id", err)
                }
        }
        for (row in orderRows) {
            val id = row.stringValue("id", "")
            if (id.isBlank()) continue
            val idLong = id.toLongOrNull() ?: 0L
            runCatching { HivaGoldClient.api.cancelMazanehOrder(idLong) }
                .onSuccess { Log.i(TAG, "close-all canceled order id=$id") }
                .onFailure { err ->
                    errors = mergeError(errors, "cancel order $id failed: ${err.message}")
                    Log.e(TAG, "close-all failed to cancel order id=$id", err)
                }
        }
        
        val activePort = runCatching { HivaGoldClient.api.getMazanehActivePortfolio() }.getOrNull()
        if (activePort != null) {
            runCatching { HivaGoldClient.api.closeMazanehPortfolio(activePort.id) }
                .onSuccess { Log.i(TAG, "close-all closed portfolio id=${activePort.id}") }
                .onFailure { err ->
                    errors = mergeError(errors, "close portfolio ${activePort.id} failed: ${err.message}")
                    Log.e(TAG, "close-all failed to close portfolio id=${activePort.id}", err)
                }
        }
        return errors
    }

    private suspend fun ensurePortfolioForSession(units: Int): PortfolioStatus {
        val nowSec = System.currentTimeMillis() / 1000L
        val activePortResult = runCatching { HivaGoldClient.api.getMazanehActivePortfolio() }
        var activePort = activePortResult.getOrNull()
        val getError = activePortResult.exceptionOrNull()
        
        Log.i(TAG, "ensurePortfolio check has=${activePort != null} requestedUnits=$units err=${getError?.message ?: "none"}")
        
        if (activePort == null) {
            // If the error was 429 (Too Many Requests), don't try to create, just fail fast
            if (getError?.message?.contains("429") == true) {
                return PortfolioStatus(error = "portfolio check rate-limited (429)")
            }

            val initialBalance = units.toLong() * PORTFOLIO_UNIT_MONEY
            val body = JsonObject().apply {
                addProperty("portfolio_type", "isolated")
                addProperty("initial_balance", initialBalance)
            }
            Log.i(TAG, "ensurePortfolio: waiting 2s before create to avoid 429...")
            kotlinx.coroutines.delay(2000)
            Log.i(TAG, "ensurePortfolio create Mazaneh portfolio payload=$body")
            val createResult = runCatching { HivaGoldClient.api.createMazanehPortfolio(body) }
            activePort = createResult.getOrNull()
            if (activePort == null) {
                val createErr = createResult.exceptionOrNull()
                Log.e(TAG, "ensurePortfolio create failed: ${createErr?.message}")
                return PortfolioStatus(error = "portfolio create failed: ${createErr?.message}")
            }
        }
        
        val userInfo = runCatching { HivaGoldClient.api.getMazanehUserInfo() }.getOrElse { JsonObject() }
        cachedPortfolio = activePort
        lastPortfolioFetchEpochSec = nowSec
        cachedUserInfo = userInfo
        lastUserInfoFetchEpochSec = nowSec
        
        return PortfolioStatus(
            hasPortfolio = true,
            portfolioId = activePort.id.toString(),
            units = activePort.availableUnits.toInt(),
            userBalance = userInfo.doubleValue("balance", 0.0),
            error = ""
        )
    }

    private fun summarizeSignal(signal: JsonObject): String {
        val type = signal.stringValue("type", "no_signal")
        val dir = signal.stringValue("direction", "-")
        val reason = signal.stringValue("reason", signal.stringValue("exit_reason", ""))
        if (type == "close_signal") {
            val ex = signal.doubleValue("exit_price", 0.0)
            return "type=$type dir=$dir exit=${formatPriceCompact(ex)} reason=$reason"
        }
        val entry = signal.stringValue("entry_price", "-")
        val stop = signal.stringValue("stop_loss", "-")
        val take = signal.stringValue("take_profit", "-")
        val score = signal.intValue("setup_score", 0)
        val qty = signal.intValue("quantity", 0)
        return "type=$type dir=$dir entry=$entry stop=$stop take=$take score=$score qty=$qty reason=$reason"
    }

    private fun formatEpochSec(value: Long): String {
        if (value <= 0L) return "-"
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return formatter.format(Date(value * 1000L))
    }

    private fun formatPriceCompact(value: Double): String {
        if (value <= 0.0) return "-"
        return String.format(Locale.US, "%,.0f", value)
    }

    private fun formatSignedMoneyCompact(value: Double): String {
        if (value == 0.0) return "0"
        val sign = if (value > 0.0) "+" else "-"
        return sign + String.format(Locale.US, "%,.0f", kotlin.math.abs(value))
    }

    private fun parseRetryAfterSeconds(err: HttpException): Long? {
        val raw = err.response()?.headers()?.get("retry-after")?.trim().orEmpty()
        if (raw.isBlank()) return null
        return raw.toLongOrNull()?.coerceAtLeast(MIN_ORDER_RATE_LIMIT_COOLDOWN_SEC)
    }

    private fun applyOrderRateLimitBackoff(nowSec: Long, retryAfterSec: Long?): Long {
        consecutiveOrderRateLimitHits = (consecutiveOrderRateLimitHits + 1).coerceAtMost(16)
        val expBackoffSec = 1L shl (consecutiveOrderRateLimitHits - 1)
        val computed = (retryAfterSec ?: expBackoffSec)
            .coerceIn(MIN_ORDER_RATE_LIMIT_COOLDOWN_SEC, MAX_ORDER_RATE_LIMIT_COOLDOWN_SEC)
        orderRateLimitUntilEpochSec = nowSec + computed
        Log.w(
            TAG,
            "order submit rate-limited hit=$consecutiveOrderRateLimitHits retryAfter=$retryAfterSec cooldownSec=$computed until=$orderRateLimitUntilEpochSec"
        )
        return computed
    }

    private fun applyCloseRateLimitBackoff(nowSec: Long, retryAfterSec: Long?): Long {
        consecutiveCloseRateLimitHits = (consecutiveCloseRateLimitHits + 1).coerceAtMost(16)
        val expBackoffSec = 1L shl (consecutiveCloseRateLimitHits - 1)
        val computed = (retryAfterSec ?: expBackoffSec)
            .coerceIn(MIN_CLOSE_RATE_LIMIT_COOLDOWN_SEC, MAX_CLOSE_RATE_LIMIT_COOLDOWN_SEC)
        closeRateLimitUntilEpochSec = nowSec + computed
        Log.w(
            TAG,
            "close submit rate-limited hit=$consecutiveCloseRateLimitHits retryAfter=$retryAfterSec cooldownSec=$computed until=$closeRateLimitUntilEpochSec"
        )
        return computed
    }

    private fun extractSubmitOrderId(res: JsonObject): String {
        val keysToTry = listOf("id", "order_id", "position_id", "transaction_id", "ticket")
        for (k in keysToTry) {
            val v = res.stringValue(k, "")
            if (v.isNotBlank()) return v
        }
        val nestedKeys = listOf("data", "result", "order")
        for (key in nestedKeys) {
            val el = res.get(key) ?: continue
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            for (k in keysToTry) {
                val v = obj.stringValue(k, "")
                if (v.isNotBlank()) return v
            }
        }
        // Keep UI explicit even when backend doesn't return a concrete id.
        return "submitted_no_id"
    }

    private fun JsonObject.stringValue(key: String, fallback: String = ""): String {
        val el = get(key) ?: return fallback
        if (el.isJsonNull) return fallback
        return runCatching { el.asString }.getOrDefault(fallback)
    }

    private fun JsonObject.doubleValue(key: String, fallback: Double = 0.0): Double {
        val el = get(key) ?: return fallback
        if (el.isJsonNull) return fallback
        return runCatching { el.asDouble }.getOrDefault(fallback)
    }

    private fun JsonObject.intValue(key: String, fallback: Int = 0): Int {
        val el = get(key) ?: return fallback
        if (el.isJsonNull) return fallback
        return runCatching { el.asInt }.getOrDefault(fallback)
    }

    private fun JsonObject.boolValue(key: String, fallback: Boolean = false): Boolean {
        val el = get(key) ?: return fallback
        if (el.isJsonNull) return fallback
        return runCatching { el.asBoolean }.getOrDefault(fallback)
    }

    private fun JsonObject.longValue(key: String, fallback: Long = 0L): Long {
        val el = get(key) ?: return fallback
        if (el.isJsonNull) return fallback
        return runCatching { el.asLong }.getOrDefault(fallback)
    }

    private fun JsonObject.arrayValue(key: String): JsonArray {
        val el: JsonElement = get(key) ?: return JsonArray()
        if (el.isJsonNull || !el.isJsonArray) return JsonArray()
        return el.asJsonArray
    }

    private data class SessionStats(
        val tradeCount: Int,
        val winCount: Int,
        val lossCount: Int,
        val winRate: Double,
        val netPnl: Double
    )

    private data class DayBacktestSummary(
        val tradeCount: Int,
        val winCount: Int,
        val lossCount: Int,
        val winRate: Double,
        val netPnl: Double,
        val signalsText: String
    )

    private data class RecoveryResult(
        val signal: JsonObject,
        val mode: String,
        val replayRuns: Int
    )

    private data class RecoverEntryParity(
        val confirmed: Boolean,
        val code: String,
        val detail: String
    )

    private data class EntryBacktestParity(
        val confirmed: Boolean,
        val code: String,
        val detail: String
    )

    private data class PortfolioStatus(
        val hasPortfolio: Boolean = false,
        val portfolioId: String = "",
        val units: Int = 0,
        val userBalance: Double = 0.0,
        val error: String = ""
    )

    private data class CacheSyncResult(
        val ok: Boolean,
        val error: String,
        val candles1m: Int,
        val mtfCounts: Map<Int, Int>
    )

    private data class BarsFetchResult(
        val candles: List<LocalCandle>,
        val elapsedMs: Long,
        val error: String
    )

    private data class MergeRevisionStats(
        val revisedCount: Int,
        val addedCount: Int,
        val beforeSize: Int,
        val afterSize: Int,
        val revisedSamples: List<String>
    )
}
