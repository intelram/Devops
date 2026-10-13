"""Strategies return TARGET WEIGHTS (fraction of max capital per asset; remainder is CAD).

All functions are pure and use only closes up to and including `closes[-1]`
(the most recent COMPLETED daily candle). Execution happens afterwards, so
there is no look-ahead in backtests.
"""
from datetime import datetime, timezone


def sma(values, n):
    if len(values) < n:
        return None
    return sum(values[-n:]) / n


def hold_targets(cfg, ctx):
    return dict(cfg["strategy"]["targets"])


def scheduled_entry_targets(cfg, ctx):
    """Deploy the fixed capital in equal tranches (no new deposits), then hold."""
    s = cfg["strategy"]
    tranches = s["scheduled_entry_tranches"]
    days = ctx["days_since_start"]
    done = min(tranches, days // s["scheduled_entry_interval_days"] + 1)
    frac = done / tranches
    return {a: w * frac for a, w in s["targets"].items()}


def trend_filter_targets(cfg, ctx):
    """Hold an asset only while its last close is above its N-day SMA; otherwise CAD.
    Evaluated only on the configured weekday to cap trading frequency."""
    s = cfg["strategy"]
    out = {}
    for asset, w in s["targets"].items():
        closes = ctx["closes"][asset]
        m = sma(closes, s["sma_days"])
        if m is None:
            out[asset] = 0.0  # not enough history -> stay in cash
        else:
            out[asset] = w if closes[-1] > m else 0.0
    return out


def rebalance_targets(cfg, ctx):
    """Fixed weights; the executor only trades when drift exceeds the band."""
    return dict(cfg["strategy"]["targets"])


STRATEGIES = {
    "hold": hold_targets,
    "scheduled_entry": scheduled_entry_targets,
    "trend_filter": trend_filter_targets,
    "rebalance": rebalance_targets,
}


def is_decision_day(cfg, ts, last_decision_ts):
    """Rate-limit decisions: trend/rebalance once per week on a fixed weekday."""
    name = cfg["strategy"]["name"]
    d = datetime.fromtimestamp(ts, timezone.utc)
    if name in ("trend_filter", "rebalance"):
        return d.weekday() == cfg["strategy"]["check_weekday"] and (
            last_decision_ts is None or ts - last_decision_ts >= 6 * 86400)
    return True


def target_weights(cfg, ctx):
    return STRATEGIES[cfg["strategy"]["name"]](cfg, ctx)


def plan_trades(cfg, targets, holdings_value, cash, prices):
    """Translate target weights into CAD-denominated orders.

    holdings_value: {asset: current CAD value}. `cash` is the bot's own CAD sleeve
    (starts at max_capital, never topped up). Buys are sized against
    min(equity, max_capital), so gains are not re-invested above the authorized
    amount and losses are never "made back" with extra money. Sells only occur when
    a position is genuinely overweight versus total equity or the target is zero.
    Returns list of dicts {asset, side, cad}.
    """
    equity = cash + sum(holdings_value.values())
    buy_base = min(equity, cfg["capital"]["max_capital_cad"])
    band = cfg["strategy"].get("rebalance_band", 0.0) if cfg["strategy"]["name"] == "rebalance" else 0.0
    min_cad = cfg["orders"]["min_order_cad"]
    orders = []
    for asset in cfg["universe"]:
        tgt = targets.get(asset, 0.0)
        cur = holdings_value.get(asset, 0.0)
        if tgt == 0.0:
            if cur >= min_cad:  # full exit; dust below the minimum is left alone
                orders.append({"asset": asset, "side": "sell", "cad": cur})
            continue
        if band and equity > 0 and abs(cur / equity - tgt) < band:
            continue
        buy_gap = tgt * buy_base - cur
        sell_gap = cur - tgt * equity
        if buy_gap >= min_cad:
            orders.append({"asset": asset, "side": "buy", "cad": buy_gap})
        elif band and sell_gap >= min_cad:
            # Only the rebalance strategy trims winners; hold/trend/scheduled never do.
            orders.append({"asset": asset, "side": "sell", "cad": sell_gap})
    # Sells first so buys can use the freed cash.
    orders.sort(key=lambda o: o["side"] != "sell")
    return orders
