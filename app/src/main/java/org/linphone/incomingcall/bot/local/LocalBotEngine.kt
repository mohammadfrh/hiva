package org.linphone.incomingcall.bot.local

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object LocalBotEngine {
    private const val TAG = "BT_ENGINE"
    private const val FEE_LOCK_POINTS = 2.0
    private const val PENDING_ENTRY_EXPIRY_CANDLES = 1
    private const val TRAILING_MIN_TARGETS_HIT = 2
    private const val TRAILING_ACTIVATION_POINTS = 110.0
    private const val TRAILING_MOVE_MEDIUM_THRESHOLD = 200.0
    private const val TRAILING_MOVE_LARGE_THRESHOLD = 300.0
    private const val TRAILING_DISTANCE_SMALL = 55.0
    private const val TRAILING_DISTANCE_MEDIUM = 75.0
    private const val TRAILING_DISTANCE_LARGE = 95.0

    fun evaluateSignal(candles1m: List<LocalCandle>, profileId: String): LocalSignalResult {
        Log.d(TAG, "evaluateSignal start profile=$profileId candles=${candles1m.size}")
        if (candles1m.size < 60) return LocalSignalResult(type = "no_signal", reason = "insufficient_history")
        val ind = LocalBotIndicators.build(candles1m)
        val i = candles1m.lastIndex
        val prev = i - LocalBotConfig.MIN_WARMUP
        if (prev < 1) return LocalSignalResult(type = "no_signal", reason = "warmup")
        val signal = evaluateEntry(candles1m, ind, i, profileId) ?: return LocalSignalResult(type = "no_signal")
        Log.d(
            TAG,
            "evaluateSignal done type=${signal.type} direction=${signal.direction} entry=${signal.entryPrice} stop=${signal.stopLoss} take=${signal.takeProfit} score=${signal.setupScore} reason=${signal.reason}"
        )
        return signal
    }

    fun backtest(
        candles1m: List<LocalCandle>,
        profileId: String,
        externalMtfCandles: Map<Int, List<LocalCandle>> = emptyMap()
    ): LocalBacktestSummary {
        Log.i(TAG, "backtest start profile=$profileId candles=${candles1m.size}")
        if (candles1m.size < 100) {
            Log.w(TAG, "backtest aborted profile=$profileId reason=insufficient_history")
            return LocalBacktestSummary(0, 0.0, 0.0, LocalBotConfig.STARTING_BALANCE, null, 0.0)
        }
        val ind = LocalBotIndicators.build(candles1m)
        val mtfSpecs = listOf(5, 15, 30, 60)
        val mtfCandles = mtfSpecs.associateWith { tf ->
            val ext = externalMtfCandles[tf].orEmpty()
            if (ext.isNotEmpty()) ext else buildMtfCandles(candles1m, tf)
        }
        val mtfIndicators = mtfCandles.mapValues { (_, v) -> LocalBotIndicators.build(v) }
        var balance = LocalBotConfig.STARTING_BALANCE
        var peak = balance
        var maxDd = 0.0
        var wins = 0
        var losses = 0
        var totalWin = 0.0
        var totalLoss = 0.0
        var tradeCount = 0
        var idx = 0
        var noSignalCheckpoints = 0
        var entryTraceCount = 0
        var exitTraceCount = 0
        var pending: PendingEntry? = null
        var position: OpenPosition? = null
        while (idx < candles1m.size) {
            val c = candles1m[idx]
            if (position != null) {
                updatePositionTracking(position, c)
                advanceTargets(position, c)
                updateTrailingStop(position, c)

                var close = checkSystemExit(position, c, idx)
                if (close == null && shouldStrategyExit(position, c, idx, profileId)) {
                    close = ExitResult(c.close, "strategy_exit", idx)
                }
                if (close != null) {
                    val pnlPoints = if (position.direction == "long") {
                        close.exitPrice - position.entryPrice
                    } else {
                        position.entryPrice - close.exitPrice
                    }
                    val pnl = (pnlPoints * LocalBotConfig.VALUE_PER_POINT - LocalBotConfig.FEE_PER_ROUND_TRIP) * position.quantity
                    balance += pnl
                    balance = max(balance, 0.0)
                    peak = max(peak, balance)
                    maxDd = max(maxDd, peak - balance)
                    tradeCount++
                    if (exitTraceCount < 60) {
                        Log.d(
                            TAG,
                            "exit trade=$tradeCount exitIdx=${close.exitIndex} exitTime=${candles1m[close.exitIndex].time} reason=${close.reason} qty=${position.quantity} exitPrice=${close.exitPrice} pnlPoints=$pnlPoints pnl=$pnl balance=$balance"
                        )
                        exitTraceCount++
                    }
                    if (pnl > 0) {
                        wins++
                        totalWin += pnl
                    } else {
                        losses++
                        totalLoss += abs(pnl)
                    }
                    position = null
                }
                idx++
                continue
            }

            if (pending != null) {
                val p = pending!!
                if (idx <= p.signalIndex) {
                    idx++
                    continue
                } else if (idx > p.signalIndex + PENDING_ENTRY_EXPIRY_CANDLES) {
                    pending = null
                } else {
                    val confirmed = if (p.direction == "long") c.high >= p.confirmPrice else c.low <= p.confirmPrice
                    val canceled = if (p.direction == "long") c.low <= p.cancelPrice else c.high >= p.cancelPrice
                    if (confirmed && canceled || canceled) {
                        pending = null
                    } else if (confirmed) {
                        val range = (c.high - c.low).coerceAtLeast(1.0)
                        val closeStrength = (c.close - c.low) / range
                        val body = abs(c.close - c.open)
                        val upperWick = c.high - max(c.open, c.close)
                        val lowerWick = min(c.open, c.close) - c.low
                        val weak = if (p.direction == "long") {
                            c.close <= c.open ||
                                c.close < p.confirmPrice ||
                                closeStrength < 0.55 ||
                                (body / range) < 0.25
                        } else {
                            c.close >= c.open ||
                                c.close > p.confirmPrice ||
                                closeStrength > 0.45 ||
                                (body / range) < 0.25
                        }
                        val adverseLong = p.direction == "long" && (
                            closeStrength < 0.68 ||
                                upperWick > max(18.0, body) ||
                                lowerWick < 4.0 ||
                                (c.close - p.confirmPrice) < 6.0
                            )
                        if (weak || adverseLong) {
                            pending = null
                        } else {
                            val score = p.setupScore ?: 0
                            val quantity = quantityForProfile(profileId, score)
                            val requiredBalance = LocalBotConfig.CONTRACT_VALUE * quantity
                            if (balance < requiredBalance) {
                                pending = null
                                idx++
                                continue
                            }
                            val stop = if (p.direction == "long") {
                                max(p.signalCandle.low - LocalBotConfig.STOP_BUFFER_POINTS, p.confirmPrice - LocalBotConfig.MAX_LOSS_POINTS_PER_TRADE)
                            } else {
                                min(p.signalCandle.high + LocalBotConfig.STOP_BUFFER_POINTS, p.confirmPrice + LocalBotConfig.MAX_LOSS_POINTS_PER_TRADE)
                            }
                            val targetPrices = buildTargetLadder(p.confirmPrice, stop, p.direction)
                            val takeProfit = targetPrices.last()
                            position = OpenPosition(
                                direction = p.direction,
                                entryPrice = p.confirmPrice,
                                stopLoss = stop,
                                takeProfit = takeProfit,
                                targetPrices = targetPrices,
                                entryIndex = idx,
                                quantity = quantity
                            )
                            if (entryTraceCount < 60) {
                                Log.d(
                                    TAG,
                                    "entry idx=$idx time=${c.time} dir=${position.direction} qty=${position.quantity} entry=${position.entryPrice} stop=${position.stopLoss} tp=${position.takeProfit} score=${p.setupScore} notes=${p.setupNotes}"
                                )
                                entryTraceCount++
                            }
                            pending = null
                        }
                    }
                }
                idx++
                continue
            }

            val sig = evaluateEntry(candles1m, ind, idx, profileId, mtfCandles, mtfIndicators)
            if (sig == null) {
                if (noSignalCheckpoints < 20 && idx % 120 == 0) {
                    Log.v(TAG, "no-signal idx=$idx time=${c.time} close=${c.close} profile=$profileId")
                    noSignalCheckpoints++
                }
                idx++
                continue
            }

            val direction = sig.direction ?: "long"
            val confirmPrice = sig.entryPrice ?: c.close
            val cancelPrice = if (direction == "long") {
                c.low - LocalBotConfig.CONFIRM_BUFFER
            } else {
                c.high + LocalBotConfig.CONFIRM_BUFFER
            }
            pending = PendingEntry(
                direction = direction,
                confirmPrice = confirmPrice,
                cancelPrice = cancelPrice,
                signalIndex = idx,
                signalCandle = c,
                setupScore = sig.setupScore,
                setupNotes = sig.setupNotes
            )
            idx++
        }

        if (position != null) {
            val lastIndex = candles1m.lastIndex
            val last = candles1m[lastIndex]
            val pnlPoints = if (position.direction == "long") last.close - position.entryPrice else position.entryPrice - last.close
            val pnl = (pnlPoints * LocalBotConfig.VALUE_PER_POINT - LocalBotConfig.FEE_PER_ROUND_TRIP) * position.quantity
            balance += pnl
            balance = max(balance, 0.0)
            peak = max(peak, balance)
            maxDd = max(maxDd, peak - balance)
            tradeCount++
            if (pnl > 0) {
                wins++
                totalWin += pnl
            } else {
                losses++
                totalLoss += abs(pnl)
            }
        }
        val winRate = if (tradeCount == 0) 0.0 else wins.toDouble() / tradeCount.toDouble()
        val netProfit = balance - LocalBotConfig.STARTING_BALANCE
        val pf = if (totalLoss > 0.0) totalWin / totalLoss else null
        val ddPct = if (peak > 0) maxDd / peak else 0.0
        Log.i(
            TAG,
            "backtest done profile=$profileId trades=$tradeCount wins=$wins losses=$losses winRate=$winRate net=$netProfit end=$balance pf=$pf maxDdPct=$ddPct"
        )
        return LocalBacktestSummary(tradeCount, winRate, netProfit, balance, pf, ddPct)
    }

    private fun evaluateEntry(
        candles: List<LocalCandle>,
        ind: LocalIndicatorSet,
        i: Int,
        profileId: String,
        mtfCandles: Map<Int, List<LocalCandle>> = emptyMap(),
        mtfIndicators: Map<Int, LocalIndicatorSet> = emptyMap()
    ): LocalSignalResult? {
        if (i < LocalBotConfig.MIN_WARMUP || i < 1) return null
        val c = candles[i]
        val fast = sma(candles, i, LocalBotConfig.EMA_FAST_PERIOD) ?: return null
        val slow = sma(candles, i, LocalBotConfig.EMA_SLOW_PERIOD) ?: return null
        val priorSlow = sma(candles, i - LocalBotConfig.SLOPE_LOOKBACK, LocalBotConfig.EMA_SLOW_PERIOD) ?: return null
        val atr = ind.atr[i] ?: return null
        val rsi = ind.rsi[i] ?: return null
        val hist = ind.macdHistogram[i] ?: return null
        val closeRatio = if (c.range > 0) (c.close - c.low) / c.range else 0.5
        val bodyStrength = if (c.range > 0) c.body / c.range else 0.0
        val topWick = c.high - max(c.open, c.close)
        val bottomWick = min(c.open, c.close) - c.low
        val goodLongWick = topWick <= max(LocalBotConfig.WICK_MAX_POINTS, c.body * LocalBotConfig.WICK_MAX_BODY_RATIO)
        val goodShortWick = bottomWick <= max(LocalBotConfig.WICK_MAX_POINTS, c.body * LocalBotConfig.WICK_MAX_BODY_RATIO)
        val lookbackStart = max(0, i - LocalBotConfig.OVEREXTENDED_LOOKBACK)
        val rollingHigh = candles.subList(lookbackStart, i + 1).maxOf { it.high }
        val rollingLow = candles.subList(lookbackStart, i + 1).minOf { it.low }
        val notOverextendedLong = (rollingHigh - c.close) <= LocalBotConfig.OVEREXTENDED_MAX_POINTS
        val notOverextendedShort = (c.close - rollingLow) <= LocalBotConfig.OVEREXTENDED_MAX_POINTS
        val mtfLong = passesMtf("long", c.time, mtfCandles, mtfIndicators)
        val mtfShort = passesMtf("short", c.time, mtfCandles, mtfIndicators)

        val longCore = fast > slow &&
            slow > priorSlow &&
            fast - slow >= LocalBotConfig.TREND_GAP_MIN &&
            c.low <= fast + LocalBotConfig.PULLBACK_MAX_DISTANCE &&
            c.close > c.open &&
            c.close > fast - LocalBotConfig.FAST_MA_CLOSE_BUFFER &&
            atr in LocalBotConfig.ATR_ENTRY_MIN..LocalBotConfig.ATR_ENTRY_MAX &&
            notOverextendedLong &&
            mtfLong.first

        val shortCore = fast < slow &&
            slow < priorSlow &&
            slow - fast >= LocalBotConfig.TREND_GAP_MIN &&
            c.high >= fast - LocalBotConfig.PULLBACK_MAX_DISTANCE &&
            c.close < c.open &&
            c.close < fast + LocalBotConfig.FAST_MA_CLOSE_BUFFER &&
            atr in LocalBotConfig.ATR_ENTRY_MIN..LocalBotConfig.ATR_ENTRY_MAX &&
            notOverextendedShort &&
            mtfShort.first

        if (longCore) {
            var score = 2
            val notes = mutableListOf("trend-aligned")
            if (fast - slow >= LocalBotConfig.TREND_GAP_STRONG) { score += 1; notes += "trend-gap-strong" }
            if (bodyStrength >= LocalBotConfig.BODY_STRENGTH_MIN) { score += 1; notes += "strong-body" }
            if (closeRatio >= LocalBotConfig.CLOSE_IN_RANGE_LONG_MIN) { score += 1; notes += "strong-close" }
            if (hist >= LocalBotConfig.MACD_LONG_MIN) { score += 1; notes += "macd-support" }
            if (rsi in LocalBotConfig.RSI_LONG_MIN..LocalBotConfig.RSI_LONG_MAX) { score += 1; notes += "rsi-support" }
            if (atr in LocalBotConfig.ATR_HEALTHY_MIN..LocalBotConfig.ATR_HEALTHY_MAX) { score += 1; notes += "atr-healthy" }
            if (notOverextendedLong) { score += 1; notes += "not-overextended" }
            if (goodLongWick) { score += 1; notes += "wick-clean" }
            notes += mtfLong.second
            score += mtfLong.second.size
            if (c.body >= LocalBotConfig.MIN_BODY_POINTS && c.range >= LocalBotConfig.MIN_RANGE_POINTS &&
                closeRatio >= LocalBotConfig.LONG_GATE_CLOSE_RATIO_MIN && score >= LocalBotConfig.MIN_ENTRY_SCORE &&
                goodLongWick) {
                val confirm = c.high + LocalBotConfig.CONFIRM_BUFFER
                val rawStop = c.low - LocalBotConfig.STOP_BUFFER_POINTS
                val stop = max(rawStop, confirm - LocalBotConfig.MAX_LOSS_POINTS_PER_TRADE)
                val risk = abs(confirm - stop).coerceAtLeast(1.0)
                val take = confirm + (risk * 2.0).roundToInt()
                return LocalSignalResult("signal", "long", confirm, stop, take, score, notes)
            }
        }
        if (shortCore) {
            var score = 2
            val notes = mutableListOf("trend-aligned")
            if (slow - fast >= LocalBotConfig.TREND_GAP_STRONG) { score += 1; notes += "trend-gap-strong" }
            if (bodyStrength >= LocalBotConfig.BODY_STRENGTH_MIN) { score += 1; notes += "strong-body" }
            if (closeRatio <= LocalBotConfig.CLOSE_IN_RANGE_SHORT_MAX) { score += 1; notes += "strong-close" }
            if (hist <= LocalBotConfig.MACD_SHORT_MAX) { score += 1; notes += "macd-support" }
            if (rsi in LocalBotConfig.RSI_SHORT_MIN..LocalBotConfig.RSI_SHORT_MAX) { score += 1; notes += "rsi-support" }
            if (atr in LocalBotConfig.ATR_HEALTHY_MIN..LocalBotConfig.ATR_HEALTHY_MAX) { score += 1; notes += "atr-healthy" }
            if (notOverextendedShort) { score += 1; notes += "not-overextended" }
            if (goodShortWick) { score += 1; notes += "wick-clean" }
            notes += mtfShort.second
            score += mtfShort.second.size
            if (c.body >= LocalBotConfig.MIN_BODY_POINTS && c.range >= LocalBotConfig.MIN_RANGE_POINTS &&
                closeRatio <= LocalBotConfig.SHORT_GATE_CLOSE_RATIO_MAX && score >= LocalBotConfig.MIN_ENTRY_SCORE &&
                goodShortWick) {
                val confirm = c.low - LocalBotConfig.CONFIRM_BUFFER
                val rawStop = c.high + LocalBotConfig.STOP_BUFFER_POINTS
                val stop = min(rawStop, confirm + LocalBotConfig.MAX_LOSS_POINTS_PER_TRADE)
                val risk = abs(confirm - stop).coerceAtLeast(1.0)
                val take = confirm - (risk * 2.0).roundToInt()
                return LocalSignalResult("signal", "short", confirm, stop, take, score, notes)
            }
        }
        return null
    }

    private fun sma(candles: List<LocalCandle>, end: Int, period: Int): Double? {
        val start = end - period + 1
        if (start < 0) return null
        var sum = 0.0
        for (i in start..end) sum += candles[i].close
        return sum / period.toDouble()
    }

    private fun buildMtfCandles(candles1m: List<LocalCandle>, minutes: Int): List<LocalCandle> {
        if (minutes <= 1) return candles1m
        val out = mutableListOf<LocalCandle>()
        val bucketSizeSec = minutes * 60L
        var bucketStart = Long.MIN_VALUE
        var bucketOpen = 0.0
        var bucketHigh = Double.NEGATIVE_INFINITY
        var bucketLow = Double.POSITIVE_INFINITY
        var bucketClose = 0.0
        var bucketLastTime = 0L
        for (c in candles1m) {
            val currentBucket = (c.time / bucketSizeSec) * bucketSizeSec
            if (bucketStart == Long.MIN_VALUE) {
                bucketStart = currentBucket
                bucketOpen = c.open
                bucketHigh = c.high
                bucketLow = c.low
                bucketClose = c.close
                bucketLastTime = c.time
                continue
            }
            if (currentBucket != bucketStart) {
                out += LocalCandle(
                    // Use bucket close time to avoid HTF lookahead on in-progress buckets.
                    time = bucketLastTime,
                    open = bucketOpen,
                    high = bucketHigh,
                    low = bucketLow,
                    close = bucketClose
                )
                bucketStart = currentBucket
                bucketOpen = c.open
                bucketHigh = c.high
                bucketLow = c.low
                bucketClose = c.close
                bucketLastTime = c.time
            } else {
                bucketHigh = max(bucketHigh, c.high)
                bucketLow = min(bucketLow, c.low)
                bucketClose = c.close
                bucketLastTime = c.time
            }
        }
        if (bucketStart != Long.MIN_VALUE) {
            out += LocalCandle(
                time = bucketLastTime,
                open = bucketOpen,
                high = bucketHigh,
                low = bucketLow,
                close = bucketClose
            )
        }
        return out
    }

    private fun passesMtf(
        direction: String,
        currentTime: Long,
        mtfCandles: Map<Int, List<LocalCandle>>,
        mtfIndicators: Map<Int, LocalIndicatorSet>
    ): Pair<Boolean, List<String>> {
        val order = listOf(5, 15, 30, 60)
        val notes = mutableListOf<String>()
        var aligned = 0
        var alignedFastContext = 0
        var considered = 0
        for (tf in order) {
            val candles = mtfCandles[tf] ?: continue
            val ind = mtfIndicators[tf] ?: continue
            val idx = candles.indexOfLast { it.time <= currentTime }
            if (idx < 0) continue
            val bias = getHtfBias(candles, ind, idx) ?: continue
            considered++
            val matches = (direction == "long" && bias == "bullish") || (direction == "short" && bias == "bearish")
            if (matches) {
                aligned++
                notes += "mtf-${tf}m-aligned"
                if (tf == 5 || tf == 15) alignedFastContext++
            }
        }
        if (considered < 1) return false to notes
        return (alignedFastContext >= 1 || aligned >= 2) to notes
    }

    private fun getHtfBias(candles: List<LocalCandle>, ind: LocalIndicatorSet, idx: Int): String? {
        val ef = ind.emaFast.getOrNull(idx) ?: return null
        val es = ind.emaSlow.getOrNull(idx) ?: return null
        val et = ind.emaTrend.getOrNull(idx) ?: return null
        val rsi = ind.rsi.getOrNull(idx) ?: return null
        val hist = ind.macdHistogram.getOrNull(idx) ?: return null
        val close = candles[idx].close
        val bullish = close >= ef &&
            ef > es &&
            es >= et - LocalBotConfig.MTF_EMA_TOLERANCE &&
            rsi >= LocalBotConfig.MTF_RSI_BULLISH_MIN &&
            hist >= LocalBotConfig.MTF_MACD_HIST_BULLISH_MIN
        if (bullish) return "bullish"
        val bearish = close <= ef &&
            ef < es &&
            es <= et + LocalBotConfig.MTF_EMA_TOLERANCE &&
            rsi <= LocalBotConfig.MTF_RSI_BEARISH_MAX &&
            hist <= LocalBotConfig.MTF_MACD_HIST_BEARISH_MAX
        if (bearish) return "bearish"
        return null
    }

    private data class PendingEntry(
        val direction: String,
        val confirmPrice: Double,
        val cancelPrice: Double,
        val signalIndex: Int,
        val signalCandle: LocalCandle,
        val setupScore: Int?,
        val setupNotes: List<String>?
    )

    private data class OpenPosition(
        val direction: String,
        val entryPrice: Double,
        var stopLoss: Double,
        val takeProfit: Double,
        val targetPrices: List<Double>,
        val entryIndex: Int,
        val quantity: Int,
        var targetsHit: Int = 0,
        var trailingStop: Double? = null,
        var bestPrice: Double = entryPrice,
        var worstPrice: Double = entryPrice
    )

    private data class ExitResult(
        val exitPrice: Double,
        val reason: String,
        val exitIndex: Int
    )

    private fun buildTargetLadder(entry: Double, stop: Double, direction: String): List<Double> {
        val risk = abs(entry - stop).coerceAtLeast(1.0)
        return LocalBotConfig.TARGET_MULTIPLIERS.map { mult ->
            val distance = (risk * mult).roundToInt().toDouble()
            if (direction == "long") entry + distance else entry - distance
        }
    }

    private fun updatePositionTracking(position: OpenPosition, candle: LocalCandle) {
        if (position.direction == "long") {
            position.bestPrice = max(position.bestPrice, candle.high)
            position.worstPrice = min(position.worstPrice, candle.low)
        } else {
            position.bestPrice = min(position.bestPrice, candle.low)
            position.worstPrice = max(position.worstPrice, candle.high)
        }
    }

    private fun advanceTargets(position: OpenPosition, candle: LocalCandle) {
        for (targetIdx in position.targetsHit until position.targetPrices.size) {
            val targetPrice = position.targetPrices[targetIdx]
            val hit = if (position.direction == "long") {
                candle.high >= targetPrice
            } else {
                candle.low <= targetPrice
            }
            if (!hit) break

            val newStop = if (targetIdx == 0) {
                if (position.direction == "long") position.entryPrice + FEE_LOCK_POINTS else position.entryPrice - FEE_LOCK_POINTS
            } else {
                position.targetPrices[targetIdx - 1]
            }
            if (position.direction == "long") {
                position.stopLoss = max(position.stopLoss, newStop)
            } else {
                position.stopLoss = min(position.stopLoss, newStop)
            }
            position.targetsHit = targetIdx + 1
        }
    }

    private fun updateTrailingStop(position: OpenPosition, candle: LocalCandle) {
        if (position.targetsHit < TRAILING_MIN_TARGETS_HIT) return
        val bestMove = if (position.direction == "long") {
            max(position.bestPrice, candle.high) - position.entryPrice
        } else {
            position.entryPrice - min(position.bestPrice, candle.low)
        }
        if (bestMove < TRAILING_ACTIVATION_POINTS) return
        val distance = when {
            bestMove >= TRAILING_MOVE_LARGE_THRESHOLD -> TRAILING_DISTANCE_LARGE
            bestMove >= TRAILING_MOVE_MEDIUM_THRESHOLD -> TRAILING_DISTANCE_MEDIUM
            else -> TRAILING_DISTANCE_SMALL
        }
        if (position.direction == "long") {
            val candidate = max(position.bestPrice, candle.high) - distance
            if (position.trailingStop == null || candidate > position.trailingStop!!) {
                position.trailingStop = candidate
            }
        } else {
            val candidate = min(position.bestPrice, candle.low) + distance
            if (position.trailingStop == null || candidate < position.trailingStop!!) {
                position.trailingStop = candidate
            }
        }
    }

    private fun checkSystemExit(position: OpenPosition, candle: LocalCandle, idx: Int): ExitResult? {
        return if (position.direction == "long") {
            when {
                candle.low <= position.stopLoss -> ExitResult(position.stopLoss, "stop_hit", idx)
                candle.high >= position.takeProfit -> ExitResult(position.takeProfit, "target_hit", idx)
                position.trailingStop != null && candle.low <= position.trailingStop!! -> ExitResult(position.trailingStop!!, "trailing_stop", idx)
                else -> null
            }
        } else {
            when {
                candle.high >= position.stopLoss -> ExitResult(position.stopLoss, "stop_hit", idx)
                candle.low <= position.takeProfit -> ExitResult(position.takeProfit, "target_hit", idx)
                position.trailingStop != null && candle.high >= position.trailingStop!! -> ExitResult(position.trailingStop!!, "trailing_stop", idx)
                else -> null
            }
        }
    }

    private fun shouldStrategyExit(position: OpenPosition, candle: LocalCandle, idx: Int, profileId: String): Boolean {
        val candlesInTrade = idx - position.entryIndex
        val closeRatio = if (candle.range > 0) (candle.close - candle.low) / candle.range else 0.5

        if (longProtectionEnabled(profileId) && position.direction == "long") {
            if (candlesInTrade >= 5) {
                val progress = position.bestPrice - position.entryPrice
                val body = candle.body
                val range = candle.range
                val failing = candle.close < candle.open &&
                    candle.close < position.entryPrice &&
                    closeRatio < 0.3 &&
                    (range == 0.0 || body / range >= 0.5)
                if (progress < 30.0 && failing) return true
            }
            if (candlesInTrade >= 8) {
                val progress = position.bestPrice - position.entryPrice
                val failing = candle.close < candle.open &&
                    candle.close < position.entryPrice &&
                    closeRatio < 0.4
                if (progress < 45.0 && failing) return true
            }
        }

        if (candlesInTrade < 6) return false
        return if (position.direction == "long") {
            val progress = position.bestPrice - position.entryPrice
            val failing = candle.close < candle.open && candle.close < position.entryPrice
            progress < 15.0 && failing
        } else {
            val progress = position.entryPrice - position.bestPrice
            val failing = candle.close > candle.open && candle.close > position.entryPrice
            progress < 15.0 && failing
        }
    }

    private fun quantityForProfile(profileId: String, score: Int): Int {
        if (profileId != "scaled_units" && profileId != "scaled_units_long_hold") return 1
        return when {
            score >= 12 -> 3
            score >= 10 -> 2
            else -> 1
        }
    }

    private fun longProtectionEnabled(profileId: String): Boolean {
        return profileId == "long_protection" ||
            profileId == "scaled_units" ||
            profileId == "scaled_units_long_hold"
    }
}
