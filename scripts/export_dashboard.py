"""Write the hosted dashboard's data files (data/dashboard/) from backtest output + research tables.

Run after `python -m cryptobot backtest`. Research rows below were verified on 2026-10-13;
`confidence` says how (live API, official docs, or third-party only).
"""
import csv
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from cryptobot.config import load_config  # noqa: E402

DATA = ROOT / "data"
OUT = DATA / "dashboard"
SCEN = {"maker (0.40% fee + 0.05% slip)": "maker", "taker (0.80% fee + 0.15% slip)": "taker",
        "app/instant (1.00% fee + 0.50% spread)": "app"}
KEYS = ["kraken_cad", "long_usd"]


def write_csv(name, rows):
    with open(OUT / name, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    res = json.load(open(DATA / "backtest_results.json"))
    summary = []
    for key, block in zip(KEYS, res[:2]):
        for r in block["rows"]:
            sc = SCEN[r["scenario"]]
            summary.append({"id": f"{key}|{sc}|{r['strategy']}", "dataset": key, "fees": sc,
                            "strategy": r["strategy"], "start": r["start"], "end": r["end"],
                            "final_cad": r["final_cad"], "return_pct": r["return_pct"], "cagr_pct": r["cagr_pct"],
                            "max_drawdown_pct": r["max_drawdown_pct"], "trades": r["trades"],
                            "fees_cad": r["fees_cad"], "fee_budget_hit": r.get("fee_budget_hit") or ""})
    write_csv("bt_summary.csv", summary)

    rolling = []
    for r in res[2]["rows"]:
        for k, v in r.items():
            if k != "start":
                rolling.append({"window_start": r["start"], "strategy": k, "return_pct": v["ret"],
                                "max_drawdown_pct": v["mdd"]})
    write_csv("bt_rolling.csv", rolling)

    curves = json.load(open(DATA / "backtest_curves.json"))
    write_csv("bt_curves.csv", curves)

    cfg = load_config()
    lim, o, sf = cfg["activity_limits"], cfg["orders"], cfg["safety"]
    risk = [
        {"rule": "Total money the bot may use", "value": f"CAD {cfg['capital']['max_capital_cad']:.2f}", "enforced": "Config refuses more than 50; buys can never push cost above it"},
        {"rule": "Adding money", "value": "Never", "enforced": "The exchange client has no deposit or withdrawal functions"},
        {"rule": "Buying more after a loss", "value": "Never", "enforced": "Buys use only the bot's own remaining cash"},
        {"rule": "Bitcoin share of portfolio", "value": f"up to {cfg['universe']['BTC']['max_allocation']:.0%}", "enforced": "Checked on every buy"},
        {"rule": "Ether share of portfolio", "value": f"up to {cfg['universe']['ETH']['max_allocation']:.0%} (target 0%)", "enforced": "Checked on every buy"},
        {"rule": "Orders per day", "value": str(lim["max_orders_per_day"]), "enforced": "Checked on every order"},
        {"rule": "Orders per 30 days", "value": str(lim["max_orders_per_30_days"]), "enforced": "Checked on every order"},
        {"rule": "Days between trades in one coin", "value": str(lim["min_days_between_trades_same_asset"]), "enforced": "Checked on every order"},
        {"rule": "Order size", "value": f"CAD {o['min_order_cad']:.0f} to {o['max_order_cad']:.0f}", "enforced": "Plus the exchange's own minimums"},
        {"rule": "Lifetime fee budget", "value": f"CAD {lim['max_cumulative_fees_cad']:.2f}", "enforced": "Bot stops trading until you approve again"},
        {"rule": "Order type", "value": "Limit, maker-only, 1-hour expiry", "enforced": "Market, stop, margin and futures orders do not exist in the code"},
        {"rule": "Big drop safety", "value": f"No new buys below CAD {(1 - sf['drawdown_pause_pct']) * cfg['capital']['max_capital_cad']:.0f}", "enforced": "Needs your review; the bot does not panic-sell"},
        {"rule": "Unusual market move", "value": f"Pause above ±{sf['max_24h_move_pct']:.0%} in 24 h", "enforced": "Checked every run"},
        {"rule": "Wide spread", "value": f"Pause above {sf['max_spread_pct']:.0%}", "enforced": "Checked every run"},
        {"rule": "Stale or bad data", "value": f"Pause if data is over {sf['max_ticker_age_seconds']} s old", "enforced": "Checked every run"},
        {"rule": "Repeated errors", "value": f"Pause after {sf['max_consecutive_errors']} in a row", "enforced": "Checked every run"},
        {"rule": "API key can withdraw", "value": "Refuse to trade", "enforced": "Permission test before every live run"},
        {"rule": "Live trading", "value": "Off until you approve", "enforced": "4 gates: config flag, typed approval, secret phrase, kill switch off"},
    ]
    json.dump(risk, open(OUT / "risk_limits.json", "w"), indent=1)

    platforms = [
        {"platform": "Kraken Pro", "registered": "Yes (restricted dealer)", "btc_cad_direct": "Yes", "maker_fee_pct": 0.40, "taker_fee_pct": 0.80,
         "cad_withdrawal": "CAD 10 (e-Transfer)", "min_btc_order": "0.00005 BTC (~CAD 6)", "trading_api": "Yes, withdraw can be disabled",
         "confidence": "Live API + official docs", "verdict": "Recommended", "link": "https://www.kraken.com/features/fee-schedule"},
        {"platform": "NDAX", "registered": "Yes", "btc_cad_direct": "Yes", "maker_fee_pct": 0.20, "taker_fee_pct": 0.20,
         "cad_withdrawal": "CAD 1.50 (e-Transfer)", "min_btc_order": "Not verified", "trading_api": "Not verified",
         "confidence": "Third-party reviews only", "verdict": "Cheapest fees; not yet supported by the bot", "link": "https://www.finder.com/ca/cryptocurrency/exchanges/ndax-review"},
        {"platform": "Coinbase Advanced", "registered": "Yes (restricted dealer)", "btc_cad_direct": "No (USDC-CAD only)", "maker_fee_pct": 0.40, "taker_fee_pct": 0.60,
         "cad_withdrawal": "Varies", "min_btc_order": "Not verified", "trading_api": "Yes",
         "confidence": "Live API (pairs); fees third-party", "verdict": "Two trades and two fees to buy BTC with CAD", "link": "https://www.finder.com/ca/coinbase-exchange-review"},
        {"platform": "Shakepay", "registered": "Yes (investment dealer)", "btc_cad_direct": "Yes (app)", "maker_fee_pct": None, "taker_fee_pct": None,
         "cad_withdrawal": "Not verified", "min_btc_order": "Small", "trading_api": "No",
         "confidence": "Third-party reviews only", "verdict": "Spread-based; recurring buys only", "link": "https://koinly.io/blog/best-crypto-exchange-canada/"},
        {"platform": "Newton", "registered": "Yes", "btc_cad_direct": "Yes (app)", "maker_fee_pct": None, "taker_fee_pct": None,
         "cad_withdrawal": "Not verified", "min_btc_order": "Small", "trading_api": "No",
         "confidence": "Official blog + third-party", "verdict": "Spread-based; no automation", "link": "https://www.newton.co/blog/a-broader-newton-signature-program"},
        {"platform": "Pionex", "registered": "No evidence for Canada", "btc_cad_direct": "No (crypto deposits)", "maker_fee_pct": 0.05, "taker_fee_pct": 0.05,
         "cad_withdrawal": "No CAD", "min_btc_order": "Not verified", "trading_api": "Built-in bots",
         "confidence": "Third-party reviews only", "verdict": "Excluded: not registered in Canada", "link": "https://coinbureau.com/review/pionex-review/"},
    ]
    json.dump(platforms, open(OUT / "platforms.json", "w"), indent=1)

    free = [
        {"opportunity": "Coinbase Learning Rewards", "you_get": "Small amounts of real tokens (rotating, often not ETH)", "real_money": "Yes",
         "id_required": "Yes", "payment_required": "No", "status": "Canada listed by third parties; current availability not confirmed",
         "link": "https://coinledger.io/tools/learn-and-earn-crypto"},
        {"opportunity": "Security bug bounties paid in crypto", "you_get": "Real ETH or stablecoins for valid findings", "real_money": "Yes",
         "id_required": "Usually", "payment_required": "No", "status": "Paid work, not a giveaway; programs not verified this session",
         "link": "https://ethereum.org/bug-bounty/"},
        {"opportunity": "Open-source bounties and grants", "you_get": "Real tokens or stablecoins for contributions", "real_money": "Yes",
         "id_required": "Varies", "payment_required": "No", "status": "Paid work; check the project's official repo",
         "link": "https://www.gitcoin.co/"},
        {"opportunity": "Shakepay Shaking Sats", "you_get": "Small amounts of real BTC (not ETH)", "real_money": "Yes",
         "id_required": "Yes", "payment_required": "Tied to account activity", "status": "Could not confirm it still runs in 2026",
         "link": "https://shakepay.com/"},
        {"opportunity": "Sepolia / Hoodi testnet faucets", "you_get": "Testnet ETH for practice only", "real_money": "No: zero value, cannot be sold",
         "id_required": "Some need a login", "payment_required": "No", "status": "Listed on ethereum.org",
         "link": "https://ethereum.org/developers/docs/networks/"},
    ]
    json.dump(free, open(OUT / "free_crypto.json", "w"), indent=1)
    for p in sorted(OUT.iterdir()):
        print(p.name, p.stat().st_size)


if __name__ == "__main__":
    main()
