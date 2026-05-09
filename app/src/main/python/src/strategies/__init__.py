"""
Strategy layer - signal generation, profile definitions.

Rules:
- No API calls or network I/O here.
- Strategy receives candles + indicators and returns signals.
- Engine handles position lifecycle; strategy only generates entry/exit signals.
"""

from __future__ import annotations
from abc import ABC, abstractmethod
from typing import Optional
from src.types import (
    Candle, Direction, IndicatorSet, Position,
    SignalCandle, StrategyProfile,
)
import src.config as cfg


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _sma(candles: list[Candle], end_index: int, period: int) -> Optional[float]:
    """Simple moving average of closes ending at end_index (inclusive). (A4)"""
    start = end_index - period + 1
    if start < 0:
        return None
    total = sum(candles[i].close for i in range(start, end_index + 1))
    return total / period


def _max_high(candles: list[Candle], start: int, end: int) -> float:
    return max(candles[i].high for i in range(start, end + 1))


def _min_low(candles: list[Candle], start: int, end: int) -> float:
    return min(candles[i].low for i in range(start, end + 1))


def _close_in_range(candle: Candle) -> float:
    r = candle.range
    return (candle.close - candle.low) / r if r > 0 else 0.5


def _upper_wick(candle: Candle) -> float:
    return candle.high - max(candle.open, candle.close)


def _lower_wick(candle: Candle) -> float:
    return min(candle.open, candle.close) - candle.low


# ---------------------------------------------------------------------------
# Strategy interface
# ---------------------------------------------------------------------------

class StrategyInterface(ABC):
    @abstractmethod
    def evaluate_entry(
        self,
        candles: list[Candle],
        indicators: IndicatorSet,
        index: int,
        mtf_candles: dict[str, list[Candle]],
        mtf_indicators: dict[str, IndicatorSet],
    ) -> Optional[SignalCandle]:
        """Return a SignalCandle if entry conditions are met, else None."""

    @abstractmethod
    def evaluate_exit(
        self,
        position: Position,
        candle: Candle,
        indicators: IndicatorSet,
        index: int,
    ) -> bool:
        """Return True if strategy-specific conditions warrant closing the position."""

    @abstractmethod
    def get_quantity(self, profile: StrategyProfile, score: int) -> int:
        """Return position size given profile and setup score."""


# ---------------------------------------------------------------------------
# MTF bias helpers (B3)
# ---------------------------------------------------------------------------

def _get_htf_bias(
    candles: list[Candle],
    indicators: IndicatorSet,
) -> Optional[str]:
    """
    Returns 'bullish', 'bearish', or None for a higher-timeframe candle set.
    Matches TS isBullishHigherTimeframe / isBearishHigherTimeframe. (B3)
    """
    if not candles:
        return None
    idx = len(candles) - 1
    ef = indicators.ema_fast[idx]
    es = indicators.ema_slow[idx]
    et = indicators.ema_trend[idx]
    rsi_val = indicators.rsi[idx]
    hist = indicators.macd_histogram[idx]

    if any(v is None for v in [ef, es, et, rsi_val, hist]):
        return None

    ef, es, et = float(ef), float(es), float(et)  # type: ignore[arg-type]
    rsi_val = float(rsi_val)  # type: ignore[arg-type]
    hist = float(hist)  # type: ignore[arg-type]
    close = candles[idx].close

    bullish = (
        close >= ef
        and ef > es
        and es >= et - cfg.MTF_EMA_TOLERANCE
        and rsi_val >= cfg.MTF_RSI_BULLISH_MIN
        and hist >= cfg.MTF_MACD_HIST_BULLISH_MIN
    )
    if bullish:
        return "bullish"

    bearish = (
        close <= ef
        and ef < es
        and es <= et + cfg.MTF_EMA_TOLERANCE
        and rsi_val <= cfg.MTF_RSI_BEARISH_MAX
        and hist <= cfg.MTF_MACD_HIST_BEARISH_MAX
    )
    if bearish:
        return "bearish"

    return None


def _passes_mtf_filter(
    direction: Direction,
    mtf_candles: dict[str, list[Candle]],
    mtf_indicators: dict[str, IndicatorSet],
) -> tuple[bool, list[str]]:
    """
    Returns (passed, notes).
    Passes if: at least one of 5m/15m is aligned, OR at least 2 total aligned. (B3)
    """
    if getattr(cfg, "DEMO_MTF_ALWAYS_PASS", False):
        return True, []
    order = ["5m", "15m", "30m", "60m"]
    notes: list[str] = []
    aligned = 0
    aligned_fast_context = 0
    considered = 0

    for tf in order:
        candles = mtf_candles.get(tf)
        indicators = mtf_indicators.get(tf)
        if not candles or not indicators:
            continue
        bias = _get_htf_bias(candles, indicators)
        if bias is None:
            continue
        considered += 1
        matches = (direction == "long" and bias == "bullish") or \
                  (direction == "short" and bias == "bearish")
        if matches:
            aligned += 1
            notes.append(f"mtf-{tf}-aligned")
            if tf in ("5m", "15m"):
                aligned_fast_context += 1

    if considered < 1:
        return False, notes

    if cfg.MTF_RELAX_SINGLE_ALIGNED:
        passed = aligned >= 1
    else:
        passed = aligned_fast_context >= 1 or aligned >= 2
    return passed, notes


# ---------------------------------------------------------------------------
# Pullback trend strategy (A4, B1, B2, B3, B4, B6, B7, B8)
# ---------------------------------------------------------------------------

class PullbackTrendStrategy(StrategyInterface):
    """
    Trend-following pullback strategy matching the TypeScript original.

    Entry uses SMA (not EMA) for trend checks. (A4)
    Entry requires price to have pulled back near the fast SMA. (B2)
    MTF gate uses RSI + MACD histogram bias, not pure EMA alignment. (B3)
    MACD support is permissive (histogram >= -40, not strictly > 0). (B4)
    Strategy exit is time + progress based, not MACD flip. (B6)
    Trailing stop uses fixed point distances, not ratios. (B7)
    """

    STRATEGY_ID = "pullback-trend"
    STRATEGY_NAME = "Long/Short Pullback Trend"

    def __init__(self, long_protection: bool = False) -> None:
        self.long_protection = long_protection

    def evaluate_entry(
        self,
        candles: list[Candle],
        indicators: IndicatorSet,
        index: int,
        mtf_candles: dict[str, list[Candle]],
        mtf_indicators: dict[str, IndicatorSet],
    ) -> Optional[SignalCandle]:
        if index < cfg.MIN_WARMUP_CANDLES or index < 1:
            return None

        candle = candles[index]
        previous = candles[index - 1]

        # SMA-based trend (A4)
        fast_ma = _sma(candles, index, cfg.EMA_FAST_PERIOD)
        slow_ma = _sma(candles, index, cfg.EMA_SLOW_PERIOD)
        prior_slow_ma = _sma(candles, index - cfg.SLOPE_LOOKBACK, cfg.EMA_SLOW_PERIOD)

        atr_val = indicators.atr[index]
        hist = indicators.macd_histogram[index]
        rsi_val = indicators.rsi[index]

        if any(v is None for v in [fast_ma, slow_ma, prior_slow_ma, atr_val, hist, rsi_val]):
            return None

        fast_ma = float(fast_ma)  # type: ignore[arg-type]
        slow_ma = float(slow_ma)  # type: ignore[arg-type]
        prior_slow_ma = float(prior_slow_ma)  # type: ignore[arg-type]
        atr_val = float(atr_val)  # type: ignore[arg-type]
        hist = float(hist)  # type: ignore[arg-type]
        rsi_val = float(rsi_val)  # type: ignore[arg-type]

        # Shared pre-checks
        lookback_start = max(0, index - cfg.OVEREXTENDED_LOOKBACK)
        valid_atr = cfg.ATR_ENTRY_MIN <= atr_val <= cfg.ATR_ENTRY_MAX
        not_overextended_long = (
            _max_high(candles, lookback_start, index) - candle.close <= cfg.OVEREXTENDED_MAX_POINTS
        )
        not_overextended_short = (
            candle.close - _min_low(candles, lookback_start, index) <= cfg.OVEREXTENDED_MAX_POINTS
        )
        macd_supports_long = hist >= cfg.MACD_LONG_MIN   # permissive (B4)
        macd_supports_short = hist <= cfg.MACD_SHORT_MAX  # permissive (B4)
        rsi_supports_long = cfg.RSI_LONG_MIN <= rsi_val <= cfg.RSI_LONG_MAX
        rsi_supports_short = cfg.RSI_SHORT_MIN <= rsi_val <= cfg.RSI_SHORT_MAX

        candle_body = candle.body
        range_ = candle.range
        close_ratio = _close_in_range(candle)
        body_strength = candle_body / range_ if range_ > 0 else 0.0
        top_wick = _upper_wick(candle)
        bottom_wick = _lower_wick(candle)
        good_long_wick = top_wick <= max(cfg.WICK_MAX_POINTS, candle_body * cfg.WICK_MAX_BODY_RATIO)
        good_short_wick = bottom_wick <= max(cfg.WICK_MAX_POINTS, candle_body * cfg.WICK_MAX_BODY_RATIO)

        # ---- LONG ----
        long_core = (
            fast_ma > slow_ma
            and slow_ma > prior_slow_ma           # slope up (A4)
            and fast_ma - slow_ma >= cfg.TREND_GAP_MIN
            and candle.low <= fast_ma + cfg.PULLBACK_MAX_DISTANCE   # pullback condition (B2)
            and candle.close > candle.open         # bullish candle
            and candle.close > fast_ma - cfg.FAST_MA_CLOSE_BUFFER
            and valid_atr
            and not_overextended_long
        )

        if long_core:
            mtf_passed, mtf_notes = _passes_mtf_filter("long", mtf_candles, mtf_indicators)
            if mtf_passed:
                score = 0
                notes: list[str] = []

                score += 2
                notes.append("trend-aligned")

                if fast_ma - slow_ma >= cfg.TREND_GAP_STRONG:
                    score += 1
                    notes.append("trend-gap-strong")

                if body_strength >= cfg.BODY_STRENGTH_MIN:
                    score += 1
                    notes.append("strong-body")

                if close_ratio >= cfg.CLOSE_IN_RANGE_LONG_MIN:
                    score += 1
                    notes.append("strong-close")

                if macd_supports_long:
                    score += 1
                    notes.append("macd-support")

                if rsi_supports_long:
                    score += 1
                    notes.append("rsi-support")

                if cfg.ATR_HEALTHY_MIN <= atr_val <= cfg.ATR_HEALTHY_MAX:
                    score += 1
                    notes.append("atr-healthy")

                if not_overextended_long:
                    score += 1
                    notes.append("not-overextended")

                if good_long_wick:
                    score += 1
                    notes.append("wick-clean")

                score += len(mtf_notes)
                notes.extend(mtf_notes)

                # Hard gate before emitting signal (B1)
                if (
                    candle_body >= cfg.MIN_BODY_POINTS
                    and range_ >= cfg.MIN_RANGE_POINTS
                    and close_ratio >= cfg.LONG_GATE_CLOSE_RATIO_MIN
                    and score >= cfg.MIN_ENTRY_SCORE
                    and good_long_wick
                ):
                    return SignalCandle(
                        candle=candle,
                        candle_index=index,
                        direction="long",
                        score=score,
                        notes=notes,
                        confirm_price=candle.high + cfg.CONFIRM_CANCEL_BUFFER_POINTS,
                        cancel_price=candle.low - cfg.CONFIRM_CANCEL_BUFFER_POINTS,
                    )

        # ---- SHORT ----
        short_core = (
            fast_ma < slow_ma
            and slow_ma < prior_slow_ma            # slope down (A4)
            and slow_ma - fast_ma >= cfg.TREND_GAP_MIN
            and candle.high >= fast_ma - cfg.PULLBACK_MAX_DISTANCE  # pullback condition (B2)
            and candle.close < candle.open          # bearish candle
            and candle.close < fast_ma + cfg.FAST_MA_CLOSE_BUFFER
            and valid_atr
            and not_overextended_short
        )

        if short_core:
            mtf_passed, mtf_notes = _passes_mtf_filter("short", mtf_candles, mtf_indicators)
            if mtf_passed:
                score = 0
                notes = []

                score += 2
                notes.append("trend-aligned")

                if slow_ma - fast_ma >= cfg.TREND_GAP_STRONG:
                    score += 1
                    notes.append("trend-gap-strong")

                if body_strength >= cfg.BODY_STRENGTH_MIN:
                    score += 1
                    notes.append("strong-body")

                if close_ratio <= cfg.CLOSE_IN_RANGE_SHORT_MAX:
                    score += 1
                    notes.append("strong-close")

                if macd_supports_short:
                    score += 1
                    notes.append("macd-support")

                if rsi_supports_short:
                    score += 1
                    notes.append("rsi-support")

                if cfg.ATR_HEALTHY_MIN <= atr_val <= cfg.ATR_HEALTHY_MAX:
                    score += 1
                    notes.append("atr-healthy")

                if not_overextended_short:
                    score += 1
                    notes.append("not-overextended")

                if good_short_wick:
                    score += 1
                    notes.append("wick-clean")

                score += len(mtf_notes)
                notes.extend(mtf_notes)

                # Hard gate before emitting signal (B1)
                if (
                    candle_body >= cfg.MIN_BODY_POINTS
                    and range_ >= cfg.MIN_RANGE_POINTS
                    and close_ratio <= cfg.SHORT_GATE_CLOSE_RATIO_MAX
                    and score >= cfg.MIN_ENTRY_SCORE
                    and good_short_wick
                ):
                    return SignalCandle(
                        candle=candle,
                        candle_index=index,
                        direction="short",
                        score=score,
                        notes=notes,
                        confirm_price=candle.low - cfg.CONFIRM_CANCEL_BUFFER_POINTS,
                        cancel_price=candle.high + cfg.CONFIRM_CANCEL_BUFFER_POINTS,
                    )

        return None

    def evaluate_exit(
        self,
        position: Position,
        candle: Candle,
        indicators: IndicatorSet,
        index: int,
    ) -> bool:
        """
        Time-based strategy exit. (B6)
        Closes stale trades that haven't made progress and are showing reversal.
        """
        candles_in_trade = index - position.entry_index
        close_ratio = _close_in_range(candle)

        # Long-protection exits (B8: only when long_protection=True)
        if self.long_protection and position.direction == "long":
            if candles_in_trade >= cfg.LONG_PROTECTION_EXIT_CANDLES_1:
                progress = position.best_price - position.entry_price
                body = candle.body
                range_ = candle.range
                failing = (
                    candle.close < candle.open
                    and candle.close < position.entry_price
                    and close_ratio < 0.3
                    and (range_ == 0 or body / range_ >= 0.5)
                )
                if progress < cfg.LONG_PROTECTION_PROGRESS_1 and failing:
                    return True

            if candles_in_trade >= cfg.LONG_PROTECTION_EXIT_CANDLES_2:
                progress = position.best_price - position.entry_price
                failing = (
                    candle.close < candle.open
                    and candle.close < position.entry_price
                    and close_ratio < 0.4
                )
                if progress < cfg.LONG_PROTECTION_PROGRESS_2 and failing:
                    return True

        # General stale-trade exit (all positions)
        if candles_in_trade < cfg.GENERAL_EXIT_CANDLES:
            return False

        if position.direction == "long":
            progress = position.best_price - position.entry_price
            failing = (
                candle.close < candle.open
                and candle.close < position.entry_price
            )
            if progress < cfg.GENERAL_EXIT_PROGRESS and failing:
                return True
        else:
            progress = position.entry_price - position.best_price
            failing = (
                candle.close > candle.open
                and candle.close > position.entry_price
            )
            if progress < cfg.GENERAL_EXIT_PROGRESS and failing:
                return True

        return False

    def get_quantity(self, profile: StrategyProfile, score: int) -> int:
        if profile.sizing_mode == "fixed":
            return profile.base_quantity
        # scaled: TS thresholds (B8)
        if score >= 12:
            return 3
        if score >= 10:
            return 2
        return profile.base_quantity


# ---------------------------------------------------------------------------
# Profile definitions
# ---------------------------------------------------------------------------

PROFILES: dict[str, StrategyProfile] = {
    "baseline": StrategyProfile(
        profile_id="baseline",
        sizing_mode="fixed",
        base_quantity=1,
        long_protection=False,
        min_score_to_trade=cfg.MIN_ENTRY_SCORE,
    ),
    "long_protection": StrategyProfile(
        profile_id="long_protection",
        sizing_mode="fixed",
        base_quantity=1,
        long_protection=True,
        min_score_to_trade=cfg.MIN_ENTRY_SCORE,
    ),
    "scaled_units": StrategyProfile(
        profile_id="scaled_units",
        sizing_mode="scaled",
        base_quantity=1,
        long_protection=True,   # (B8: was False)
        min_score_to_trade=cfg.MIN_ENTRY_SCORE,
    ),
}


def get_profile(profile_id: str) -> StrategyProfile:
    if profile_id not in PROFILES:
        raise ValueError(f"Unknown profile: {profile_id!r}. Available: {list(PROFILES)}")
    return PROFILES[profile_id]
