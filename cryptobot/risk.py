"""Risk engine: every order and every market snapshot must pass these checks.

The checks are intentionally conservative and fail closed: any doubt means no trade.
"""
import time

DAY = 86400


class RiskEngine:
    def __init__(self, cfg):
        self.cfg = cfg

    # ---------- market / data quality ----------
    def check_market(self, snap, now=None):
        """snap: {status, server_time, ticker, last_closed_candle_ts, last_close} for one pair."""
        s = self.cfg["safety"]
        now = now or time.time()
        problems = []
        if snap.get("status") != "online":
            problems.append(f"exchange status is {snap.get('status')!r}, not 'online'")
        if abs(now - snap.get("server_time", 0)) > s["max_ticker_age_seconds"]:
            problems.append("exchange clock/data age outside tolerance (stale data or clock skew)")
        t = snap.get("ticker")
        if not t or min(t["bid"], t["ask"], t["last"]) <= 0 or t["bid"] >= t["ask"]:
            problems.append("invalid ticker (non-positive or crossed book)")
        else:
            mid = (t["bid"] + t["ask"]) / 2
            if (t["ask"] - t["bid"]) / mid > s["max_spread_pct"]:
                problems.append(f"spread {(t['ask'] - t['bid']) / mid:.2%} exceeds limit")
            if t["open_24h"] > 0 and abs(t["last"] / t["open_24h"] - 1) > s["max_24h_move_pct"]:
                problems.append(f"abnormal volatility: 24h move {t['last'] / t['open_24h'] - 1:+.1%}")
            lc = snap.get("last_close")
            if lc and abs(t["last"] / lc - 1) > s["max_ticker_vs_close_deviation_pct"]:
                problems.append("ticker deviates too far from last daily close (bad data?)")
        if now - snap.get("last_closed_candle_ts", 0) > 2 * DAY + 3600:
            problems.append("daily candles are stale")
        return problems

    # ---------- orders ----------
    def check_order(self, order, state, price, pair_info, now=None):
        """order: {asset, side, cad}. state: ledger state. Returns list of violations."""
        c = self.cfg
        now = now or time.time()
        o, lim = c["orders"], c["activity_limits"]
        v = []
        asset, side, cad = order["asset"], order["side"], order["cad"]
        fee = cad * o["assumed_fee_rate"]

        if asset not in c["universe"]:
            v.append(f"{asset} not in approved universe")
            return v
        if side not in ("buy", "sell"):
            v.append("invalid side")
            return v
        if state.get("paused_reason"):
            v.append(f"bot paused: {state['paused_reason']}")
        if cad > o["max_order_cad"] + 1e-9:
            v.append(f"order CAD {cad:.2f} exceeds max_order_cad {o['max_order_cad']}")
        if cad < o["min_order_cad"] - 1e-9:
            v.append(f"order CAD {cad:.2f} below min_order_cad {o['min_order_cad']}")
        volume = cad / price
        if volume < float(pair_info.get("ordermin", 0)):
            v.append(f"volume {volume:.8f} below exchange minimum {pair_info.get('ordermin')}")
        if cad < float(pair_info.get("costmin", 0) or 0):
            v.append("below exchange minimum cost")
        if o["assumed_fee_rate"] > o["max_fee_rate_per_order"]:
            v.append("fee rate above allowed maximum")
        if state["fees_paid_cad"] + fee > lim["max_cumulative_fees_cad"] + 1e-9:
            v.append(f"fee budget exhausted ({state['fees_paid_cad']:.2f} of {lim['max_cumulative_fees_cad']} CAD)")

        recent = [t for t in state["orders"] if t["status"] in ("filled", "submitted", "paper_filled")]
        if sum(1 for t in recent if now - t["ts"] < DAY) >= lim["max_orders_per_day"]:
            v.append("daily order limit reached")
        if sum(1 for t in recent if now - t["ts"] < 30 * DAY) >= lim["max_orders_per_30_days"]:
            v.append("30-day order limit reached")
        last = max((t["ts"] for t in recent if t["asset"] == asset), default=None)
        if last is not None and now - last < lim["min_days_between_trades_same_asset"] * DAY:
            v.append(f"traded {asset} too recently")

        cap = c["capital"]["max_capital_cad"]
        holdings_value = {a: q * state["prices"].get(a, 0) for a, q in state["holdings"].items()}
        equity = state["cash_cad"] + sum(holdings_value.values())
        if side == "buy":
            # The fee is paid out of `cad` (order volume is reduced), so `cad` is the total debit.
            if cad > state["cash_cad"] + 1e-9:
                v.append(f"insufficient bot cash ({state['cash_cad']:.2f} CAD) - bot never deposits")
            if state["cost_basis_cad"] + cad > cap + 1e-9:
                v.append(f"would exceed authorized capital of {cap} CAD")
            if equity < (1 - c["safety"]["drawdown_pause_pct"]) * cap:
                v.append("drawdown circuit breaker: new buys blocked pending human review")
            max_alloc = c["universe"][asset]["max_allocation"]
            if equity > 0 and (holdings_value.get(asset, 0) + cad) / equity > max_alloc + 0.02:
                v.append(f"{asset} allocation would exceed {max_alloc:.0%}")
        else:
            if volume > state["holdings"].get(asset, 0) * 1.0001:
                v.append("cannot sell more than held (no short selling)")
        return v
