"""Daily-bar backtester that reuses the live strategy + order planner.

No look-ahead: the decision on day t uses closes up to day t-1 (the last completed
candle) and fills at day t's OPEN, adjusted for slippage, then charges a fee.
Activity limits from the config are enforced the same way the live bot does.
"""
import copy
import math
from datetime import datetime, timezone

from . import strategy

DAY = 86400


def run_backtest(cfg, bars, fee_rate, slippage, start_index=None, return_curve=False):
    """bars: {asset: [(ts, open, high, low, close), ...]} aligned on the same timestamps."""
    assets = list(cfg["strategy"]["targets"].keys())
    ts_list = [b[0] for b in bars[assets[0]]]
    for a in assets:
        assert [b[0] for b in bars[a]] == ts_list, "bars must be aligned"
    warm = cfg["strategy"]["sma_days"] if cfg["strategy"]["name"] == "trend_filter" else 1
    start = max(start_index or 0, warm)

    cap = cfg["capital"]["max_capital_cad"]
    cash, hold = cap, {a: 0.0 for a in assets}
    fees, trades, last_trade = 0.0, [], {}
    last_decision = None
    fee_budget_hit = None
    equity_curve = []
    lim = cfg["activity_limits"]
    for i in range(start, len(ts_list)):
        ts = ts_list[i]
        opens = {a: bars[a][i][1] for a in assets}
        if strategy.is_decision_day(cfg, ts, last_decision):
            last_decision = ts
            ctx = {"closes": {a: [b[4] for b in bars[a][:i]] for a in assets},
                   "days_since_start": (i - start)}
            targets = strategy.target_weights(cfg, ctx)
            hv = {a: hold[a] * opens[a] for a in assets}
            for o in strategy.plan_trades(cfg, targets, hv, cash, opens):
                a = o["asset"]
                if a in last_trade and ts - last_trade[a] < lim["min_days_between_trades_same_asset"] * DAY:
                    continue
                if sum(1 for t in trades if ts - t[0] < 30 * DAY) >= lim["max_orders_per_30_days"]:
                    continue
                if o["side"] == "buy":
                    cad = min(o["cad"], cash)
                    if cad < cfg["orders"]["min_order_cad"]:
                        continue
                    px = opens[a] * (1 + slippage)
                    fee = cad * fee_rate
                    hold[a] += (cad - fee) / px
                    cash -= cad
                else:
                    px = opens[a] * (1 - slippage)
                    qty = min(hold[a], o["cad"] / opens[a])
                    gross = qty * px
                    fee = gross * fee_rate
                    hold[a] -= qty
                    cash += gross - fee
                fees += fee
                if fee_budget_hit is None and fees > lim["max_cumulative_fees_cad"]:
                    fee_budget_hit = ts  # the live bot would pause here pending re-approval
                last_trade[a] = ts
                trades.append((ts, a, o["side"], round(fee, 4)))
        closes = {a: bars[a][i][4] for a in assets}
        equity_curve.append((ts, cash + sum(hold[a] * closes[a] for a in assets)))
    res = summarize(cap, equity_curve, fees, trades)
    res["fee_budget_hit"] = (datetime.fromtimestamp(fee_budget_hit, timezone.utc).date().isoformat()
                             if fee_budget_hit else None)
    if return_curve:
        res["curve"] = [(datetime.fromtimestamp(t, timezone.utc).date().isoformat(), round(v, 4))
                        for t, v in equity_curve]
    return res


def summarize(cap, curve, fees, trades):
    vals = [v for _, v in curve]
    peak, mdd = -math.inf, 0.0
    for v in vals:
        peak = max(peak, v)
        mdd = min(mdd, v / peak - 1)
    days = (curve[-1][0] - curve[0][0]) / DAY if len(curve) > 1 else 0
    final = vals[-1]
    cagr = (final / cap) ** (365 / days) - 1 if days > 30 else float("nan")
    return {
        "start": datetime.fromtimestamp(curve[0][0], timezone.utc).date().isoformat(),
        "end": datetime.fromtimestamp(curve[-1][0], timezone.utc).date().isoformat(),
        "final_cad": round(final, 2), "return_pct": round((final / cap - 1) * 100, 1),
        "cagr_pct": round(cagr * 100, 1), "max_drawdown_pct": round(mdd * 100, 1),
        "trades": len(trades), "fees_cad": round(fees, 2), "min_equity_cad": round(min(vals), 2),
    }


def variant(cfg, **strategy_overrides):
    c = copy.deepcopy(cfg)
    c["strategy"].update(strategy_overrides)
    return c
