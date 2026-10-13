"""Strategy comparison: same window, same capital, two fee scenarios."""
import json
import time

from .backtest import run_backtest, variant
from .config import load_config
from .data import (DATA_DIR, align, fetch_coinbase_daily, fetch_kraken_daily, load_csv,
                   save_csv)

# Fee scenarios (per side). Kraken Pro tier-1 published fees: 0.40% maker / 0.80% taker.
SCENARIOS = {
    "maker (0.40% fee + 0.05% slip)": (0.0040, 0.0005),
    "taker (0.80% fee + 0.15% slip)": (0.0080, 0.0015),
    "app/instant (1.00% fee + 0.50% spread)": (0.0100, 0.0050),
}


def fetch_all():
    for asset, pair in (("BTC", "XBTCAD"), ("ETH", "ETHCAD")):
        rows = fetch_kraken_daily(pair)
        save_csv(DATA_DIR / f"kraken_{asset}_CAD_daily.csv", rows)
        print(f"kraken {asset}/CAD: {len(rows)} days")
    start = int(time.mktime((2017, 1, 1, 0, 0, 0, 0, 0, 0)))
    for asset in ("BTC", "ETH"):
        rows = fetch_coinbase_daily(f"{asset}-USD", start, int(time.time()))
        save_csv(DATA_DIR / f"coinbase_{asset}_USD_daily.csv", rows)
        print(f"coinbase {asset}/USD: {len(rows)} days")


def strategy_set(cfg):
    return {
        "Buy & hold 100% BTC (benchmark)": variant(cfg, name="hold", targets={"BTC": 1.0}),
        "Buy & hold 70% BTC / 30% ETH": variant(cfg, name="hold", targets={"BTC": 0.7, "ETH": 0.3}),
        "Scheduled entry BTC (5 x CAD10, 14d apart)": variant(cfg, name="scheduled_entry", targets={"BTC": 1.0}),
        "Trend filter BTC (200d SMA, weekly)": variant(cfg, name="trend_filter", targets={"BTC": 1.0}, sma_days=200),
        "Trend filter 70/30 BTC/ETH (200d SMA)": variant(cfg, name="trend_filter", targets={"BTC": 0.7, "ETH": 0.3}, sma_days=200),
        "Rebalance 70/30 (10pp band, weekly)": variant(cfg, name="rebalance", targets={"BTC": 0.7, "ETH": 0.3}),
        "[sensitivity] Trend filter BTC 100d SMA": variant(cfg, name="trend_filter", targets={"BTC": 1.0}, sma_days=100),
    }


def compare(cfg, bars, label, start_index):
    results = {"dataset": label, "rows": []}
    for name, c in strategy_set(cfg).items():
        for scen, (fee, slip) in SCENARIOS.items():
            r = run_backtest(c, bars, fee, slip, start_index=start_index)
            results["rows"].append({"strategy": name, "scenario": scen, **r})
    return results


def windows(bars, start_index, years):
    """Yield (label, start_index) sub-windows to show regime dependence."""
    n = len(next(iter(bars.values())))
    step = int(365 * years)
    i = start_index
    while i + step <= n:
        yield i
        i += step


CURVE_STRATEGIES = ["Buy & hold 100% BTC (benchmark)", "Buy & hold 70% BTC / 30% ETH",
                    "Scheduled entry BTC (5 x CAD10, 14d apart)", "Trend filter BTC (200d SMA, weekly)",
                    "Rebalance 70/30 (10pp band, weekly)"]


def export_curves(cfg, datasets):
    """Equity curves (taker scenario) for charts. Long history is sampled weekly."""
    fee, slip = SCENARIOS["taker (0.80% fee + 0.15% slip)"]
    rows = []
    for key, bars in datasets.items():
        for name, c in strategy_set(cfg).items():
            if name not in CURVE_STRATEGIES:
                continue
            curve = run_backtest(c, bars, fee, slip, start_index=200, return_curve=True)["curve"]
            step = 7 if key == "long_usd" else 1
            pts = curve[::step] + ([curve[-1]] if (len(curve) - 1) % step else [])
            rows += [{"dataset": key, "strategy": name, "date": d, "equity": v} for d, v in pts]
    with open(DATA_DIR / "backtest_curves.json", "w") as f:
        json.dump(rows, f)


def main():
    cfg = load_config()
    out = []
    kb = align({a: load_csv(DATA_DIR / f"kraken_{a}_CAD_daily.csv") for a in ("BTC", "ETH")})
    out.append(compare(cfg, kb, "Kraken BTC/CAD + ETH/CAD daily (actual CAD market)", 200))
    cb = align({a: load_csv(DATA_DIR / f"coinbase_{a}_USD_daily.csv") for a in ("BTC", "ETH")})
    out.append(compare(cfg, cb, "Coinbase BTC/USD + ETH/USD daily since 2017 (USD proxy, long history)", 200))
    # Rolling 2-year windows on the long dataset: how often does each strategy beat buy & hold?
    roll = []
    n = len(cb["BTC"])
    for s in range(200, n - 730, 91):
        sub = {a: rows[: s + 730] for a, rows in cb.items()}
        row = {"start_index": s}
        for name, c in strategy_set(cfg).items():
            row[name] = run_backtest(c, sub, 0.0080, 0.0015, start_index=s)
        roll.append(row)
    out.append({"dataset": "Rolling 2-year windows (quarterly starts), taker scenario, USD proxy",
                "rows": [{"start": r["Buy & hold 100% BTC (benchmark)"]["start"],
                          **{k: {"ret": v["return_pct"], "mdd": v["max_drawdown_pct"]}
                             for k, v in r.items() if k != "start_index"}} for r in roll]})
    with open(DATA_DIR / "backtest_results.json", "w") as f:
        json.dump(out, f, indent=2)
    export_curves(cfg, {"kraken_cad": kb, "long_usd": cb})
    for block in out[:2]:
        print(f"\n=== {block['dataset']} ===")
        print(f"{'strategy':45s} {'scenario':40s} {'final':>7s} {'ret%':>7s} {'CAGR%':>6s} {'maxDD%':>7s} {'trades':>6s} {'fees':>6s}  fee-budget-hit")
        for r in block["rows"]:
            print(f"{r['strategy']:45s} {r['scenario']:40s} {r['final_cad']:7.2f} {r['return_pct']:7.1f} "
                  f"{r['cagr_pct']:6.1f} {r['max_drawdown_pct']:7.1f} {r['trades']:6d} {r['fees_cad']:6.2f}  {r['fee_budget_hit'] or '-'}")
        print(f"window: {block['rows'][0]['start']} -> {block['rows'][0]['end']}")
    rb = out[2]["rows"]
    print(f"\n=== {out[2]['dataset']}: {len(rb)} windows ===")
    names = [k for k in rb[0] if k != "start"]
    bench = "Buy & hold 100% BTC (benchmark)"
    for k in names:
        rets = sorted(r[k]["ret"] for r in rb)
        beats = sum(1 for r in rb if r[k]["ret"] > r[bench]["ret"])
        shallower = sum(1 for r in rb if r[k]["mdd"] > r[bench]["mdd"])
        print(f"{k:45s} median ret {rets[len(rets)//2]:7.1f}%  worst {rets[0]:7.1f}%  "
              f"beats B&H {beats}/{len(rb)}  shallower DD {shallower}/{len(rb)}  "
              f"windows losing money {sum(1 for x in rets if x < 0)}/{len(rb)}")


if __name__ == "__main__":
    main()
