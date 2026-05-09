"""
Backtest runner - wires candles, indicators, strategy and engine together,
then produces a BacktestSummary.
"""

from __future__ import annotations
from typing import Optional
import src.config as cfg
from src.types import (
    BacktestSummary, Candle, IndicatorSet, StrategyProfile,
    StrategySummary, Trade,
)
from src.indicators import build_indicators
from src.strategies import PullbackTrendStrategy
from src.engine.core import EngineState, close_open_position_end_of_data, process_candle


def run_backtest(
    dataset_name: str,
    profile: StrategyProfile,
    candles_1m: list[Candle],
    mtf_candles: dict[str, list[Candle]],
) -> BacktestSummary:
    """
    Run a full backtest on 1m candles with multi-timeframe context.

    Args:
        dataset_name: label for the run (e.g. "aday", "amonth")
        profile: strategy profile to use
        candles_1m: primary 1-minute candle series
        mtf_candles: dict of higher-TF candle series keyed by timeframe string
    """
    # Pass long_protection from profile to strategy (B6/B8)
    strategy = PullbackTrendStrategy(long_protection=profile.long_protection)
    indicators_1m = build_indicators(candles_1m)

    # Pre-build indicators for all higher TFs
    mtf_indicators: dict[str, IndicatorSet] = {}
    for tf, candles in mtf_candles.items():
        mtf_indicators[tf] = build_indicators(candles)

    state = EngineState()

    for i in range(len(candles_1m)):
        current_time = candles_1m[i].time
        aligned_mtf_candles: dict[str, list[Candle]] = {}
        aligned_mtf_indicators: dict[str, IndicatorSet] = {}

        for tf, htf_candles in mtf_candles.items():
            # Avoid lookahead: only candles whose time <= current 1m time
            # Binary search for efficiency
            lo, hi, ans = 0, len(htf_candles) - 1, -1
            while lo <= hi:
                mid = (lo + hi) // 2
                if htf_candles[mid].time <= current_time:
                    ans = mid
                    lo = mid + 1
                else:
                    hi = mid - 1

            if ans >= 0:
                n = ans + 1
                full_ind = mtf_indicators[tf]
                aligned_mtf_candles[tf] = htf_candles[:n]
                aligned_mtf_indicators[tf] = IndicatorSet(
                    ema_fast=full_ind.ema_fast[:n],
                    ema_slow=full_ind.ema_slow[:n],
                    ema_trend=full_ind.ema_trend[:n],
                    atr=full_ind.atr[:n],
                    rsi=full_ind.rsi[:n],
                    macd_line=full_ind.macd_line[:n],
                    macd_signal=full_ind.macd_signal[:n],
                    macd_histogram=full_ind.macd_histogram[:n],
                )

        process_candle(
            state=state,
            candle=candles_1m[i],
            candle_index=i,
            indicators=indicators_1m,
            strategy=strategy,
            profile=profile,
            mtf_candles=aligned_mtf_candles,
            mtf_indicators=aligned_mtf_indicators,
            all_candles=candles_1m,
        )

    # Close any remaining open position
    if state.position is not None:
        close_open_position_end_of_data(
            state,
            candles_1m,
            len(candles_1m) - 1,
            is_backtest=True,
        )

    return _build_summary(dataset_name, profile, state)


def _build_summary(
    dataset_name: str,
    profile: StrategyProfile,
    state: EngineState,
) -> BacktestSummary:
    engine_name = f"Multi-strategy 1m engine ({profile.profile_id})"

    assumptions = {
        "contractValue": cfg.CONTRACT_VALUE,
        "valuePerPoint": cfg.VALUE_PER_POINT,
        "stopLossRule": f"Signal candle extreme plus {cfg.STOP_BUFFER_POINTS}-point buffer",
        "startingBalance": cfg.STARTING_BALANCE,
        "deployableCapitalCap": cfg.DEPLOYABLE_CAPITAL_CAP,
        "feePerSide": cfg.FEE_PER_SIDE,
        "feePerRoundTrip": cfg.FEE_PER_ROUND_TRIP,
        "withdrawProfitsDaily": cfg.WITHDRAW_PROFITS_DAILY,
    }

    # C1: only count trades the engine actually executed (capital was sufficient)
    # The engine already skips trades if balance < CONTRACT_VALUE at trade time.
    # Here we also filter to executed trades (those with fee_money set).
    executed = [t for t in state.trades]

    summary = _strategy_summary(
        strategy_id=PullbackTrendStrategy.STRATEGY_ID,
        strategy_name=PullbackTrendStrategy.STRATEGY_NAME,
        trades=executed,
        initial_balance=cfg.STARTING_BALANCE,
        ending_balance=state.balance,
        withdrawn=state.withdrawn,
        max_drawdown=state.max_drawdown,
        peak_balance=state.peak_balance,
    )

    return BacktestSummary(
        engine_name=engine_name,
        assumptions=assumptions,
        summary=summary,
        per_strategy=[summary],
        trades=executed,
    )


def _strategy_summary(
    strategy_id: str,
    strategy_name: str,
    trades: list[Trade],
    initial_balance: float,
    ending_balance: float,
    withdrawn: float,
    max_drawdown: float,
    peak_balance: float,
) -> StrategySummary:
    wins = [t for t in trades if t.net_pnl_money > 0]
    losses = [t for t in trades if t.net_pnl_money <= 0]

    total_won = sum(t.net_pnl_money for t in wins)
    total_lost = sum(abs(t.net_pnl_money) for t in losses)
    total_fees = sum(t.fee_money for t in trades)
    net_profit = ending_balance + withdrawn - initial_balance   # matches TS formula
    win_rate = len(wins) / len(trades) if trades else 0.0
    profit_factor: Optional[float] = (total_won / total_lost) if total_lost > 0 else None
    avg_win = total_won / len(wins) if wins else 0.0
    avg_loss = total_lost / len(losses) if losses else 0.0
    max_dd_pct = (max_drawdown / peak_balance) if peak_balance > 0 else 0.0

    return StrategySummary(
        strategy_id=strategy_id,
        strategy_name=strategy_name,
        initial_balance=initial_balance,
        ending_balance=ending_balance,
        net_profit=net_profit,
        withdrawn_profit=withdrawn,
        total_fees=total_fees,
        trade_count=len(trades),
        win_count=len(wins),
        loss_count=len(losses),
        total_won=total_won,
        total_lost=total_lost,
        win_rate=win_rate,
        profit_factor=profit_factor,
        max_drawdown=max_drawdown,
        max_drawdown_percent=max_dd_pct,
        average_win=avg_win,
        average_loss=avg_loss,
    )
