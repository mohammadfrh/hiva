"""
Live signal converter - runs engine on the latest candle window and
returns a structured LiveSignalResponse.
"""

from __future__ import annotations
from src.types import (
    Candle, IndicatorSet, LiveSignalResponse, StrategyProfile,
)
from src.indicators import build_indicators
from src.strategies import PullbackTrendStrategy
from src.engine.core import EngineState, process_candle


def evaluate_live_signal(
    candles_1m: list[Candle],
    mtf_candles: dict[str, list[Candle]],
    profile: StrategyProfile,
) -> LiveSignalResponse:
    """
    Run engine over the full 1m candle window.
    Returns signal/close_signal/no_signal based on what happened on the last candle.

    This re-runs from scratch each call (stateless from caller's perspective).
    """
    if len(candles_1m) < 2:
        return LiveSignalResponse(type="no_signal")

    strategy = PullbackTrendStrategy(long_protection=profile.long_protection)
    indicators_1m = build_indicators(candles_1m)

    mtf_indicators: dict[str, IndicatorSet] = {}
    for tf, candles in mtf_candles.items():
        mtf_indicators[tf] = build_indicators(candles)

    state = EngineState()
    last_trade_count = 0
    last_position_open = False

    for i in range(len(candles_1m)):
        current_time = candles_1m[i].time
        aligned_mtf_candles: dict[str, list[Candle]] = {}
        aligned_mtf_indicators: dict[str, IndicatorSet] = {}

        for tf, htf_candles in mtf_candles.items():
            visible = [c for c in htf_candles if c.time <= current_time]
            if visible:
                n = len(visible)
                full_ind = mtf_indicators[tf]
                from src.types import IndicatorSet as IS
                aligned_mtf_indicators[tf] = IS(
                    ema_fast=full_ind.ema_fast[:n],
                    ema_slow=full_ind.ema_slow[:n],
                    ema_trend=full_ind.ema_trend[:n],
                    atr=full_ind.atr[:n],
                    rsi=full_ind.rsi[:n],
                    macd_line=full_ind.macd_line[:n],
                    macd_signal=full_ind.macd_signal[:n],
                    macd_histogram=full_ind.macd_histogram[:n],
                )
                aligned_mtf_candles[tf] = visible

        was_flat = state.is_flat
        prev_trade_count = len(state.trades)

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

        if i == len(candles_1m) - 1:
            last_trade_count = len(state.trades) - prev_trade_count
            last_position_open = (state.position is not None and was_flat)

    if last_position_open and state.position is not None:
        pos = state.position
        return LiveSignalResponse(
            type="signal",
            direction=pos.direction,
            entry_price=pos.entry_price,
            stop_loss=pos.stop_loss,
            take_profit=pos.take_profit,
            quantity=pos.quantity,
            target_prices=pos.target_prices,
            setup_score=pos.setup_score,
            setup_notes=pos.setup_notes,
            candle_time=candles_1m[-1].time,
            entry_time=pos.entry_time,
        )

    # Position is still open at latest candle: emit position_update so live manager
    # can keep broker TP/SL aligned with the same engine state used in backtest.
    if state.position is not None:
        pos = state.position
        return LiveSignalResponse(
            type="position_update",
            direction=pos.direction,
            entry_price=pos.entry_price,
            stop_loss=pos.stop_loss,
            take_profit=pos.take_profit,
            quantity=pos.quantity,
            target_prices=pos.target_prices,
            setup_score=pos.setup_score,
            setup_notes=pos.setup_notes,
            candle_time=candles_1m[-1].time,
            entry_time=pos.entry_time,
        )

    if last_trade_count > 0:
        last_trade = state.trades[-1]
        return LiveSignalResponse(
            type="close_signal",
            direction=last_trade.direction,
            exit_reason=last_trade.exit_reason,
            exit_price=last_trade.exit_price,
            pnl_price_points=last_trade.pnl_price_points,
            candle_time=candles_1m[-1].time,
        )

    return LiveSignalResponse(type="no_signal", candle_time=candles_1m[-1].time)

