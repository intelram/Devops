"""One bot cycle. Designed to be run by cron (e.g. hourly); each run is stateless
apart from the ledger, so a crash never leaves a loop spinning."""
import json
import os
import time
import urllib.request
import uuid
from datetime import datetime, timezone

from . import strategy
from .config import KILL_SWITCH, live_trading_status
from .kraken import KrakenError, KrakenPrivate, KrakenPublic
from .ledger import Ledger
from .risk import RiskEngine

KRAKEN_BALANCE_CODES = {"BTC": ("XXBT", "XBT"), "ETH": ("XETH", "ETH")}


def alert(msg):
    print(msg)
    topic = os.environ.get("CRYPTOBOT_NTFY_TOPIC")
    if topic:
        try:
            urllib.request.urlopen(urllib.request.Request(
                f"https://ntfy.sh/{topic}", data=msg.encode()[:3500], method="POST"), timeout=10)
        except Exception as e:  # alerts must never crash the bot
            print(f"(alert delivery failed: {e})")


def gather_market(cfg, pub):
    status = pub.system_status()
    server_time = pub.server_time()
    snaps = {}
    for asset, u in cfg["universe"].items():
        ohlc = pub.daily_ohlc(u["pair"])
        closed = ohlc[:-1]  # last row is the in-progress candle
        snaps[asset] = {
            "status": status, "server_time": server_time, "ticker": pub.ticker(u["pair"]),
            "closes": [r[4] for r in closed], "last_close": closed[-1][4],
            "last_closed_candle_ts": closed[-1][0] + 86400, "pair_info": pub.asset_pair(u["pair"]),
        }
    return snaps


def reconcile_live(cfg, ledger, priv):
    """Apply fills of previously submitted live orders and verify exchange balances."""
    s = ledger.state
    still_pending = []
    for p in s.get("pending", []):
        info = priv.query_orders([p["txid"]])[p["txid"]]
        if info["status"] in ("open", "pending"):
            still_pending.append(p)
            continue
        vol = float(info.get("vol_exec", 0))
        if vol > 0:
            ledger.apply_fill(p["asset"], p["side"], vol, float(info["price"]), float(info["fee"]),
                              p["txid"], "filled")
        else:
            for rec in s["orders"]:
                if rec["id"] == p["txid"]:
                    rec["status"] = info["status"]  # e.g. expired/canceled: no fill, no fee
            s["last_decision_ts"] = None  # allow a retry on a later run of the same decision day
        ledger.event("order_closed", txid=p["txid"], status=info["status"], vol_exec=vol)
    s["pending"] = still_pending
    bal = priv.balance()
    for asset, qty in s["holdings"].items():
        on_exchange = sum(bal.get(code, 0.0) for code in KRAKEN_BALANCE_CODES.get(asset, (asset,)))
        if on_exchange + 1e-9 < qty:
            s["paused_reason"] = f"reconciliation mismatch for {asset}: ledger {qty} > exchange {on_exchange}"
            ledger.event("error", message=s["paused_reason"])
    cad = bal.get("ZCAD", 0.0)
    if cad + 1e-6 < s["cash_cad"] - sum(p["cad"] for p in still_pending if p["side"] == "buy"):
        # The bot never deposits; it simply cannot spend money that is not there.
        s["cash_cad"] = cad
        ledger.event("cash_clamped_to_exchange_balance", cash_cad=cad)


def run_once(cfg, want_live=False, now=None):
    now = now or time.time()
    allowed, reasons = live_trading_status(cfg)
    live = want_live and allowed
    ledger = Ledger(cfg, mode="live" if live else "paper")
    s = ledger.state
    if want_live and not allowed:
        ledger.event("live_refused", reasons=reasons)
        print("Live trading NOT enabled -> running in PAPER mode. Reasons:\n  - " + "\n  - ".join(reasons))

    if KILL_SWITCH.exists():
        ledger.event("kill_switch", action="no trading this run")
        if live:
            try:
                KrakenPrivate().cancel_all()
            except KrakenError as e:
                ledger.event("error", message=f"cancel_all failed: {e}")
        ledger.save()
        alert("Kill switch active: bot did not trade.")
        return ledger

    risk = RiskEngine(cfg)
    pub = KrakenPublic()
    try:
        snaps = gather_market(cfg, pub)
        priv = None
        if live:
            priv = KrakenPrivate()
            if priv.key_can_withdraw():
                s["paused_reason"] = "API key has WITHDRAW permission - refusing to trade. Create a trade-only key."
                ledger.event("security_violation", message=s["paused_reason"])
                ledger.save()
                alert(s["paused_reason"])
                return ledger
            reconcile_live(cfg, ledger, priv)
        s["consecutive_errors"] = 0
    except (KrakenError, KeyError, ValueError, StopIteration) as e:
        s["consecutive_errors"] += 1
        ledger.event("error", message=f"data/connectivity failure: {e}", count=s["consecutive_errors"])
        if s["consecutive_errors"] >= cfg["safety"]["max_consecutive_errors"]:
            s["paused_reason"] = f"{s['consecutive_errors']} consecutive connectivity/data errors"
            alert(f"Bot paused: {s['paused_reason']}")
        ledger.save()
        return ledger

    for asset, snap in snaps.items():
        s["prices"][asset] = snap["ticker"]["last"]
    ledger.event("snapshot", prices=s["prices"], equity=ledger.equity())

    problems = {a: risk.check_market(sn, now) for a, sn in snaps.items()}
    problems = {a: p for a, p in problems.items() if p}
    if problems:
        ledger.event("market_unsafe", problems=problems)
        alert(f"Trading skipped - unsafe market conditions: {json.dumps(problems)}")
    elif s.get("paused_reason"):
        ledger.event("paused", reason=s["paused_reason"])
    elif s.get("pending"):
        ledger.event("waiting_for_pending_orders", count=len(s["pending"]))
    elif strategy.is_decision_day(cfg, now, s.get("last_decision_ts")):
        decide_and_execute(cfg, ledger, risk, snaps, priv, now)

    maybe_daily_summary(cfg, ledger, now)
    ledger.save()
    return ledger


def decide_and_execute(cfg, ledger, risk, snaps, priv, now):
    s = ledger.state
    ctx = {"closes": {a: sn["closes"] for a, sn in snaps.items()},
           "days_since_start": int((now - s["started_ts"]) // 86400)}
    targets = strategy.target_weights(cfg, ctx)
    holdings_value = {a: q * s["prices"][a] for a, q in s["holdings"].items()}
    orders = strategy.plan_trades(cfg, targets, holdings_value, s["cash_cad"], s["prices"])
    ledger.event("signal", strategy=cfg["strategy"]["name"], targets=targets, planned_orders=orders)
    s["last_decision_ts"] = now
    slip = cfg["orders"]["max_slippage_vs_mid"] / 5  # paper fills: conservative 0.1% past the touch

    for o in orders:
        snap = snaps[o["asset"]]
        t = snap["ticker"]
        if o["side"] == "sell":
            o["cad"] = min(o["cad"], s["holdings"].get(o["asset"], 0) * t["bid"])
        violations = risk.check_order(o, s, t["ask"] if o["side"] == "buy" else t["bid"],
                                      snap["pair_info"], now)
        if violations:
            ledger.event("order_rejected", order=o, violations=violations)
            continue
        if priv is None:  # paper
            px = t["ask"] * (1 + slip) if o["side"] == "buy" else t["bid"] * (1 - slip)
            qty = (o["cad"] / px) if o["side"] == "buy" else min(o["cad"] / px, s["holdings"][o["asset"]])
            fee = qty * px * cfg["orders"]["assumed_fee_rate"]
            if o["side"] == "buy":
                qty = (o["cad"] - fee) / px  # fee comes out of the order budget
            ledger.apply_fill(o["asset"], o["side"], qty, px, fee, f"paper-{uuid.uuid4().hex[:12]}",
                              "paper_filled")
        else:
            px = t["bid"] if o["side"] == "buy" else t["ask"]  # post-only: rest at the touch (maker)
            fee_est = o["cad"] * cfg["orders"]["assumed_fee_rate"]
            qty = round(((o["cad"] - fee_est) if o["side"] == "buy" else o["cad"]) / px, 8)
            if o["side"] == "sell":
                qty = min(qty, s["holdings"][o["asset"]])
            pair = cfg["universe"][o["asset"]]["pair"]
            decimals = int(snap["pair_info"].get("pair_decimals", 1))
            res = priv.add_limit_order(pair, o["side"], qty, round(px, decimals),
                                       post_only=True, ttl_seconds=cfg["orders"]["limit_order_ttl_seconds"],
                                       client_id=uuid.uuid4().hex)
            txid = res["txid"][0]
            s.setdefault("pending", []).append({"txid": txid, "asset": o["asset"], "side": o["side"],
                                                "cad": o["cad"]})
            s["orders"].append({"ts": int(now), "asset": o["asset"], "side": o["side"], "qty": qty,
                                "price": px, "fee_cad": 0.0, "id": txid, "status": "submitted"})
            ledger.event("order_submitted", txid=txid, order=o, qty=qty, limit_price=px)
            alert(f"LIVE order submitted: {o['side']} {qty} {o['asset']} @ {px} CAD (post-only)")


def maybe_daily_summary(cfg, ledger, now):
    s = ledger.state
    d = datetime.fromtimestamp(now, timezone.utc)
    if d.hour >= cfg["alerts"]["daily_summary_hour_utc"] and s.get("last_summary_date") != d.date().isoformat():
        s["last_summary_date"] = d.date().isoformat()
        text = ledger.summary(cfg)
        ledger.event("daily_summary", text=text)
        alert(text)
