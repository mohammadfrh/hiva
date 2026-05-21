"""
Runtime constants for the trading bot.
All numeric thresholds and domain parameters live here.

Values marked [TUNE] are calibration points - correct for the original TS strategy
but may be adjusted after seeing live/backtest results.
"""

# --- Contract model ---
CONTRACT_VALUE = 4_600_000          # cost of one contract in base currency
VALUE_PER_POINT = 23_000            # profit/loss in base currency per price point
FEE_PER_SIDE = 14_000               # brokerage fee per side (entry or exit)
FEE_PER_ROUND_TRIP = FEE_PER_SIDE * 2

# --- Capital model ---
DEPLOYABLE_CAPITAL_UNITS = 3
STARTING_BALANCE = 13_800_000   # 3 * 4_600_000
DEPLOYABLE_CAPITAL_CAP = STARTING_BALANCE
WITHDRAW_PROFITS_DAILY = True

# --- Risk controls ---
MAX_LOSS_POINTS_PER_TRADE = 70      # [TUNE] hard stop cap from entry price (A1: was 200)

# --- Entry signal buffers ---
STOP_BUFFER_POINTS = 8              # stop loss placed this far beyond signal candle extreme
CONFIRM_CANCEL_BUFFER_POINTS = 4    # confirm/cancel levels this far beyond signal high/low (A: was 8 for both)

# --- Indicator periods ---
EMA_FAST_PERIOD = 12                # (A3: was 9)
EMA_SLOW_PERIOD = 20                # (A3: was 21)
EMA_TREND_PERIOD = 50

ATR_PERIOD = 14

RSI_PERIOD = 14
MACD_FAST_PERIOD = 12
MACD_SLOW_PERIOD = 26
MACD_SIGNAL_PERIOD = 9

# --- ATR validity gate (hard filter before any entry check) ---
ATR_ENTRY_MIN = 14                  # [TUNE] below this: too quiet to trade
ATR_ENTRY_MAX = 280                 # [TUNE] above this: too volatile to trade
#ATR_ENTRY_MIN = 0                   # [TUNE] below this: too quiet to trade
#ATR_ENTRY_MAX = 9999                # [TUNE] above this: too volatile to trade

# --- ATR healthy scoring band (adds "atr-healthy" to score) ---
ATR_HEALTHY_MIN = 22                # [TUNE]
ATR_HEALTHY_MAX = 210               # [TUNE]

# --- Trend / SMA check ---
TREND_GAP_MIN = 3                   # fast SMA must be at least this far from slow SMA
TREND_GAP_STRONG = 14               # [TUNE] gap this large earns "trend-gap-strong" bonus
SLOPE_LOOKBACK = 8                  # bars back to compare slow SMA for slope check
PULLBACK_MAX_DISTANCE = 10          # [TUNE] candle low must be within this pts of fast SMA (long)
FAST_MA_CLOSE_BUFFER = 2            # close must be above (fast SMA - this) for long

# --- Overextension check ---
OVEREXTENDED_LOOKBACK = 10          # rolling window for max-high / min-low check
OVEREXTENDED_MAX_POINTS = 110       # [TUNE] max distance from rolling extreme to close

# --- Candle quality thresholds (scoring, not hard gates) ---
BODY_STRENGTH_MIN = 0.32            # [TUNE] body/range >= this -> "strong-body"
CLOSE_IN_RANGE_LONG_MIN = 0.58      # [TUNE] close_ratio >= this -> "strong-close" for long
CLOSE_IN_RANGE_SHORT_MAX = 0.42     # [TUNE] close_ratio <= this -> "strong-close" for short

# --- Demo: looser gates for testing on demo (set DEMO_LOOSER_GATES = False for production) ---
DEMO_LOOSER_GATES = True

# --- Candle size + entry gates (overrides below when DEMO_LOOSER_GATES is True) ---
if DEMO_LOOSER_GATES:
    # Very permissive: more signals for QA (not for real money)
    MIN_BODY_POINTS = 8
    MIN_RANGE_POINTS = 10
    LONG_GATE_CLOSE_RATIO_MIN = 0.46
    SHORT_GATE_CLOSE_RATIO_MAX = 0.54
    MIN_ENTRY_SCORE = 2
    MTF_RELAX_SINGLE_ALIGNED = True
    DEMO_MTF_ALWAYS_PASS = True
    ATR_ENTRY_MIN = 5
    ATR_ENTRY_MAX = 500
    TREND_GAP_MIN = 1
    PULLBACK_MAX_DISTANCE = 28
    OVEREXTENDED_MAX_POINTS = 320
    WICK_MAX_POINTS = 28
else:
    MIN_BODY_POINTS = 18
    MIN_RANGE_POINTS = 24
    LONG_GATE_CLOSE_RATIO_MIN = 0.56
    SHORT_GATE_CLOSE_RATIO_MAX = 0.44
    MIN_ENTRY_SCORE = 6
    MTF_RELAX_SINGLE_ALIGNED = False
    DEMO_MTF_ALWAYS_PASS = False
    ATR_ENTRY_MIN = 14
    ATR_ENTRY_MAX = 280
    TREND_GAP_MIN = 3
    PULLBACK_MAX_DISTANCE = 10
    OVEREXTENDED_MAX_POINTS = 110
    WICK_MAX_POINTS = 18

# --- Wick quality (WICK_MAX_POINTS set in DEMO block above when DEMO_LOOSER_GATES) ---
WICK_MAX_BODY_RATIO = 1.05          # [TUNE]

# --- RSI support ranges (scoring) ---
RSI_LONG_MIN = 38                   # [TUNE] (was 70 max only)
RSI_LONG_MAX = 80                   # [TUNE]
RSI_SHORT_MIN = 20                  # [TUNE] (was 30 min only)
RSI_SHORT_MAX = 62                  # [TUNE]

# --- MACD histogram support (scoring, permissive) ---
MACD_LONG_MIN = -40                 # [TUNE] histogram >= this -> "macd-support" for long (B4: was > 0)
MACD_SHORT_MAX = 40                 # [TUNE] histogram <= this -> "macd-support" for short (B4: was < 0)

# --- Pending entry ---
PENDING_ENTRY_EXPIRY_CANDLES = 1    # (A6: was 3) only try to confirm on the very next candle

# --- Target ladder (A2: was [0.125..1.0] x 8, giving 8x risk max) ---
TARGET_RISK_MULTIPLIERS = [0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0]  # up to 2x risk
FEE_LOCK_POINTS = 2                 # stop moves to entry + this after TP1

# --- Trailing stop (B7: was ratio-based) ---
TRAILING_MIN_TARGETS_HIT = 2        # trailing activates only after this many targets hit
TRAILING_ACTIVATION_POINTS = 110    # [TUNE] favorable move (pts) before trailing starts
TRAILING_DISTANCE_SMALL = 55        # [TUNE] trail distance when move < MEDIUM threshold
TRAILING_DISTANCE_MEDIUM = 75       # [TUNE] trail distance when move < LARGE threshold
TRAILING_DISTANCE_LARGE = 95        # [TUNE] trail distance when move >= LARGE threshold
TRAILING_MOVE_MEDIUM_THRESHOLD = 200  # [TUNE]
TRAILING_MOVE_LARGE_THRESHOLD = 300   # [TUNE]

# --- Multi-timeframe ---
MTF_TIMEFRAMES = ["5m", "15m", "30m", "60m"]
MTF_EMA_TOLERANCE = 6               # [TUNE] slow SMA allowed to be this far from trend EMA
MTF_RSI_BULLISH_MIN = 48            # [TUNE] RSI must be >= this for bullish HTF bias (B3)
MTF_RSI_BEARISH_MAX = 52            # [TUNE] RSI must be <= this for bearish HTF bias (B3)
MTF_MACD_HIST_BULLISH_MIN = -10     # [TUNE] MACD histogram >= this for bullish HTF bias (B3)
MTF_MACD_HIST_BEARISH_MAX = 10      # [TUNE] MACD histogram <= this for bearish HTF bias (B3)

# --- Warmup ---
MIN_WARMUP_CANDLES = 28             # engine skips first N candles (matches TS strategy.minWarmup)

# --- Strategy exit (B6) ---
LONG_PROTECTION_EXIT_CANDLES_1 = 5    # first long-protection exit check after this many candles
LONG_PROTECTION_PROGRESS_1 = 30       # [TUNE] exit if progress < this (pts) at check 1
LONG_PROTECTION_EXIT_CANDLES_2 = 8    # second long-protection exit check
LONG_PROTECTION_PROGRESS_2 = 45       # [TUNE] exit if progress < this (pts) at check 2
GENERAL_EXIT_CANDLES = 6              # general stale-trade exit after this many candles
GENERAL_EXIT_PROGRESS = 15            # [TUNE] exit if progress < this (pts)

# --- scaled_units_long_hold: 15m swing profile (anti-bot, multi-hour holds) ---
LONG_HOLD_STOP_BUFFER_POINTS = 0
LONG_HOLD_FEE_LOCK_AFTER_TARGET_INDEX = 99
LONG_HOLD_FEE_LOCK_POINTS = 0
LONG_HOLD_MIN_CANDLES_BEFORE_STOP_TIGHTEN = 0
LONG_HOLD_MIN_FAVORABLE_POINTS_BEFORE_STOP_TIGHTEN = 0
LONG_HOLD_GENERAL_EXIT_CANDLES = 9999
LONG_HOLD_GENERAL_EXIT_PROGRESS = 15
LONG_HOLD_MIN_ENTRY_SCORE = 10
LONG_HOLD_MIN_MINUTES_BETWEEN_TRADES = 0
LONG_HOLD_MAX_LOSS_POINTS_PER_TRADE = 70
LONG_HOLD_MAX_QUANTITY_CAP = 2
LONG_HOLD_MIN_SCORE_FOR_TWO_UNITS = 10
LONG_HOLD_TRAILING_MIN_TARGETS_HIT = 99
LONG_HOLD_PROFIT_STOP_REQUIRES_REVERSAL_CLOSE = True
LONG_HOLD_LOSS_STREAK_PAUSE_COUNT = 0
LONG_HOLD_LOSS_STREAK_PAUSE_MINUTES = 0

LONG_HOLD_SWING_SIGNAL_TIMEFRAME_MINUTES = 15
LONG_HOLD_SWING_EMA_FAST = 4
LONG_HOLD_SWING_EMA_SLOW = 9
LONG_HOLD_SWING_EMA_TREND = 21
LONG_HOLD_SWING_CLOSE_LONG_MIN = 0.55
LONG_HOLD_SWING_CLOSE_SHORT_MAX = 0.45
LONG_HOLD_SWING_LONG_MIN_RANGE_POINTS = 130
LONG_HOLD_SWING_RSI_LONG_MIN = 45
LONG_HOLD_SWING_RSI_SHORT_MAX = 55
LONG_HOLD_SWING_SHORT_MIN_FAST_SLOW_GAP = 45
LONG_HOLD_SWING_SHORT_MAX_SLOW_TREND_GAP = 45
LONG_HOLD_SWING_MIN_PROFIT_HOLD_MINUTES = 90
LONG_HOLD_SWING_TRAIL_START_POINTS = 350
LONG_HOLD_SWING_TRAIL_DISTANCE_POINTS = 160
LONG_HOLD_SWING_MAX_HOLD_MINUTES = 240
LONG_HOLD_SWING_DISABLE_TAKE_PROFIT = True
