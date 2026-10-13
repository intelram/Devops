"""Historical daily data download (documented public endpoints, no keys) and CSV cache."""
import csv
import json
import time
import urllib.parse
import urllib.request
from pathlib import Path

from .config import ROOT
from .kraken import KrakenPublic

DATA_DIR = ROOT / "data"


def save_csv(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["ts", "open", "high", "low", "close"])
        w.writerows(rows)


def load_csv(path):
    with open(path) as f:
        r = csv.reader(f)
        next(r)
        return [(int(x[0]), float(x[1]), float(x[2]), float(x[3]), float(x[4])) for x in r]


def fetch_kraken_daily(pair):
    """Kraken returns at most 720 daily candles. Drops the in-progress candle."""
    return KrakenPublic().daily_ohlc(pair)[:-1]


def fetch_coinbase_daily(product, start_ts, end_ts):
    """Coinbase Exchange public candles (max 300 per request). Returns ascending rows."""
    out = {}
    step = 300 * 86400
    t = start_ts
    while t < end_ts:
        q = urllib.parse.urlencode({"granularity": 86400,
                                    "start": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t)),
                                    "end": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(min(t + step, end_ts)))})
        req = urllib.request.Request(f"https://api.exchange.coinbase.com/products/{product}/candles?{q}",
                                     headers={"User-Agent": "cryptobot/1.0"})
        with urllib.request.urlopen(req, timeout=20) as r:
            for ts, low, high, opn, close, _vol in json.loads(r.read()):
                out[int(ts)] = (int(ts), opn, high, low, close)
        t += step
        time.sleep(0.4)  # stay well under public rate limits
    today = int(time.time()) // 86400 * 86400
    return [out[k] for k in sorted(out) if k < today]


def align(series):
    """Keep only timestamps present in every series."""
    common = set.intersection(*(set(r[0] for r in rows) for rows in series.values()))
    return {k: [r for r in rows if r[0] in common] for k, rows in series.items()}
