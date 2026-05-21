"""
Execution engine - candle-by-candle position lifecycle.

Loop per candle (matches TS TradeEngine.processIndex order):
  1. If position open:
     a. Update best/worst price tracking
     b. Advance target ladder (move stop on target hits)
     c. Update trailing stop
     d. Check system exits: stop loss -> take profit -> trailing stop
     e. Check strategy-specific exit
  2. If flat: check pending entry activation (confirm/cancel/expiry)
  3. If flat and no pending: evaluate new entry signal
"""

from __future__ import annotations
from typing import Optional
import src.config as cfg
from src.types import (
    Candle, Direction, IndicatorSet, PendingEntry, Position,
    StrategyProfile, Trade,
)
from src.strategies import PullbackTrendStrategy, StrategyInterface, evaluate_swing_entry


# ---------------------------------------------------------------------------
# Target ladder builder (A2: multipliers fixed to [0.25 .. 2.0])
# ---------------------------------------------------------------------------

def build_target_ladder(entry: float, stop: float, direction: Direction) -> list[float]:
    """
    Build NUM_TARGETS price targets from entry risk.
    Uses TS-matching multipliers: [0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0].
    Distances are rounded to nearest integer (matching TS Math.round).
    """
    risk = max(1.0, abs(entry - stop))
    targets = []
    for mult in cfg.TARGET_RISK_MULTIPLIERS:
        distance = round(risk * mult)
        if direction == "long":
            targets.append(entry + distance)
        else:
            targets.append(entry - distance)
    return targets


# ---------------------------------------------------------------------------
# Engine state
# ---------------------------------------------------------------------------

class EngineState:
    def __init__(self) -> None:
        self.position: Optional[Position] = None
        self.pending: Optional[PendingEntry] = None
        self.trades: list[Trade] = []
        self.balance: float = cfg.STARTING_BALANCE
        self.peak_balance: float = cfg.STARTING_BALANCE
        self.max_drawdown: float = 0.0
        self.withdrawn: float = 0.0
        self.last_exit_time: int = 0
        self.consecutive_losses: int = 0
        self.pause_until_time: int = 0

    @property
    def is_flat(self) -> bool:
        return self.position is None


# ---------------------------------------------------------------------------
# Core candle processor
# ---------------------------------------------------------------------------

def process_candle(
    state: EngineState,
    candle: Candle,
    candle_index: int,
    indicators: IndicatorSet,
    strategy: StrategyInterface,
    profile: StrategyProfile,
    mtf_candles: dict[str, list[Candle]],
    mtf_indicators: dict[str, IndicatorSet],
    all_candles: list[Candle],
) -> None:
    if state.position is not None:
        _update_position_tracking(state.position, candle)
        if profile.swing_signal_timeframe_minutes > 0:
            _update_swing_trailing_stop(state.position, candle, profile)
        else:
            _advance_targets(state.position, candle, profile, candle_index)
            _update_trailing_stop(state.position, candle, profile)

        closed = _check_system_exits(state, candle, candle_index, profile)
        if not closed and profile.swing_signal_timeframe_minutes <= 0:
            _check_strategy_exit(state, candle, indicators, candle_index, strategy, profile)
        return

    # flat: check pending first
    if state.pending is not None:
        _check_pending_activation(state, candle, candle_index, indicators, strategy, profile)

    # if still flat and no pending, look for new signal
    if state.position is None and state.pending is None:
        if candle_index >= cfg.MIN_WARMUP_CANDLES and _may_seek_new_entry(state, candle, profile):
            if profile.swing_signal_timeframe_minutes > 0:
                signal = evaluate_swing_entry(all_candles, candle_index, profile)
            else:
                signal = strategy.evaluate_entry(
                    all_candles, indicators, candle_index,
                    mtf_candles, mtf_indicators,
                )
            if signal is not None and signal.score >= profile.min_score_to_trade:
                if profile.swing_signal_timeframe_minutes > 0:
                    _open_position(state, candle, candle_index, strategy, profile, signal)
                else:
                    state.pending = PendingEntry(signal=signal, created_at_index=candle_index)


# ---------------------------------------------------------------------------
# Position tracking
# ---------------------------------------------------------------------------

def _update_position_tracking(position: Position, candle: Candle) -> None:
    if position.direction == "long":
        position.best_price = max(position.best_price, candle.high)
        position.worst_price = min(position.worst_price, candle.low)
    else:
        position.best_price = min(position.best_price, candle.low)
        position.worst_price = max(position.worst_price, candle.high)


# ---------------------------------------------------------------------------
# Target ladder advancement
# ---------------------------------------------------------------------------

def _trade_cooldown_elapsed(state: EngineState, candle: Candle, profile: StrategyProfile) -> bool:
    if profile.min_minutes_between_trades <= 0 or state.last_exit_time <= 0:
        return True
    return (candle.time - state.last_exit_time) >= profile.min_minutes_between_trades * 60


def _loss_streak_pause_active(state: EngineState, candle: Candle, profile: StrategyProfile) -> bool:
    if profile.loss_streak_pause_count <= 0 or profile.loss_streak_pause_minutes <= 0:
        return False
    return state.pause_until_time > 0 and candle.time < state.pause_until_time


def _may_seek_new_entry(state: EngineState, candle: Candle, profile: StrategyProfile) -> bool:
    if _loss_streak_pause_active(state, candle, profile):
        return False
    return _trade_cooldown_elapsed(state, candle, profile)


def _max_loss_points(profile: StrategyProfile) -> int:
    if profile.max_loss_points_per_trade is not None:
        return profile.max_loss_points_per_trade
    return cfg.MAX_LOSS_POINTS_PER_TRADE


def _stop_buffer_points(profile: StrategyProfile) -> int:
    if profile.stop_buffer_points is not None:
        return profile.stop_buffer_points
    return cfg.STOP_BUFFER_POINTS


def _fee_lock_points(profile: StrategyProfile) -> int:
    if profile.fee_lock_points is not None:
        return profile.fee_lock_points
    return cfg.FEE_LOCK_POINTS


def _favorable_points(pos: Position) -> float:
    if pos.direction == "long":
        return pos.best_price - pos.entry_price
    return pos.entry_price - pos.best_price


def _advance_targets(
    pos: Position,
    candle: Candle,
    profile: StrategyProfile,
    candle_index: int,
) -> None:
    """Check each target in sequence and advance stop loss on hits."""
    candles_in_trade = candle_index - pos.entry_index
    for target_idx in range(pos.targets_hit, len(pos.target_prices)):
        target_price = pos.target_prices[target_idx]
        hit = (
            (pos.direction == "long" and candle.high >= target_price)
            or (pos.direction == "short" and candle.low <= target_price)
        )
        if not hit:
            break

        pos.targets_hit = target_idx + 1

        if candles_in_trade < profile.min_candles_before_stop_tighten:
            continue
        if profile.min_favorable_points_before_stop_tighten > 0:
            if _favorable_points(pos) < profile.min_favorable_points_before_stop_tighten:
                continue
        if target_idx < profile.fee_lock_after_target_index:
            continue

        lock_pts = _fee_lock_points(profile)
        if target_idx == profile.fee_lock_after_target_index:
            new_stop = (
                pos.entry_price + lock_pts
                if pos.direction == "long"
                else pos.entry_price - lock_pts
            )
        else:
            new_stop = pos.target_prices[target_idx - 1]

        if pos.direction == "long":
            pos.stop_loss = max(pos.stop_loss, new_stop)
        else:
            pos.stop_loss = min(pos.stop_loss, new_stop)


# ---------------------------------------------------------------------------
# Trailing stop (B7: fixed point distances matching TS)
# ---------------------------------------------------------------------------

def _trailing_min_targets_hit(profile: StrategyProfile) -> int:
    if profile.trailing_min_targets_hit is not None:
        return profile.trailing_min_targets_hit
    return cfg.TRAILING_MIN_TARGETS_HIT


def _update_trailing_stop(pos: Position, candle: Candle, profile: StrategyProfile) -> None:
    """
    Trailing stop activates after TRAILING_MIN_TARGETS_HIT targets are hit
    AND favorable move exceeds TRAILING_ACTIVATION_POINTS.
    Uses fixed distances: 55/75/95 pts depending on move size. (B7)
    """
    if pos.targets_hit < _trailing_min_targets_hit(profile):
        return

    if pos.direction == "long":
        best = max(pos.best_price, candle.high)
        move = best - pos.entry_price
    else:
        best = min(pos.best_price, candle.low)
        move = pos.entry_price - best

    if move < cfg.TRAILING_ACTIVATION_POINTS:
        return

    if move >= cfg.TRAILING_MOVE_LARGE_THRESHOLD:
        distance = cfg.TRAILING_DISTANCE_LARGE
    elif move >= cfg.TRAILING_MOVE_MEDIUM_THRESHOLD:
        distance = cfg.TRAILING_DISTANCE_MEDIUM
    else:
        distance = cfg.TRAILING_DISTANCE_SMALL

    if pos.direction == "long":
        candidate = best - distance
        if pos.trailing_stop is None or candidate > pos.trailing_stop:
            pos.trailing_stop = candidate
    else:
        candidate = best + distance
        if pos.trailing_stop is None or candidate < pos.trailing_stop:
            pos.trailing_stop = candidate


def _update_swing_trailing_stop(pos: Position, candle: Candle, profile: StrategyProfile) -> None:
    if profile.swing_trail_start_points <= 0 or profile.swing_trail_distance_points <= 0:
        return

    if pos.direction == "long":
        best = max(pos.best_price, candle.high)
        move = best - pos.entry_price
        if move < profile.swing_trail_start_points:
            return
        candidate = best - profile.swing_trail_distance_points
        if pos.trailing_stop is None or candidate > pos.trailing_stop:
            pos.trailing_stop = candidate
    else:
        best = min(pos.best_price, candle.low)
        move = pos.entry_price - best
        if move < profile.swing_trail_start_points:
            return
        candidate = best + profile.swing_trail_distance_points
        if pos.trailing_stop is None or candidate < pos.trailing_stop:
            pos.trailing_stop = candidate


# ---------------------------------------------------------------------------
# System exits (order matches TS: stop loss -> take profit -> trailing stop)
# ---------------------------------------------------------------------------

def _is_profitable_stop(pos: Position) -> bool:
    if pos.direction == "long":
        return pos.stop_loss > pos.entry_price
    return pos.stop_loss < pos.entry_price


def _profit_stop_reversal_close_hit(pos: Position, candle: Candle) -> bool:
    if pos.direction == "long":
        return candle.close <= pos.stop_loss and candle.close < candle.open
    return candle.close >= pos.stop_loss and candle.close > candle.open


def _check_system_exits(
    state: EngineState,
    candle: Candle,
    candle_index: int,
    profile: StrategyProfile,
) -> bool:
    pos = state.position
    assert pos is not None

    if profile.swing_signal_timeframe_minutes > 0:
        return _check_swing_system_exits(state, candle, candle_index, profile)

    if pos.direction == "long":
        if candle.low <= pos.stop_loss:
            if (
                not profile.profit_stop_requires_reversal_close
                or not _is_profitable_stop(pos)
                or _profit_stop_reversal_close_hit(pos, candle)
            ):
                _close_position(state, candle, candle_index, "stopLoss", pos.stop_loss, profile)
                return True
        if candle.high >= pos.take_profit:
            _close_position(state, candle, candle_index, "takeProfit", pos.take_profit, profile)
            return True
        if pos.trailing_stop is not None and candle.low <= pos.trailing_stop:
            _close_position(state, candle, candle_index, "trailingStop", pos.trailing_stop, profile)
            return True
    else:
        if candle.high >= pos.stop_loss:
            if (
                not profile.profit_stop_requires_reversal_close
                or not _is_profitable_stop(pos)
                or _profit_stop_reversal_close_hit(pos, candle)
            ):
                _close_position(state, candle, candle_index, "stopLoss", pos.stop_loss, profile)
                return True
        if candle.low <= pos.take_profit:
            _close_position(state, candle, candle_index, "takeProfit", pos.take_profit, profile)
            return True
        if pos.trailing_stop is not None and candle.high >= pos.trailing_stop:
            _close_position(state, candle, candle_index, "trailingStop", pos.trailing_stop, profile)
            return True

    return False


def _check_swing_system_exits(
    state: EngineState,
    candle: Candle,
    candle_index: int,
    profile: StrategyProfile,
) -> bool:
    pos = state.position
    assert pos is not None
    holding_minutes = max(0, candle.time - pos.entry_time) // 60

    if pos.direction == "long":
        if candle.low <= pos.stop_loss:
            _close_position(state, candle, candle_index, "stopLoss", pos.stop_loss, profile)
            return True
        if (
            pos.trailing_stop is not None
            and holding_minutes >= profile.swing_min_profit_hold_minutes
            and candle.close <= pos.trailing_stop
            and candle.close < candle.open
        ):
            _close_position(state, candle, candle_index, "trailingStop", pos.trailing_stop, profile)
            return True
    else:
        if candle.high >= pos.stop_loss:
            _close_position(state, candle, candle_index, "stopLoss", pos.stop_loss, profile)
            return True
        if (
            pos.trailing_stop is not None
            and holding_minutes >= profile.swing_min_profit_hold_minutes
            and candle.close >= pos.trailing_stop
            and candle.close > candle.open
        ):
            _close_position(state, candle, candle_index, "trailingStop", pos.trailing_stop, profile)
            return True

    if profile.swing_max_hold_minutes > 0 and holding_minutes >= profile.swing_max_hold_minutes:
        _close_position(state, candle, candle_index, "strategyExit", candle.close, profile)
        return True
    return False


def _check_strategy_exit(
    state: EngineState,
    candle: Candle,
    indicators: IndicatorSet,
    candle_index: int,
    strategy: StrategyInterface,
    profile: StrategyProfile,
) -> bool:
    pos = state.position
    assert pos is not None
    if strategy.evaluate_exit(pos, candle, indicators, candle_index, profile):
        _close_position(state, candle, candle_index, "strategyExit", candle.close, profile)
        return True
    return False


# ---------------------------------------------------------------------------
# Pending entry activation (A6: expiry after 1 candle; B5: two-layer confirmation)
# ---------------------------------------------------------------------------

def _check_pending_activation(
    state: EngineState,
    candle: Candle,
    candle_index: int,
    indicators: IndicatorSet,
    strategy: StrategyInterface,
    profile: StrategyProfile,
) -> None:
    pending = state.pending
    assert pending is not None
    sig = pending.signal

    # Must be strictly after signal candle (not same candle)
    if candle_index <= sig.candle_index:
        return

    # A6: expire after 1 candle (only try next candle)
    if candle_index > sig.candle_index + cfg.PENDING_ENTRY_EXPIRY_CANDLES:
        state.pending = None
        return

    if sig.direction == "long":
        confirmed = candle.high >= sig.confirm_price
        canceled = candle.low <= sig.cancel_price

        if confirmed and canceled:
            state.pending = None
            return
        if canceled:
            state.pending = None
            return

        if confirmed:
            # B5: two-layer confirmation check for longs
            close_strength = (
                (candle.close - candle.low) / (candle.high - candle.low)
                if candle.high != candle.low else 0.5
            )
            body = abs(candle.close - candle.open)
            range_ = candle.high - candle.low
            upper_wick = candle.high - max(candle.open, candle.close)
            lower_wick = min(candle.open, candle.close) - candle.low

            weak = (
                candle.close <= candle.open
                or candle.close < sig.confirm_price
                or close_strength < 0.55
                or (range_ > 0 and body / range_ < 0.25)
            )
            adverse = (
                close_strength < 0.68
                or upper_wick > max(18, body)
                or lower_wick < 4
                or candle.close - sig.confirm_price < 6
            )
            if weak or adverse:
                state.pending = None
                return

            _open_position(state, candle, candle_index, strategy, profile, sig)

    else:  # short
        confirmed = candle.low <= sig.confirm_price
        canceled = candle.high >= sig.cancel_price

        if confirmed and canceled:
            state.pending = None
            return
        if canceled:
            state.pending = None
            return

        if confirmed:
            # B5: weak confirmation check for shorts (no adverse check, matching TS)
            close_strength = (
                (candle.close - candle.low) / (candle.high - candle.low)
                if candle.high != candle.low else 0.5
            )
            body = abs(candle.close - candle.open)
            range_ = candle.high - candle.low

            weak = (
                candle.close >= candle.open
                or candle.close > sig.confirm_price
                or close_strength > 0.45
                or (range_ > 0 and body / range_ < 0.25)
            )
            if weak:
                state.pending = None
                return

            _open_position(state, candle, candle_index, strategy, profile, sig)


def _open_position(
    state: EngineState,
    candle: Candle,
    candle_index: int,
    strategy: StrategyInterface,
    profile: StrategyProfile,
    sig,
) -> None:
    entry_price = sig.confirm_price

    max_loss = _max_loss_points(profile)
    if profile.swing_signal_timeframe_minutes > 0:
        if sig.direction == "long":
            stop_loss = entry_price - max_loss
        else:
            stop_loss = entry_price + max_loss
    else:
        stop_buf = _stop_buffer_points(profile)
        if sig.direction == "long":
            raw_stop = sig.candle.low - stop_buf
            stop_loss = max(raw_stop, entry_price - max_loss)
        else:
            raw_stop = sig.candle.high + stop_buf
            stop_loss = min(raw_stop, entry_price + max_loss)

    if profile.swing_signal_timeframe_minutes > 0 and profile.swing_disable_take_profit:
        far_take_profit_points = max(2000, profile.swing_trail_start_points * 10)
        take_profit = (
            entry_price + far_take_profit_points
            if sig.direction == "long"
            else entry_price - far_take_profit_points
        )
        targets = [take_profit]
    else:
        targets = build_target_ladder(entry_price, stop_loss, sig.direction)
        take_profit = targets[-1]
    quantity = strategy.get_quantity(profile, sig.score)

    pos = Position(
        direction=sig.direction,
        entry_price=entry_price,
        entry_time=candle.time,
        entry_index=candle_index,
        quantity=quantity,
        stop_loss=stop_loss,
        take_profit=take_profit,
        initial_risk=abs(entry_price - stop_loss),
        target_prices=targets,
        setup_score=sig.score,
        setup_notes=sig.notes + ["confirmed-next-candle"],
    )
    pos.best_price = entry_price
    pos.worst_price = entry_price

    state.position = pos
    state.pending = None


# ---------------------------------------------------------------------------
# Close position
# ---------------------------------------------------------------------------

def _close_position(
    state: EngineState,
    candle: Candle,
    candle_index: int,
    reason: str,
    exit_price: float,
    profile: StrategyProfile,
) -> None:
    pos = state.position
    assert pos is not None

    if pos.direction == "long":
        pnl_points = exit_price - pos.entry_price
        mfe = pos.best_price - pos.entry_price
        mae = pos.entry_price - pos.worst_price
    else:
        pnl_points = pos.entry_price - exit_price
        mfe = pos.entry_price - pos.best_price
        mae = pos.worst_price - pos.entry_price

    pnl_money = pnl_points * cfg.VALUE_PER_POINT * pos.quantity
    fee = cfg.FEE_PER_ROUND_TRIP * pos.quantity
    net_pnl = pnl_money - fee

    balance_before = state.balance
    state.balance += net_pnl

    if cfg.WITHDRAW_PROFITS_DAILY and state.balance > cfg.DEPLOYABLE_CAPITAL_CAP:
        state.withdrawn += state.balance - cfg.DEPLOYABLE_CAPITAL_CAP
        state.balance = cfg.DEPLOYABLE_CAPITAL_CAP

    state.balance = max(state.balance, 0.0)
    state.peak_balance = max(state.peak_balance, state.balance)
    dd = state.peak_balance - state.balance
    state.max_drawdown = max(state.max_drawdown, dd)

    from datetime import datetime, timezone
    exit_day = datetime.fromtimestamp(candle.time, tz=timezone.utc).strftime("%Y-%m-%d")

    # holding_candles: use time difference in seconds / 60 (matching TS)
    holding = max(1, candle.time - pos.entry_time) // 60

    trade = Trade(
        strategy_id=PullbackTrendStrategy.STRATEGY_ID,
        direction=pos.direction,
        quantity=pos.quantity,
        entry_time=pos.entry_time,
        exit_time=candle.time,
        entry_price=pos.entry_price,
        exit_price=exit_price,
        stop_loss=pos.stop_loss,
        take_profit=pos.take_profit,
        trailing_stop=pos.trailing_stop,
        pnl_price_points=pnl_points,
        pnl_money=pnl_money,
        exit_reason=reason,  # type: ignore[arg-type]
        setup_score=pos.setup_score,
        setup_notes=pos.setup_notes,
        target_prices=pos.target_prices,
        targets_hit=pos.targets_hit,
        holding_candles=holding,
        max_favorable_excursion=max(mfe, 0.0),
        max_adverse_excursion=max(mae, 0.0),
        fee_money=fee,
        net_pnl_money=net_pnl,
        balance_before=balance_before,
        balance_after=state.balance,
        drawdown_after_trade=dd,
        exit_day=exit_day,
    )

    state.trades.append(trade)
    state.last_exit_time = candle.time
    if net_pnl < 0:
        state.consecutive_losses += 1
        if (
            profile.loss_streak_pause_count > 0
            and profile.loss_streak_pause_minutes > 0
            and state.consecutive_losses >= profile.loss_streak_pause_count
        ):
            state.pause_until_time = candle.time + profile.loss_streak_pause_minutes * 60
    else:
        state.consecutive_losses = 0
        state.pause_until_time = 0
    state.position = None


def close_open_position_end_of_data(
    state: EngineState,
    candles: list[Candle],
    candle_index: int,
    profile: StrategyProfile,
    *,
    is_backtest: bool = False,
) -> None:
    if (
        is_backtest
        and state.position is not None
        and candle_index == len(candles) - 1
    ):
        last = candles[candle_index]
        _close_position(state, last, candle_index, "endOfData", last.close, profile)
