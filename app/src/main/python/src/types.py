"""
Shared domain types for the trading bot.
All dataclasses are immutable where possible; mutable state lives in engine internals.
"""

from __future__ import annotations
from dataclasses import dataclass, field
from typing import Literal, Optional


# ---------------------------------------------------------------------------
# Market data
# ---------------------------------------------------------------------------

@dataclass
class Candle:
    time: int           # Unix timestamp (seconds)
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

    @property
    def body_ratio(self) -> float:
        return self.body / self.range if self.range > 0 else 0.0

    @property
    def close_ratio(self) -> float:
        """close position from low as a fraction of total range"""
        return (self.close - self.low) / self.range if self.range > 0 else 0.5

    @property
    def is_bullish(self) -> bool:
        return self.close >= self.open

    @property
    def upper_wick(self) -> float:
        return self.high - max(self.open, self.close)

    @property
    def lower_wick(self) -> float:
        return min(self.open, self.close) - self.low


Direction = Literal["long", "short"]
Timeframe = Literal["1m", "5m", "15m", "30m", "60m"]


# ---------------------------------------------------------------------------
# Indicators
# ---------------------------------------------------------------------------

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


# ---------------------------------------------------------------------------
# Strategy signal / pending entry
# ---------------------------------------------------------------------------

@dataclass
class SignalCandle:
    candle: Candle
    candle_index: int
    direction: Direction
    score: int
    notes: list[str]
    confirm_price: float    # price level that activates entry
    cancel_price: float     # price level that invalidates signal


@dataclass
class PendingEntry:
    signal: SignalCandle
    created_at_index: int


# ---------------------------------------------------------------------------
# Position lifecycle
# ---------------------------------------------------------------------------

@dataclass
class Position:
    direction: Direction
    entry_price: float
    entry_time: int
    entry_index: int
    quantity: int
    stop_loss: float
    take_profit: float
    initial_risk: float         # entry_price to original stop_loss in points
    target_prices: list[float]
    targets_hit: int = 0
    trailing_stop: Optional[float] = None
    best_price: float = 0.0     # MFE price
    worst_price: float = 0.0    # MAE price
    setup_score: int = 0
    setup_notes: list[str] = field(default_factory=list)

    def __post_init__(self):
        self.best_price = self.entry_price
        self.worst_price = self.entry_price


# ---------------------------------------------------------------------------
# Closed trade record
# ---------------------------------------------------------------------------

ExitReason = Literal["stopLoss", "takeProfit", "trailingStop", "strategyExit", "endOfData"]


@dataclass
class Trade:
    strategy_id: str
    direction: Direction
    quantity: int
    entry_time: int
    exit_time: int
    entry_price: float
    exit_price: float
    stop_loss: float
    take_profit: float
    trailing_stop: Optional[float]
    pnl_price_points: float
    pnl_money: float
    exit_reason: ExitReason
    setup_score: int
    setup_notes: list[str]
    target_prices: list[float]
    targets_hit: int
    holding_candles: int
    max_favorable_excursion: float   # MFE in points
    max_adverse_excursion: float     # MAE in points
    fee_money: float
    net_pnl_money: float
    balance_before: float
    balance_after: float
    drawdown_after_trade: float
    exit_day: str                    # YYYY-MM-DD


# ---------------------------------------------------------------------------
# Backtest summary
# ---------------------------------------------------------------------------

@dataclass
class StrategySummary:
    strategy_id: str
    strategy_name: str
    initial_balance: float
    ending_balance: float
    net_profit: float
    withdrawn_profit: float
    total_fees: float
    trade_count: int
    win_count: int
    loss_count: int
    total_won: float
    total_lost: float
    win_rate: float
    profit_factor: Optional[float]
    max_drawdown: float
    max_drawdown_percent: float
    average_win: float
    average_loss: float


@dataclass
class BacktestSummary:
    engine_name: str
    assumptions: dict
    summary: StrategySummary
    per_strategy: list[StrategySummary]
    trades: list[Trade]


# ---------------------------------------------------------------------------
# Live signal response
# ---------------------------------------------------------------------------

SignalType = Literal["signal", "position_update", "close_signal", "no_signal"]


@dataclass
class LiveSignalResponse:
    type: SignalType
    direction: Optional[Direction] = None
    entry_price: Optional[float] = None
    stop_loss: Optional[float] = None
    take_profit: Optional[float] = None
    quantity: Optional[int] = None
    target_prices: Optional[list[float]] = None
    setup_score: Optional[int] = None
    setup_notes: Optional[list[str]] = None
    exit_reason: Optional[ExitReason] = None
    exit_price: Optional[float] = None
    pnl_price_points: Optional[float] = None
    candle_time: Optional[int] = None
    entry_time: Optional[int] = None


# ---------------------------------------------------------------------------
# Strategy profile
# ---------------------------------------------------------------------------

SizingMode = Literal["fixed", "scaled"]


@dataclass
class StrategyProfile:
    profile_id: str
    sizing_mode: SizingMode
    base_quantity: int
    long_protection: bool   # extra early-exit logic for longs
    min_score_to_trade: int
