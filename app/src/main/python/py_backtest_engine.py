from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Optional, Literal


# --- Config (mirrors python-bot/src/config.py) ---
CONTRACT_VALUE = 2_300_000
VALUE_PER_POINT = 23_000
FEE_PER_SIDE = 14_000
FEE_PER_ROUND_TRIP = FEE_PER_SIDE * 2
STARTING_BALANCE = CONTRACT_VALUE * 3
MAX_LOSS_POINTS_PER_TRADE = 70
STOP_BUFFER_POINTS = 8
CONFIRM_CANCEL_BUFFER_POINTS = 4
EMA_FAST_PERIOD = 12
EMA_SLOW_PERIOD = 20
EMA_TREND_PERIOD = 50
ATR_PERIOD = 14
RSI_PERIOD = 14
MACD_FAST_PERIOD = 12
MACD_SLOW_PERIOD = 26
MACD_SIGNAL_PERIOD = 9
ATR_ENTRY_MIN = 14
ATR_ENTRY_MAX = 280
ATR_HEALTHY_MIN = 22
ATR_HEALTHY_MAX = 210
TREND_GAP_MIN = 3
TREND_GAP_STRONG = 14
SLOPE_LOOKBACK = 8
PULLBACK_MAX_DISTANCE = 10
FAST_MA_CLOSE_BUFFER = 2
OVEREXTENDED_LOOKBACK = 10
OVEREXTENDED_MAX_POINTS = 110
BODY_STRENGTH_MIN = 0.32
CLOSE_IN_RANGE_LONG_MIN = 0.58
CLOSE_IN_RANGE_SHORT_MAX = 0.42
MIN_BODY_POINTS = 8
MIN_RANGE_POINTS = 10
LONG_GATE_CLOSE_RATIO_MIN = 0.46
SHORT_GATE_CLOSE_RATIO_MAX = 0.54
MIN_ENTRY_SCORE = 2
WICK_MAX_POINTS = 18
WICK_MAX_BODY_RATIO = 1.05
RSI_LONG_MIN = 38
RSI_LONG_MAX = 80
RSI_SHORT_MIN = 20
RSI_SHORT_MAX = 62
MACD_LONG_MIN = -40
MACD_SHORT_MAX = 40
PENDING_ENTRY_EXPIRY_CANDLES = 1
TARGET_RISK_MULTIPLIERS = [0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0]
FEE_LOCK_POINTS = 2
TRAILING_MIN_TARGETS_HIT = 2
TRAILING_ACTIVATION_POINTS = 110
TRAILING_DISTANCE_SMALL = 55
TRAILING_DISTANCE_MEDIUM = 75
TRAILING_DISTANCE_LARGE = 95
TRAILING_MOVE_MEDIUM_THRESHOLD = 200
TRAILING_MOVE_LARGE_THRESHOLD = 300
MTF_EMA_TOLERANCE = 6
MTF_RSI_BULLISH_MIN = 48
MTF_RSI_BEARISH_MAX = 52
MTF_MACD_HIST_BULLISH_MIN = -10
MTF_MACD_HIST_BEARISH_MAX = 10
MIN_WARMUP_CANDLES = 28
LONG_PROTECTION_EXIT_CANDLES_1 = 5
LONG_PROTECTION_PROGRESS_1 = 30
LONG_PROTECTION_EXIT_CANDLES_2 = 8
LONG_PROTECTION_PROGRESS_2 = 45
GENERAL_EXIT_CANDLES = 6
GENERAL_EXIT_PROGRESS = 15


Direction = Literal["long", "short"]


@dataclass
class Candle:
    time: int
    open: float
    high: float
    low: float
    close: float

    @property
    def range(self) -> float:
        return self.high - self.low

    @property
    def body(self) -> float:
        return abs(self.close - self.open)


@dataclass
class IndicatorSet:
    ema_fast: list[Optional[float]]
    ema_slow: list[Optional[float]]
    ema_trend: list[Optional[float]]
    atr: list[Optional[float]]
    rsi: list[Optional[float]]
    macd_line: list[Optional[float]]
    macd_signal: list[Optional[float]]
    macd_histogram: list[Optional[float]]


@dataclass
class SignalCandle:
    candle: Candle
    candle_index: int
    direction: Direction
    score: int
    notes: list[str]
    confirm_price: float
    cancel_price: float


@dataclass
class PendingEntry:
    signal: SignalCandle
    created_at_index: int


@dataclass
class Position:
    direction: Direction
    entry_price: float
    entry_time: int
    entry_index: int
    quantity: int
    stop_loss: float
    take_profit: float
    initial_risk: float
    target_prices: list[float]
    targets_hit: int = 0
    trailing_stop: Optional[float] = None
    best_price: float = 0.0
    worst_price: float = 0.0
    setup_score: int = 0
    setup_notes: list[str] = field(default_factory=list)

    def __post_init__(self):
        self.best_price = self.entry_price
        self.worst_price = self.entry_price


@dataclass
class Trade:
    net_pnl_money: float


class EngineState:
    def __init__(self):
        self.position: Optional[Position] = None
        self.pending: Optional[PendingEntry] = None
        self.trades: list[Trade] = []
        self.balance = float(STARTING_BALANCE)
        self.peak_balance = float(STARTING_BALANCE)
        self.max_drawdown = 0.0
        self.withdrawn = 0.0


def _ema_standard(values: list[float], period: int) -> list[Optional[float]]:
    out: list[Optional[float]] = [None] * len(values)
    if len(values) < period:
        return out
    k = 2.0 / (period + 1)
    prev = sum(values[:period]) / period
    out[period - 1] = prev
    for i in range(period, len(values)):
        prev = values[i] * k + prev * (1 - k)
        out[i] = prev
    return out


def _ema_wilder(values: list[float], period: int) -> list[Optional[float]]:
    out: list[Optional[float]] = [None] * len(values)
    if len(values) < period:
        return out
    prev = sum(values[:period]) / period
    out[period - 1] = prev
    for i in range(period, len(values)):
        prev = (prev * (period - 1) + values[i]) / period
        out[i] = prev
    return out


def _ema(values: list[Optional[float]], period: int) -> list[Optional[float]]:
    clean_idx = [i for i, v in enumerate(values) if v is not None]
    if not clean_idx:
        return [None] * len(values)
    clean = [values[i] for i in clean_idx]  # type: ignore[index]
    raw = _ema_standard(clean, period)
    out: list[Optional[float]] = [None] * len(values)
    j = 0
    for i in clean_idx:
        out[i] = raw[j]
        j += 1
    return out


def _atr(candles: list[Candle], period: int) -> list[Optional[float]]:
    if len(candles) < period:
        return [None] * len(candles)
    tr = []
    for i, c in enumerate(candles):
        if i == 0:
            tr.append(c.high - c.low)
        else:
            pc = candles[i - 1].close
            tr.append(max(c.high - c.low, abs(c.high - pc), abs(c.low - pc)))
    return _ema_wilder(tr, period)


def _rsi(values: list[Optional[float]], period: int) -> list[Optional[float]]:
    out: list[Optional[float]] = [None] * len(values)
    idxs = [i for i, v in enumerate(values) if v is not None]
    if len(idxs) <= period:
        return out
    clean = [values[i] for i in idxs]  # type: ignore[index]
    gain = 0.0
    loss = 0.0
    for i in range(1, period + 1):
        d = clean[i] - clean[i - 1]  # type: ignore[operator]
        if d >= 0:
            gain += d
        else:
            loss += abs(d)
    ag = gain / period
    al = loss / period
    out[idxs[period]] = 100.0 if al == 0 else 100.0 - 100.0 / (1 + ag / al)
    for i in range(period + 1, len(clean)):
        d = clean[i] - clean[i - 1]  # type: ignore[operator]
        g = max(d, 0.0)
        l = max(-d, 0.0)
        ag = (ag * (period - 1) + g) / period
        al = (al * (period - 1) + l) / period
        out[idxs[i]] = 100.0 if al == 0 else 100.0 - 100.0 / (1 + ag / al)
    return out


def _macd(values: list[Optional[float]]) -> tuple[list[Optional[float]], list[Optional[float]], list[Optional[float]]]:
    fast = _ema(values, MACD_FAST_PERIOD)
    slow = _ema(values, MACD_SLOW_PERIOD)
    line: list[Optional[float]] = []
    for f, s in zip(fast, slow):
        line.append((f - s) if f is not None and s is not None else None)
    seeded = [v if v is not None else 0.0 for v in line]
    sig = _ema_standard(seeded, MACD_SIGNAL_PERIOD)
    hist: list[Optional[float]] = []
    for l, s in zip(line, sig):
        hist.append((l - s) if l is not None and s is not None else None)
    return line, sig, hist


def build_indicators(candles: list[Candle]) -> IndicatorSet:
    closes: list[Optional[float]] = [c.close for c in candles]
    ef = _ema(closes, EMA_FAST_PERIOD)
    es = _ema(closes, EMA_SLOW_PERIOD)
    et = _ema(closes, EMA_TREND_PERIOD)
    atr = _atr(candles, ATR_PERIOD)
    rsi = _rsi(closes, RSI_PERIOD)
    ml, ms, mh = _macd(closes)
    return IndicatorSet(ef, es, et, atr, rsi, ml, ms, mh)


def _sma(candles: list[Candle], end: int, period: int) -> Optional[float]:
    start = end - period + 1
    if start < 0:
        return None
    return sum(candles[i].close for i in range(start, end + 1)) / period


def _htf_bias(candles: list[Candle], ind: IndicatorSet) -> Optional[str]:
    if not candles:
        return None
    i = len(candles) - 1
    ef, es, et = ind.ema_fast[i], ind.ema_slow[i], ind.ema_trend[i]
    rsi, hist = ind.rsi[i], ind.macd_histogram[i]
    if any(v is None for v in [ef, es, et, rsi, hist]):
        return None
    close = candles[i].close
    if close >= ef and ef > es and es >= et - MTF_EMA_TOLERANCE and rsi >= MTF_RSI_BULLISH_MIN and hist >= MTF_MACD_HIST_BULLISH_MIN:
        return "bullish"
    if close <= ef and ef < es and es <= et + MTF_EMA_TOLERANCE and rsi <= MTF_RSI_BEARISH_MAX and hist <= MTF_MACD_HIST_BEARISH_MAX:
        return "bearish"
    return None


def _passes_mtf(direction: Direction, mtf_candles: dict[str, list[Candle]], mtf_indicators: dict[str, IndicatorSet]) -> tuple[bool, list[str]]:
    notes: list[str] = []
    aligned = 0
    aligned_fast = 0
    considered = 0
    for tf in ["5m", "15m", "30m", "60m"]:
        candles = mtf_candles.get(tf)
        ind = mtf_indicators.get(tf)
        if not candles or not ind:
            continue
        bias = _htf_bias(candles, ind)
        if bias is None:
            continue
        considered += 1
        matches = (direction == "long" and bias == "bullish") or (direction == "short" and bias == "bearish")
        if matches:
            aligned += 1
            notes.append(f"mtf-{tf}-aligned")
            if tf in ("5m", "15m"):
                aligned_fast += 1
    if considered < 1:
        return False, notes
    return aligned_fast >= 1 or aligned >= 2, notes


def _quantity(profile_id: str, score: int) -> int:
    if profile_id != "scaled_units":
        return 1
    if score >= 12:
        return 3
    if score >= 10:
        return 2
    return 1


def _long_protection(profile_id: str) -> bool:
    return profile_id in ("long_protection", "scaled_units")


def _build_targets(entry: float, stop: float, direction: Direction) -> list[float]:
    risk = max(1.0, abs(entry - stop))
    out = []
    for mult in TARGET_RISK_MULTIPLIERS:
        dist = round(risk * mult)
        out.append(entry + dist if direction == "long" else entry - dist)
    return out


def _evaluate_entry(candles: list[Candle], ind: IndicatorSet, i: int, mtf_candles: dict[str, list[Candle]], mtf_indicators: dict[str, IndicatorSet]) -> Optional[SignalCandle]:
    if i < MIN_WARMUP_CANDLES or i < 1:
        return None
    c = candles[i]
    fast = _sma(candles, i, EMA_FAST_PERIOD)
    slow = _sma(candles, i, EMA_SLOW_PERIOD)
    prior = _sma(candles, i - SLOPE_LOOKBACK, EMA_SLOW_PERIOD)
    atr = ind.atr[i]
    rsi = ind.rsi[i]
    hist = ind.macd_histogram[i]
    if any(v is None for v in [fast, slow, prior, atr, rsi, hist]):
        return None
    lookback_start = max(0, i - OVEREXTENDED_LOOKBACK)
    not_ext_long = max(x.high for x in candles[lookback_start:i+1]) - c.close <= OVEREXTENDED_MAX_POINTS
    not_ext_short = c.close - min(x.low for x in candles[lookback_start:i+1]) <= OVEREXTENDED_MAX_POINTS
    close_ratio = (c.close - c.low) / c.range if c.range > 0 else 0.5
    body_strength = c.body / c.range if c.range > 0 else 0.0
    top_wick = c.high - max(c.open, c.close)
    bot_wick = min(c.open, c.close) - c.low
    good_long_wick = top_wick <= max(WICK_MAX_POINTS, c.body * WICK_MAX_BODY_RATIO)
    good_short_wick = bot_wick <= max(WICK_MAX_POINTS, c.body * WICK_MAX_BODY_RATIO)

    long_core = fast > slow and slow > prior and fast - slow >= TREND_GAP_MIN and c.low <= fast + PULLBACK_MAX_DISTANCE and c.close > c.open and c.close > fast - FAST_MA_CLOSE_BUFFER and ATR_ENTRY_MIN <= atr <= ATR_ENTRY_MAX and not_ext_long
    short_core = fast < slow and slow < prior and slow - fast >= TREND_GAP_MIN and c.high >= fast - PULLBACK_MAX_DISTANCE and c.close < c.open and c.close < fast + FAST_MA_CLOSE_BUFFER and ATR_ENTRY_MIN <= atr <= ATR_ENTRY_MAX and not_ext_short

    if long_core:
        mtf_ok, mtf_notes = _passes_mtf("long", mtf_candles, mtf_indicators)
        if mtf_ok:
            score = 2
            notes = ["trend-aligned"]
            if fast - slow >= TREND_GAP_STRONG: score += 1; notes.append("trend-gap-strong")
            if body_strength >= BODY_STRENGTH_MIN: score += 1; notes.append("strong-body")
            if close_ratio >= CLOSE_IN_RANGE_LONG_MIN: score += 1; notes.append("strong-close")
            if hist >= MACD_LONG_MIN: score += 1; notes.append("macd-support")
            if RSI_LONG_MIN <= rsi <= RSI_LONG_MAX: score += 1; notes.append("rsi-support")
            if ATR_HEALTHY_MIN <= atr <= ATR_HEALTHY_MAX: score += 1; notes.append("atr-healthy")
            if not_ext_long: score += 1; notes.append("not-overextended")
            if good_long_wick: score += 1; notes.append("wick-clean")
            score += len(mtf_notes); notes.extend(mtf_notes)
            if c.body >= MIN_BODY_POINTS and c.range >= MIN_RANGE_POINTS and close_ratio >= LONG_GATE_CLOSE_RATIO_MIN and score >= MIN_ENTRY_SCORE and good_long_wick:
                return SignalCandle(c, i, "long", score, notes, c.high + CONFIRM_CANCEL_BUFFER_POINTS, c.low - CONFIRM_CANCEL_BUFFER_POINTS)

    if short_core:
        mtf_ok, mtf_notes = _passes_mtf("short", mtf_candles, mtf_indicators)
        if mtf_ok:
            score = 2
            notes = ["trend-aligned"]
            if slow - fast >= TREND_GAP_STRONG: score += 1; notes.append("trend-gap-strong")
            if body_strength >= BODY_STRENGTH_MIN: score += 1; notes.append("strong-body")
            if close_ratio <= CLOSE_IN_RANGE_SHORT_MAX: score += 1; notes.append("strong-close")
            if hist <= MACD_SHORT_MAX: score += 1; notes.append("macd-support")
            if RSI_SHORT_MIN <= rsi <= RSI_SHORT_MAX: score += 1; notes.append("rsi-support")
            if ATR_HEALTHY_MIN <= atr <= ATR_HEALTHY_MAX: score += 1; notes.append("atr-healthy")
            if not_ext_short: score += 1; notes.append("not-overextended")
            if good_short_wick: score += 1; notes.append("wick-clean")
            score += len(mtf_notes); notes.extend(mtf_notes)
            if c.body >= MIN_BODY_POINTS and c.range >= MIN_RANGE_POINTS and close_ratio <= SHORT_GATE_CLOSE_RATIO_MAX and score >= MIN_ENTRY_SCORE and good_short_wick:
                return SignalCandle(c, i, "short", score, notes, c.low - CONFIRM_CANCEL_BUFFER_POINTS, c.high + CONFIRM_CANCEL_BUFFER_POINTS)

    return None


def run_backtest_json(profile_id: str, candles_1m_payload, mtf_payload) -> str:
    candles_raw = json.loads(candles_1m_payload) if isinstance(candles_1m_payload, str) else candles_1m_payload
    mtf_raw = json.loads(mtf_payload) if isinstance(mtf_payload, str) else mtf_payload

    candles_1m = [Candle(int(x["time"]), float(x["open"]), float(x["high"]), float(x["low"]), float(x["close"])) for x in candles_raw]
    mtf_candles_all = {
        tf: [Candle(int(x["time"]), float(x["open"]), float(x["high"]), float(x["low"]), float(x["close"])) for x in arr]
        for tf, arr in (mtf_raw or {}).items()
    }
    ind_1m = build_indicators(candles_1m)
    mtf_inds_all = {tf: build_indicators(arr) for tf, arr in mtf_candles_all.items()}

    state = EngineState()
    total_won = 0.0
    total_lost = 0.0
    win_count = 0
    loss_count = 0

    def close_position(pos: Position, candle: Candle, exit_price: float):
        nonlocal total_won, total_lost, win_count, loss_count
        pnl_points = (exit_price - pos.entry_price) if pos.direction == "long" else (pos.entry_price - exit_price)
        pnl_money = pnl_points * VALUE_PER_POINT * pos.quantity
        fee = FEE_PER_ROUND_TRIP * pos.quantity
        net = pnl_money - fee
        state.balance += net
        state.peak_balance = max(state.peak_balance, state.balance)
        state.max_drawdown = max(state.max_drawdown, state.peak_balance - state.balance)
        if net > 0:
            win_count += 1
            total_won += net
        else:
            loss_count += 1
            total_lost += abs(net)
        state.trades.append(Trade(net))
        state.position = None

    for i, c in enumerate(candles_1m):
        aligned_mtf_c = {}
        aligned_mtf_i = {}
        for tf, arr in mtf_candles_all.items():
            idx = -1
            lo, hi = 0, len(arr) - 1
            while lo <= hi:
                mid = (lo + hi) // 2
                if arr[mid].time <= c.time:
                    idx = mid
                    lo = mid + 1
                else:
                    hi = mid - 1
            if idx >= 0:
                n = idx + 1
                aligned_mtf_c[tf] = arr[:n]
                fi = mtf_inds_all[tf]
                aligned_mtf_i[tf] = IndicatorSet(fi.ema_fast[:n], fi.ema_slow[:n], fi.ema_trend[:n], fi.atr[:n], fi.rsi[:n], fi.macd_line[:n], fi.macd_signal[:n], fi.macd_histogram[:n])

        pos = state.position
        if pos is not None:
            if pos.direction == "long":
                pos.best_price = max(pos.best_price, c.high)
                pos.worst_price = min(pos.worst_price, c.low)
            else:
                pos.best_price = min(pos.best_price, c.low)
                pos.worst_price = max(pos.worst_price, c.high)

            # target ladder
            for target_idx in range(pos.targets_hit, len(pos.target_prices)):
                t = pos.target_prices[target_idx]
                hit = (pos.direction == "long" and c.high >= t) or (pos.direction == "short" and c.low <= t)
                if not hit:
                    break
                if target_idx == 0:
                    new_stop = pos.entry_price + FEE_LOCK_POINTS if pos.direction == "long" else pos.entry_price - FEE_LOCK_POINTS
                else:
                    new_stop = pos.target_prices[target_idx - 1]
                pos.stop_loss = max(pos.stop_loss, new_stop) if pos.direction == "long" else min(pos.stop_loss, new_stop)
                pos.targets_hit = target_idx + 1

            # trailing
            if pos.targets_hit >= TRAILING_MIN_TARGETS_HIT:
                move = (max(pos.best_price, c.high) - pos.entry_price) if pos.direction == "long" else (pos.entry_price - min(pos.best_price, c.low))
                if move >= TRAILING_ACTIVATION_POINTS:
                    if move >= TRAILING_MOVE_LARGE_THRESHOLD:
                        dist = TRAILING_DISTANCE_LARGE
                    elif move >= TRAILING_MOVE_MEDIUM_THRESHOLD:
                        dist = TRAILING_DISTANCE_MEDIUM
                    else:
                        dist = TRAILING_DISTANCE_SMALL
                    cand_stop = (max(pos.best_price, c.high) - dist) if pos.direction == "long" else (min(pos.best_price, c.low) + dist)
                    if pos.trailing_stop is None:
                        pos.trailing_stop = cand_stop
                    elif pos.direction == "long":
                        pos.trailing_stop = max(pos.trailing_stop, cand_stop)
                    else:
                        pos.trailing_stop = min(pos.trailing_stop, cand_stop)

            # system exits
            closed = False
            if pos.direction == "long":
                if c.low <= pos.stop_loss:
                    close_position(pos, c, pos.stop_loss); closed = True
                elif c.high >= pos.take_profit:
                    close_position(pos, c, pos.take_profit); closed = True
                elif pos.trailing_stop is not None and c.low <= pos.trailing_stop:
                    close_position(pos, c, pos.trailing_stop); closed = True
            else:
                if c.high >= pos.stop_loss:
                    close_position(pos, c, pos.stop_loss); closed = True
                elif c.low <= pos.take_profit:
                    close_position(pos, c, pos.take_profit); closed = True
                elif pos.trailing_stop is not None and c.high >= pos.trailing_stop:
                    close_position(pos, c, pos.trailing_stop); closed = True

            if not closed:
                candles_in_trade = i - pos.entry_index
                close_ratio = (c.close - c.low) / c.range if c.range > 0 else 0.5
                do_exit = False
                if _long_protection(profile_id) and pos.direction == "long":
                    if candles_in_trade >= LONG_PROTECTION_EXIT_CANDLES_1:
                        progress = pos.best_price - pos.entry_price
                        failing = c.close < c.open and c.close < pos.entry_price and close_ratio < 0.3 and (c.range == 0 or c.body / c.range >= 0.5)
                        if progress < LONG_PROTECTION_PROGRESS_1 and failing:
                            do_exit = True
                    if not do_exit and candles_in_trade >= LONG_PROTECTION_EXIT_CANDLES_2:
                        progress = pos.best_price - pos.entry_price
                        failing = c.close < c.open and c.close < pos.entry_price and close_ratio < 0.4
                        if progress < LONG_PROTECTION_PROGRESS_2 and failing:
                            do_exit = True
                if not do_exit and candles_in_trade >= GENERAL_EXIT_CANDLES:
                    if pos.direction == "long":
                        progress = pos.best_price - pos.entry_price
                        if progress < GENERAL_EXIT_PROGRESS and c.close < c.open and c.close < pos.entry_price:
                            do_exit = True
                    else:
                        progress = pos.entry_price - pos.best_price
                        if progress < GENERAL_EXIT_PROGRESS and c.close > c.open and c.close > pos.entry_price:
                            do_exit = True
                if do_exit:
                    close_position(pos, c, c.close)
            continue

        if state.pending is not None:
            p = state.pending
            sig = p.signal
            if i <= sig.candle_index:
                pass
            elif i > sig.candle_index + PENDING_ENTRY_EXPIRY_CANDLES:
                state.pending = None
            else:
                confirmed = c.high >= sig.confirm_price if sig.direction == "long" else c.low <= sig.confirm_price
                canceled = c.low <= sig.cancel_price if sig.direction == "long" else c.high >= sig.cancel_price
                if canceled or (confirmed and canceled):
                    state.pending = None
                elif confirmed:
                    close_strength = (c.close - c.low) / (c.high - c.low) if c.high != c.low else 0.5
                    body = abs(c.close - c.open)
                    range_ = c.high - c.low
                    upper_wick = c.high - max(c.open, c.close)
                    lower_wick = min(c.open, c.close) - c.low
                    weak = (
                        (c.close <= c.open or c.close < sig.confirm_price or close_strength < 0.55 or (range_ > 0 and body / range_ < 0.25))
                        if sig.direction == "long"
                        else (c.close >= c.open or c.close > sig.confirm_price or close_strength > 0.45 or (range_ > 0 and body / range_ < 0.25))
                    )
                    adverse = sig.direction == "long" and (close_strength < 0.68 or upper_wick > max(18, body) or lower_wick < 4 or c.close - sig.confirm_price < 6)
                    if weak or adverse:
                        state.pending = None
                    else:
                        qty = _quantity(profile_id, sig.score)
                        if state.balance < CONTRACT_VALUE * qty:
                            state.pending = None
                            continue
                        if sig.direction == "long":
                            raw_stop = sig.candle.low - STOP_BUFFER_POINTS
                            stop = max(raw_stop, sig.confirm_price - MAX_LOSS_POINTS_PER_TRADE)
                        else:
                            raw_stop = sig.candle.high + STOP_BUFFER_POINTS
                            stop = min(raw_stop, sig.confirm_price + MAX_LOSS_POINTS_PER_TRADE)
                        targets = _build_targets(sig.confirm_price, stop, sig.direction)
                        state.position = Position(sig.direction, sig.confirm_price, c.time, i, qty, stop, targets[-1], abs(sig.confirm_price - stop), targets, setup_score=sig.score, setup_notes=sig.notes + ["confirmed-next-candle"])
                        state.pending = None
            continue

        if i >= MIN_WARMUP_CANDLES:
            sig = _evaluate_entry(candles_1m, ind_1m, i, aligned_mtf_c, aligned_mtf_i)
            if sig is not None and sig.score >= MIN_ENTRY_SCORE:
                state.pending = PendingEntry(sig, i)

    if state.position is not None:
        close_position(state.position, candles_1m[-1], candles_1m[-1].close)

    trade_count = len(state.trades)
    win_rate = (win_count / trade_count) if trade_count else 0.0
    net_profit = state.balance - STARTING_BALANCE
    profit_factor = (total_won / total_lost) if total_lost > 0 else None
    max_dd_pct = (state.max_drawdown / state.peak_balance) if state.peak_balance > 0 else 0.0

    return json.dumps({
        "trade_count": trade_count,
        "win_rate": win_rate,
        "net_profit": net_profit,
        "ending_balance": state.balance,
        "profit_factor": profit_factor,
        "max_drawdown_percent": max_dd_pct,
        "wins": win_count,
        "losses": loss_count,
    })

