"""Append-only event log (JSONL) plus a small state file. Every signal, order,
fee, error and portfolio change is written to the log before state is updated."""
import json
import os
import time

from .config import STATE_DIR


def default_state(cfg):
    return {
        "mode": "paper",
        "cash_cad": cfg["capital"]["max_capital_cad"],
        "holdings": {},
        "cost_basis_cad": 0.0,
        "fees_paid_cad": 0.0,
        "orders": [],
        "prices": {},
        "paused_reason": None,
        "consecutive_errors": 0,
        "started_ts": int(time.time()),
        "last_decision_ts": None,
        "last_summary_date": None,
    }


class Ledger:
    def __init__(self, cfg, mode="paper", state_dir=None):
        self.dir = state_dir or STATE_DIR
        self.dir.mkdir(parents=True, exist_ok=True)
        self.mode = mode
        self.log_path = self.dir / f"events_{mode}.jsonl"
        self.state_path = self.dir / f"state_{mode}.json"
        try:
            with open(self.state_path) as f:
                self.state = json.load(f)
        except FileNotFoundError:
            self.state = default_state(cfg)
            self.state["mode"] = mode
            self.event("init", capital_cad=self.state["cash_cad"])
            self.save()

    def event(self, kind, **data):
        rec = {"ts": int(time.time()), "iso": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
               "mode": self.mode, "kind": kind, **data}
        with open(self.log_path, "a") as f:
            f.write(json.dumps(rec) + "\n")
        return rec

    def save(self):
        tmp = self.state_path.with_suffix(".tmp")
        with open(tmp, "w") as f:
            json.dump(self.state, f, indent=2)
        os.replace(tmp, self.state_path)

    def apply_fill(self, asset, side, qty, price, fee_cad, order_id, status):
        s = self.state
        gross = qty * price
        held = s["holdings"].get(asset, 0.0)
        if side == "buy":
            s["cash_cad"] -= gross + fee_cad
            s["holdings"][asset] = held + qty
            s["cost_basis_cad"] += gross + fee_cad
        else:
            frac = qty / held if held else 1.0
            s["cash_cad"] += gross - fee_cad
            s["holdings"][asset] = max(0.0, held - qty)
            s["cost_basis_cad"] = max(0.0, s["cost_basis_cad"] * (1 - frac))
        s["fees_paid_cad"] += fee_cad
        rec = {"ts": int(time.time()), "asset": asset, "side": side, "qty": qty,
               "price": price, "fee_cad": fee_cad, "id": order_id, "status": status}
        existing = next((o for o in s["orders"] if o["id"] == order_id), None)
        if existing:  # live order recorded at submission: keep its timestamp, update the fill
            existing.update({k: v for k, v in rec.items() if k != "ts"})
        else:
            s["orders"].append(rec)
        self.event("fill", asset=asset, side=side, qty=qty, price=price, fee_cad=fee_cad,
                   order_id=order_id, status=status, cash_cad=s["cash_cad"],
                   holdings=s["holdings"], fees_paid_cad=s["fees_paid_cad"])

    def equity(self):
        s = self.state
        return s["cash_cad"] + sum(q * s["prices"].get(a, 0) for a, q in s["holdings"].items())

    def summary(self, cfg):
        s = self.state
        eq = self.equity()
        cap = cfg["capital"]["max_capital_cad"]
        lines = [f"[{self.mode.upper()}] Portfolio summary {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())}",
                 f"  Equity:      CAD {eq:8.2f}  ({eq / cap - 1:+.1%} vs CAD {cap:.2f} authorized)",
                 f"  Cash:        CAD {s['cash_cad']:8.2f}"]
        for a, q in s["holdings"].items():
            if q > 0:
                lines.append(f"  {a:<12} {q:.8f}  ~CAD {q * s['prices'].get(a, 0):8.2f}")
        lines += [f"  Fees paid:   CAD {s['fees_paid_cad']:8.2f} of {cfg['activity_limits']['max_cumulative_fees_cad']:.2f} budget",
                  f"  Orders:      {len(s['orders'])} total",
                  f"  Paused:      {s['paused_reason'] or 'no'}"]
        return "\n".join(lines)
