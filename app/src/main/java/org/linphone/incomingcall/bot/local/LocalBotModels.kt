package org.linphone.incomingcall.bot.local

data class LocalCandle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
) {
    val range: Double get() = high - low
    val body: Double get() = kotlin.math.abs(close - open)
}

data class LocalIndicatorSet(
    val emaFast: List<Double?>,
    val emaSlow: List<Double?>,
    val emaTrend: List<Double?>,
    val atr: List<Double?>,
    val rsi: List<Double?>,
    val macdLine: List<Double?>,
    val macdSignal: List<Double?>,
    val macdHistogram: List<Double?>
)

data class LocalSignalResult(
    val type: String,
    val direction: String? = null,
    val entryPrice: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val setupScore: Int? = null,
    val setupNotes: List<String>? = null,
    val reason: String? = null
)

data class LocalBacktestSummary(
    val tradeCount: Int,
    val winRate: Double,
    val netProfit: Double,
    val endingBalance: Double,
    val profitFactor: Double?,
    val maxDrawdownPercent: Double
)

object LocalBotConfig {
    const val CONTRACT_VALUE = 4_600_000.0
    const val VALUE_PER_POINT = 23_000.0
    const val FEE_PER_ROUND_TRIP = 28_000.0
    const val STARTING_BALANCE = 13_800_000.0

    const val EMA_FAST_PERIOD = 12
    const val EMA_SLOW_PERIOD = 20
    const val EMA_TREND_PERIOD = 50
    const val ATR_PERIOD = 14
    const val RSI_PERIOD = 14
    const val MACD_FAST = 12
    const val MACD_SLOW = 26
    const val MACD_SIGNAL = 9

    const val MIN_WARMUP = 28
    /** Must stay in sync with python src/config.py when DEMO_LOOSER_GATES is on. */
    const val MIN_ENTRY_SCORE = 2
    const val STOP_BUFFER_POINTS = 8.0
    const val CONFIRM_BUFFER = 4.0
    const val SLOPE_LOOKBACK = 8
    const val TREND_GAP_MIN = 1.0
    const val TREND_GAP_STRONG = 14.0
    const val PULLBACK_MAX_DISTANCE = 28.0
    const val FAST_MA_CLOSE_BUFFER = 2.0
    const val OVEREXTENDED_LOOKBACK = 10
    const val OVEREXTENDED_MAX_POINTS = 320.0
    const val MIN_BODY_POINTS = 8.0
    const val MIN_RANGE_POINTS = 10.0
    const val BODY_STRENGTH_MIN = 0.32
    const val CLOSE_IN_RANGE_LONG_MIN = 0.58
    const val CLOSE_IN_RANGE_SHORT_MAX = 0.42
    const val LONG_GATE_CLOSE_RATIO_MIN = 0.46
    const val SHORT_GATE_CLOSE_RATIO_MAX = 0.54
    const val MAX_LOSS_POINTS_PER_TRADE = 70.0
    const val WICK_MAX_POINTS = 28.0
    const val WICK_MAX_BODY_RATIO = 1.05
    const val ATR_ENTRY_MIN = 5.0
    const val ATR_ENTRY_MAX = 500.0
    const val ATR_HEALTHY_MIN = 22.0
    const val ATR_HEALTHY_MAX = 210.0
    const val RSI_LONG_MIN = 38.0
    const val RSI_LONG_MAX = 80.0
    const val RSI_SHORT_MIN = 20.0
    const val RSI_SHORT_MAX = 62.0
    const val MACD_LONG_MIN = -40.0
    const val MACD_SHORT_MAX = 40.0
    const val MTF_EMA_TOLERANCE = 6.0
    const val MTF_RSI_BULLISH_MIN = 48.0
    const val MTF_RSI_BEARISH_MAX = 52.0
    const val MTF_MACD_HIST_BULLISH_MIN = -10.0
    const val MTF_MACD_HIST_BEARISH_MAX = 10.0
    val TARGET_MULTIPLIERS = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
}
