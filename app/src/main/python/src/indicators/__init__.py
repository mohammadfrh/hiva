"""
Indicator calculations - pure functions, no side effects.

All series are aligned to candle index. Values are None during warmup
(not enough history to compute). Strategy must skip candles where any
required indicator is None.
"""

from __future__ import annotations
from typing import Optional
from src.types import Candle, IndicatorSet
import src.config as cfg


# ---------------------------------------------------------------------------
# Primitive helpers
# ---------------------------------------------------------------------------

def _ema_standard(values: list[float], period: int) -> list[Optional[float]]:
    """
    Standard EMA (alpha = 2/(period+1)), SMA-seeded.
    Used for EMA fast/slow/trend and MACD lines.
    """
    result: list[Optional[float]] = [None] * len(values)
    if len(values) < period:
        return result

    k = 2.0 / (period + 1)
    sma = sum(values[:period]) / period
    result[period - 1] = sma
    prev = sma

    for i in range(period, len(values)):
        current = values[i] * k + prev * (1.0 - k)
        result[i] = current
        prev = current

    return result


def _ema_wilder(values: list[float], period: int) -> list[Optional[float]]:
    """
    Wilder's smoothed moving average (alpha = 1/period), SMA-seeded.
    Used for ATR and RSI - matches the original Wilder definition. (A5)
    """
    result: list[Optional[float]] = [None] * len(values)
    if len(values) < period:
        return result

    sma = sum(values[:period]) / period
    result[period - 1] = sma
    prev = sma

    for i in range(period, len(values)):
        current = (prev * (period - 1) + values[i]) / period
        result[i] = current
        prev = current

    return result


# ---------------------------------------------------------------------------
# Public indicator functions
# ---------------------------------------------------------------------------

def ema(values: list[Optional[float]], period: int) -> list[Optional[float]]:
    """Standard EMA over a series that may contain leading Nones."""
    start = next((i for i, v in enumerate(values) if v is not None), None)
    if start is None:
        return [None] * len(values)

    clean = [v for v in values[start:] if v is not None]
    raw = _ema_standard(clean, period)

    result: list[Optional[float]] = [None] * len(values)
    j = 0
    for i in range(start, len(values)):
        if values[i] is not None:
            result[i] = raw[j]
            j += 1
    return result


def atr(candles: list[Candle], period: int) -> list[Optional[float]]:
    """
    Average True Range using Wilder's smoothing (correct ATR definition). (A5, C2)
    - index 0: true range = high - low (no previous close available)
    - index 1+: true range = max(H-L, |H-prevC|, |L-prevC|)
    """
    if len(candles) < period:
        return [None] * len(candles)

    # C2: include candle[0] using simple range (no prev close)
    tr_values: list[float] = []
    for i in range(len(candles)):
        c = candles[i]
        if i == 0:
            tr = c.high - c.low
        else:
            prev_close = candles[i - 1].close
            tr = max(
                c.high - c.low,
                abs(c.high - prev_close),
                abs(c.low - prev_close),
            )
        tr_values.append(tr)

    # A5: use Wilder's smoothing
    raw = _ema_wilder(tr_values, period)
    return raw


def rsi(values: list[Optional[float]], period: int) -> list[Optional[float]]:
    """
    RSI using Wilder's smoothing (alpha = 1/period).
    Seed uses changes from index 1 to period (inclusive), matching TS.
    """
    result: list[Optional[float]] = [None] * len(values)
    clean_indices = [i for i, v in enumerate(values) if v is not None]
    if len(clean_indices) <= period:
        return result

    clean = [values[i] for i in clean_indices]

    # seed using changes[1..period] (TS starts at index 1)
    gain_sum = 0.0
    loss_sum = 0.0
    for i in range(1, period + 1):
        delta = clean[i] - clean[i - 1]  # type: ignore[operator]
        if delta >= 0:
            gain_sum += delta
        else:
            loss_sum += abs(delta)

    avg_gain = gain_sum / period
    avg_loss = loss_sum / period

    def _rsi_val(ag: float, al: float) -> float:
        return 100.0 if al == 0 else 100.0 - 100.0 / (1.0 + ag / al)

    result[clean_indices[period]] = _rsi_val(avg_gain, avg_loss)

    for i in range(period + 1, len(clean)):
        delta = clean[i] - clean[i - 1]  # type: ignore[operator]
        gain = max(delta, 0.0)
        loss = max(-delta, 0.0)
        avg_gain = (avg_gain * (period - 1) + gain) / period
        avg_loss = (avg_loss * (period - 1) + loss) / period
        result[clean_indices[i]] = _rsi_val(avg_gain, avg_loss)

    return result


def macd(
    values: list[Optional[float]],
    fast_period: int,
    slow_period: int,
    signal_period: int,
) -> tuple[list[Optional[float]], list[Optional[float]], list[Optional[float]]]:
    """
    Returns (macd_line, signal_line, histogram).
    C3: signal EMA is seeded with zeros for None positions (matching TS behavior).
    """
    fast = ema(values, fast_period)
    slow = ema(values, slow_period)

    line: list[Optional[float]] = []
    for f, s in zip(fast, slow):
        if f is not None and s is not None:
            line.append(f - s)
        else:
            line.append(None)

    # C3: seed with 0 for None positions before computing signal EMA
    seeded = [v if v is not None else 0.0 for v in line]
    raw_signal = _ema_standard(seeded, signal_period)
    sig: list[Optional[float]] = [v for v in raw_signal]

    hist: list[Optional[float]] = []
    for l, s in zip(line, sig):
        if l is not None and s is not None:
            hist.append(l - s)
        else:
            hist.append(None)

    return line, sig, hist


# ---------------------------------------------------------------------------
# Assembled indicator set
# ---------------------------------------------------------------------------

def build_indicators(candles: list[Candle]) -> IndicatorSet:
    """
    Build the full indicator set for a candle series.
    All output series are aligned to candle index.
    """
    closes: list[Optional[float]] = [c.close for c in candles]

    ema_fast = ema(closes, cfg.EMA_FAST_PERIOD)
    ema_slow = ema(closes, cfg.EMA_SLOW_PERIOD)
    ema_trend = ema(closes, cfg.EMA_TREND_PERIOD)
    atr_series = atr(candles, cfg.ATR_PERIOD)
    rsi_series = rsi(closes, cfg.RSI_PERIOD)
    macd_line, macd_sig, macd_hist = macd(
        closes,
        cfg.MACD_FAST_PERIOD,
        cfg.MACD_SLOW_PERIOD,
        cfg.MACD_SIGNAL_PERIOD,
    )

    return IndicatorSet(
        ema_fast=ema_fast,
        ema_slow=ema_slow,
        ema_trend=ema_trend,
        atr=atr_series,
        rsi=rsi_series,
        macd_line=macd_line,
        macd_signal=macd_sig,
        macd_histogram=macd_hist,
    )
