"""
Trade logger - classifies trades and exports JSON output files.

Output files per backtest run:
  {dataset}-{profile}-summary.json
  {dataset}-{profile}-trades.json
  {dataset}-{profile}-worst-losses.json
  {dataset}-{profile}-trade-log-wins.json
  {dataset}-{profile}-trade-log-losses.json
  {dataset}-{profile}-trade-log-summary.json
  trades-YYYY-MM-DD.log  (daily append log)
"""

from __future__ import annotations
import json
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path
from src.types import BacktestSummary, Trade


# ---------------------------------------------------------------------------
# Trade classification
# ---------------------------------------------------------------------------

def is_win(trade: Trade) -> bool:
    return trade.net_pnl_money > 0


def is_loss(trade: Trade) -> bool:
    return trade.net_pnl_money <= 0


# ---------------------------------------------------------------------------
# Serialization helpers
# ---------------------------------------------------------------------------

def _trade_to_dict(t: Trade) -> dict:
    d = asdict(t)
    # convert snake_case keys to camelCase to match existing output schema
    return {_to_camel(k): v for k, v in d.items()}


def _summary_to_dict(summary: BacktestSummary) -> dict:
    def _strategy_to_dict(s):
        return {_to_camel(k): v for k, v in asdict(s).items()}

    return {
        "engineName": summary.engine_name,
        "assumptions": summary.assumptions,
        "summary": _strategy_to_dict(summary.summary),
        "perStrategy": [_strategy_to_dict(s) for s in summary.per_strategy],
        "trades": [_trade_to_dict(t) for t in summary.trades],
    }


def _to_camel(snake: str) -> str:
    parts = snake.split("_")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


def _write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f, indent=2)


# ---------------------------------------------------------------------------
# Main export function
# ---------------------------------------------------------------------------

def export_backtest_results(
    summary: BacktestSummary,
    dataset: str,
    profile_id: str,
    outputs_dir: str | Path,
) -> None:
    """
    Write all output files for a backtest run.
    """
    outputs_dir = Path(outputs_dir)
    prefix = f"{dataset}-{profile_id}"
    trades = summary.trades

    wins = [t for t in trades if is_win(t)]
    losses = [t for t in trades if is_loss(t)]
    worst_losses = sorted(losses, key=lambda t: t.net_pnl_money)[:10]

    # Full summary (matches existing output schema)
    _write_json(outputs_dir / f"{prefix}-summary.json", _summary_to_dict(summary))

    # All trades
    _write_json(outputs_dir / f"{prefix}-trades.json", [_trade_to_dict(t) for t in trades])

    # Worst losses
    _write_json(outputs_dir / f"{prefix}-worst-losses.json", [_trade_to_dict(t) for t in worst_losses])

    # Trade log splits
    _write_json(outputs_dir / f"{prefix}-trade-log-wins.json", [_trade_to_dict(t) for t in wins])
    _write_json(outputs_dir / f"{prefix}-trade-log-losses.json", [_trade_to_dict(t) for t in losses])

    log_summary = {
        "dataset": dataset,
        "profile": profile_id,
        "tradeCount": len(trades),
        "winCount": len(wins),
        "lossCount": len(losses),
        "winRate": round(len(wins) / len(trades), 4) if trades else 0,
        "netProfit": summary.summary.net_profit,
        "withdrawnProfit": summary.summary.withdrawn_profit,
        "maxDrawdown": summary.summary.max_drawdown,
    }
    _write_json(outputs_dir / f"{prefix}-trade-log-summary.json", log_summary)

    # Daily log line
    _append_daily_log(trades, outputs_dir)


def _append_daily_log(trades: list[Trade], outputs_dir: Path) -> None:
    """Append one log line per trade to the daily trades log file."""
    today = datetime.now(tz=timezone.utc).strftime("%Y-%m-%d")
    log_path = outputs_dir / f"trades-{today}.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with open(log_path, "a") as f:
        for t in trades:
            sign = "+" if t.net_pnl_money >= 0 else ""
            line = (
                f"{t.exit_day} | {t.direction.upper():5s} | "
                f"entry={t.entry_price} exit={t.exit_price} | "
                f"pts={sign}{t.pnl_price_points:.1f} net={sign}{t.net_pnl_money:.0f} | "
                f"reason={t.exit_reason} score={t.setup_score}\n"
            )
            f.write(line)
