package org.linphone.incomingcall.bot.local

import android.util.Log
import com.google.gson.JsonObject
import kotlin.math.abs

object BacktestParityLogger {
    private const val TAG = "BT_PARITY"

    private data class ExpectedSummary(
        val tradeCount: Int,
        val winCount: Int,
        val lossCount: Int,
        val winRate: Double,
        val netProfit: Double,
        val withdrawnProfit: Double,
        val totalFees: Double,
        val endingBalance: Double
    )

    private val expected = mapOf(
        "SH-day-1405-01-23" to mapOf(
            "baseline" to ExpectedSummary(
                tradeCount = 4,
                winCount = 4,
                lossCount = 0,
                winRate = 1.0,
                netProfit = 2_096_000.0,
                withdrawnProfit = 2_096_000.0,
                totalFees = 112_000.0,
                endingBalance = 6_900_000.0
            ),
            "long_protection" to ExpectedSummary(
                tradeCount = 4,
                winCount = 4,
                lossCount = 0,
                winRate = 1.0,
                netProfit = 2_096_000.0,
                withdrawnProfit = 2_096_000.0,
                totalFees = 112_000.0,
                endingBalance = 6_900_000.0
            ),
            "scaled_units" to ExpectedSummary(
                tradeCount = 4,
                winCount = 4,
                lossCount = 0,
                winRate = 1.0,
                netProfit = 4_192_000.0,
                withdrawnProfit = 4_192_000.0,
                totalFees = 224_000.0,
                endingBalance = 6_900_000.0
            )
        )
    )

    // Allow quick comparison against the known web reference without requiring manual file import.
    // SH-day-1405-01-23 is the web dataset label; M-day-2026-04-12 is the app day alias.
    private val datasetAliasToReference = mapOf(
        "SH-day-1405-01-23" to "SH-day-1405-01-23",
        "M-day-2026-04-12" to "SH-day-1405-01-23",
        "C-day-2026-04-12" to "SH-day-1405-01-23"
    )

    fun logComparison(datasetId: String, profileId: String, actual: JsonObject) {
        val referenceDataset = datasetAliasToReference[datasetId] ?: datasetId
        val expectedSummary = expected[referenceDataset]?.get(profileId)
        if (expectedSummary == null) {
            Log.i(TAG, "no expected summary configured dataset=$datasetId profile=$profileId")
            return
        }

        val mismatches = mutableListOf<String>()
        fun checkInt(name: String, actualValue: Int, expectedValue: Int) {
            if (actualValue != expectedValue) mismatches += "$name actual=$actualValue expected=$expectedValue"
        }
        fun checkDouble(name: String, actualValue: Double, expectedValue: Double, tolerance: Double = 0.5) {
            if (abs(actualValue - expectedValue) > tolerance) {
                mismatches += "$name actual=$actualValue expected=$expectedValue"
            }
        }

        checkInt("trade_count", actual.intValue("trade_count"), expectedSummary.tradeCount)
        checkInt("win_count", actual.intValue("win_count"), expectedSummary.winCount)
        checkInt("loss_count", actual.intValue("loss_count"), expectedSummary.lossCount)
        checkDouble("win_rate", actual.doubleValue("win_rate"), expectedSummary.winRate, tolerance = 1e-9)
        checkDouble("net_profit", actual.doubleValue("net_profit"), expectedSummary.netProfit)
        checkDouble("withdrawn_profit", actual.doubleValue("withdrawn_profit"), expectedSummary.withdrawnProfit)
        checkDouble("total_fees", actual.doubleValue("total_fees"), expectedSummary.totalFees)
        checkDouble("ending_balance", actual.doubleValue("ending_balance"), expectedSummary.endingBalance)

        if (mismatches.isEmpty()) {
            Log.i(TAG, "MATCH dataset=$datasetId profile=$profileId reference=$referenceDataset")
        } else {
            Log.w(
                TAG,
                "MISMATCH dataset=$datasetId profile=$profileId reference=$referenceDataset diffs=${mismatches.joinToString(" | ")}"
            )
        }
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
}

