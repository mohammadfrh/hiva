package org.linphone.incomingcall

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AutoTradeUiState(
    val isRunning: Boolean = false,
    val profileId: String = "baseline",
    val startedAtEpochSec: Long = 0L,
    val price: Double = 0.0,
    val bestBid: Double = 0.0,
    val bestAsk: Double = 0.0,
    val openPositions: Int = 0,
    val pendingOrders: Int = 0,
    val wsConnected: Boolean = false,
    val hasPortfolio: Boolean = false,
    val portfolioId: String = "",
    val portfolioUnits: Int = 0,
    val userBalance: Double = 0.0,
    val signalType: String = "no_signal",
    val signalDirection: String = "-",
    val signalReason: String = "",
    val sessionTradeCount: Int = 0,
    val sessionWinCount: Int = 0,
    val sessionLossCount: Int = 0,
    val sessionWinRate: Double = 0.0,
    val sessionNetPnl: Double = 0.0,
    val dayBacktestTradeCount: Int = 0,
    val dayBacktestWinCount: Int = 0,
    val dayBacktestLossCount: Int = 0,
    val dayBacktestWinRate: Double = 0.0,
    val dayBacktestNetPnl: Double = 0.0,
    val dayBacktestSignalsText: String = "",
    val orderAttempts: Int = 0,
    val ordersPlaced: Int = 0,
    val lastOrderId: String = "",
    val lastOrderAction: String = "",
    val lastOrderUnits: Int = 0,
    val lastOrderAtEpochSec: Long = 0L,
    val lastLiveTransactionId: String = "",
    val lastSubmitOk: Boolean? = null,
    val lastSubmitMessage: String = "",
    val lastSubmitAtEpochSec: Long = 0L,
    val lastDecision: String = "idle",
    val lastGateTrace: String = "",
    val lastError: String = "",
    val updatedAtEpochMs: Long = 0L
)

object AutoTradeStateStore {
    private val _state = MutableStateFlow(AutoTradeUiState())
    val state: StateFlow<AutoTradeUiState> = _state.asStateFlow()

    fun update(reducer: (AutoTradeUiState) -> AutoTradeUiState) {
        _state.value = reducer(_state.value)
    }

    fun reset() {
        _state.value = AutoTradeUiState()
    }
}
