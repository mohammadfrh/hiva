from __future__ import annotations

import json
from dataclasses import asdict
from src.engine.backtester import run_backtest
from src.engine.live_signal import evaluate_live_signal
from src.logger import export_backtest_results
from src.services.mock_data import load_dataset
from src.strategies import PROFILES
from src.types import Candle


def _summary_payload(summary) -> dict:
    s = summary.summary
    return {
        "trade_count": s.trade_count,
        "win_rate": s.win_rate,
        "net_profit": s.net_profit,
        "ending_balance": s.ending_balance,
        "profit_factor": s.profit_factor,
        "max_drawdown_percent": s.max_drawdown_percent,
        "win_count": s.win_count,
        "loss_count": s.loss_count,
        "total_fees": s.total_fees,
        "withdrawn_profit": s.withdrawn_profit,
        "average_win": s.average_win,
        "average_loss": s.average_loss,
        "trades": [asdict(t) for t in summary.trades],
    }


def run_dataset_backtest_json(
    profile_id: str,
    dataset_id: str,
    mock_dir: str,
    outputs_dir: str | None = None,
) -> str:
    if profile_id not in PROFILES:
        raise ValueError(f"Unknown profile: {profile_id}")
    candles_by_tf = load_dataset(mock_dir, dataset_id)
    candles_1m = candles_by_tf.get("1m", [])
    if not candles_1m:
        raise ValueError(f"Dataset has no 1m candles: {dataset_id}")
    mtf = {k: v for k, v in candles_by_tf.items() if k != "1m"}
    summary = run_backtest(dataset_id, PROFILES[profile_id], candles_1m, mtf)
    if outputs_dir:
        export_backtest_results(summary, dataset_id, profile_id, outputs_dir)
    return json.dumps(_summary_payload(summary))


def run_payload_backtest_json(
    profile_id: str,
    candles_1m_payload,
    mtf_payload,
    dataset_name: str = "live",
) -> str:
    if profile_id not in PROFILES:
        raise ValueError(f"Unknown profile: {profile_id}")
    candles_raw = json.loads(candles_1m_payload) if isinstance(candles_1m_payload, str) else candles_1m_payload
    mtf_raw = json.loads(mtf_payload) if isinstance(mtf_payload, str) else mtf_payload

    candles_1m = [Candle(int(x["time"]), float(x["open"]), float(x["high"]), float(x["low"]), float(x["close"])) for x in candles_raw]
    mtf = {
        tf: [Candle(int(x["time"]), float(x["open"]), float(x["high"]), float(x["low"]), float(x["close"])) for x in arr]
        for tf, arr in (mtf_raw or {}).items()
    }

    summary = run_backtest(dataset_name, PROFILES[profile_id], candles_1m, mtf)
    return json.dumps(_summary_payload(summary))


def run_payload_signal_json(
    profile_id: str,
    candles_1m_payload,
    mtf_payload,
) -> str:
    if profile_id not in PROFILES:
        raise ValueError(f"Unknown profile: {profile_id}")
    candles_raw = json.loads(candles_1m_payload) if isinstance(candles_1m_payload, str) else candles_1m_payload
    mtf_raw = json.loads(mtf_payload) if isinstance(mtf_payload, str) else mtf_payload
    candles_1m = [Candle(int(x["time"]), float(x["open"]), float(x["high"]), float(x["low"]), float(x["close"])) for x in candles_raw]
    mtf = {
        tf: [Candle(int(x["time"]), float(x["open"]), float(x["high"]), float(x["low"]), float(x["close"])) for x in arr]
        for tf, arr in (mtf_raw or {}).items()
    }
    res = evaluate_live_signal(candles_1m, mtf, PROFILES[profile_id])
    payload = asdict(res)
    return json.dumps(payload)
