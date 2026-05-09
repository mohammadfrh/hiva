"""
Mock data loader - reads candle JSON files from the mock/ directory.
"""

from __future__ import annotations
import json
from pathlib import Path
from src.types import Candle


def load_candles(path: str | Path) -> list[Candle]:
    """
    Load a list of candles from a JSON file.
    Expected format: [{time, open, high, low, close}, ...]
    """
    with open(path, "r") as f:
        raw = json.load(f)
    return [
        Candle(
            time=int(item["time"]),
            open=float(item["open"]),
            high=float(item["high"]),
            low=float(item["low"]),
            close=float(item["close"]),
        )
        for item in raw
    ]


def load_dataset(mock_dir: str | Path, dataset: str) -> dict[str, list[Candle]]:
    """
    Load all timeframes for a named dataset.

    Args:
        mock_dir: path to the mock/ directory
        dataset: dataset prefix, e.g. "aday" or "amonth"

    Returns:
        dict keyed by timeframe string: {"1m": [...], "5m": [...], ...}
    """
    mock_dir = Path(mock_dir)
    timeframes = ["1m", "5m", "15m", "30m", "60m"]
    result: dict[str, list[Candle]] = {}
    for tf in timeframes:
        path = mock_dir / f"{dataset}-{tf}.json"
        if path.exists():
            result[tf] = load_candles(path)
    return result
