package org.linphone.incomingcall.bot.local

import kotlin.math.abs
import kotlin.math.max

object LocalBotIndicators {
    fun build(candles: List<LocalCandle>): LocalIndicatorSet {
        val closes = candles.map { it.close }
        val emaFast = ema(closes, LocalBotConfig.EMA_FAST_PERIOD)
        val emaSlow = ema(closes, LocalBotConfig.EMA_SLOW_PERIOD)
        val emaTrend = ema(closes, LocalBotConfig.EMA_TREND_PERIOD)
        val atr = atr(candles, LocalBotConfig.ATR_PERIOD)
        val rsi = rsi(closes, LocalBotConfig.RSI_PERIOD)
        val (macdLine, macdSignal, macdHistogram) = macd(
            closes,
            LocalBotConfig.MACD_FAST,
            LocalBotConfig.MACD_SLOW,
            LocalBotConfig.MACD_SIGNAL
        )
        return LocalIndicatorSet(emaFast, emaSlow, emaTrend, atr, rsi, macdLine, macdSignal, macdHistogram)
    }

    private fun ema(values: List<Double>, period: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (values.size < period) return out
        val k = 2.0 / (period + 1.0)
        var prev = values.take(period).average()
        out[period - 1] = prev
        for (i in period until values.size) {
            val cur = values[i] * k + prev * (1.0 - k)
            out[i] = cur
            prev = cur
        }
        return out
    }

    private fun emaWilder(values: List<Double>, period: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (values.size < period) return out
        var prev = values.take(period).average()
        out[period - 1] = prev
        for (i in period until values.size) {
            val cur = (prev * (period - 1) + values[i]) / period
            out[i] = cur
            prev = cur
        }
        return out
    }

    private fun atr(candles: List<LocalCandle>, period: Int): List<Double?> {
        if (candles.isEmpty()) return emptyList()
        val tr = candles.mapIndexed { i, c ->
            if (i == 0) c.high - c.low else max(c.high - c.low, max(abs(c.high - candles[i - 1].close), abs(c.low - candles[i - 1].close)))
        }
        return emaWilder(tr, period)
    }

    private fun rsi(values: List<Double>, period: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (values.size <= period) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = values[i] - values[i - 1]
            if (d >= 0) gain += d else loss += abs(d)
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        out[period] = if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1 + avgGain / avgLoss)
        for (i in period + 1 until values.size) {
            val d = values[i] - values[i - 1]
            val g = max(d, 0.0)
            val l = max(-d, 0.0)
            avgGain = (avgGain * (period - 1) + g) / period
            avgLoss = (avgLoss * (period - 1) + l) / period
            out[i] = if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1 + avgGain / avgLoss)
        }
        return out
    }

    private fun macd(values: List<Double>, fast: Int, slow: Int, signal: Int): Triple<List<Double?>, List<Double?>, List<Double?>> {
        val fastEma = ema(values, fast)
        val slowEma = ema(values, slow)
        val line = values.indices.map { i ->
            val f = fastEma[i]
            val s = slowEma[i]
            if (f != null && s != null) f - s else null
        }
        val seeded = line.map { it ?: 0.0 }
        val sig = ema(seeded, signal)
        val hist = values.indices.map { i ->
            val l = line[i]
            val s = sig[i]
            if (l != null && s != null) l - s else null
        }
        return Triple(line, sig, hist)
    }
}
