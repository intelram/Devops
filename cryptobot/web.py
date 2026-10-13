"""Local control panel: python -m cryptobot web  ->  http://127.0.0.1:8050

Security model
- Binds to loopback only; requests whose Host header is not loopback are refused
  (blocks DNS-rebinding attacks from web pages you visit).
- Every state-changing request is a POST carrying a per-process CSRF token; Origin
  and Sec-Fetch-Site are checked when the browser sends them.
- No JavaScript at all. Strict CSP (no inline script or style), no framing, no referrer.
- All dynamic text is HTML-escaped. Flash messages come from a fixed list, never the URL.
- The panel can only run PAPER cycles and toggle the kill switch. It cannot approve
  live trading, edit risk limits, or see API keys: approval stays a deliberate
  terminal step (`python -m cryptobot approve`).
"""
import hmac
import html
import json
import math
import secrets
import threading
import time
import urllib.parse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import config as cfgmod
from .config import config_hash, live_trading_status, load_config, read_approval
from .data import DATA_DIR

CSRF_TOKEN = secrets.token_urlsafe(32)
RUN_LOCK = threading.Lock()
MIN_SECONDS_BETWEEN_WEB_RUNS = 30
_last_web_run = [0.0]

FLASH = {
    "ran": ("ok", "Paper cycle finished. See the latest events below."),
    "busy": ("warn", "A cycle is already running, or one ran less than 30 seconds ago. Try again shortly."),
    "failed": ("bad", "The paper cycle hit an error. The Activity page shows what happened."),
    "killed": ("warn", "Kill switch is ON. The bot will not trade and will cancel open orders on its next run."),
    "unkilled": ("ok", "Kill switch is OFF. Trading resumes on the next scheduled run, within your limits."),
}

CSS = """
:root{--bg:#f6f7f5;--panel:#ffffff;--fg:#17201c;--muted:#5d6b64;--line:#dde3df;--accent:#1f6f5c;
--accent-fg:#ffffff;--ok:#1d7a46;--warn:#9a6400;--bad:#b3261e;--chip:#eef2ef;
--s0:#1f6f5c;--s1:#c0631b;--s2:#5b5bd6;--s3:#a1366b;--s4:#6b7a73;
--mono:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;--sans:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
@media (prefers-color-scheme:dark){:root{--bg:#101513;--panel:#171e1b;--fg:#e4ebe7;--muted:#9aa9a1;--line:#2a3430;
--accent:#4fb39a;--accent-fg:#0d1311;--ok:#5cc58a;--warn:#e0a948;--bad:#ff8a80;--chip:#1f2824;
--s0:#4fb39a;--s1:#e58a46;--s2:#9a9cf2;--s3:#e07aa9;--s4:#8d9b94;color-scheme:dark}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 var(--sans)}
.wrap{max-width:72rem;margin-inline:auto;padding-inline:16px;padding-block:0 3rem}
header.top{display:flex;flex-wrap:wrap;gap:.75rem 1.5rem;align-items:center;justify-content:space-between;
padding-block:1rem;border-bottom:1px solid var(--line);margin-bottom:1.25rem}
.brand{font-weight:650;letter-spacing:.01em}
.brand small{display:block;font-weight:400;color:var(--muted);font-size:.8rem}
nav{display:flex;flex-wrap:wrap;gap:.25rem}
nav a{color:var(--fg);text-decoration:none;padding:.35rem .7rem;border-radius:6px}
nav a:hover{background:var(--chip)}
nav a[aria-current=page]{background:var(--accent);color:var(--accent-fg)}
a{color:var(--accent)}
a:focus-visible,button:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
h1{font-size:1.5rem;margin:0 0 .25rem;text-wrap:balance}
h2{font-size:1.05rem;margin:0 0 .6rem}
p.lede{color:var(--muted);margin:0 0 1.25rem;max-width:65ch}
.grid{display:grid;gap:1rem;grid-template-columns:repeat(auto-fit,minmax(min(100%,16rem),1fr))}
.grid.wide{grid-template-columns:repeat(auto-fit,minmax(min(100%,28rem),1fr))}
.card{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:1rem 1.1rem;min-width:0}
.stack{display:grid;gap:1rem}
.kpi .label{color:var(--muted);font-size:.8rem;text-transform:uppercase;letter-spacing:.06em}
.kpi .value{font:600 1.7rem/1.2 var(--mono);font-variant-numeric:tabular-nums;margin-top:.2rem}
.kpi .sub{color:var(--muted);font-size:.85rem;font-variant-numeric:tabular-nums}
.pos{color:var(--ok)}.neg{color:var(--bad)}
.statusbar{display:flex;flex-wrap:wrap;gap:.5rem;margin-bottom:1rem}
.pill{display:inline-flex;align-items:center;gap:.4rem;padding:.25rem .65rem;border-radius:999px;
background:var(--chip);font-size:.85rem;border:1px solid var(--line)}
.pill::before{content:"";width:.5rem;height:.5rem;border-radius:50%;background:var(--muted)}
.pill.ok::before{background:var(--ok)}.pill.warn::before{background:var(--warn)}.pill.bad::before{background:var(--bad)}
.flash{padding:.7rem 1rem;border-radius:8px;margin-bottom:1rem;border:1px solid var(--line);background:var(--panel)}
.flash.ok{border-color:var(--ok)}.flash.warn{border-color:var(--warn)}.flash.bad{border-color:var(--bad)}
.actions{display:flex;flex-wrap:wrap;gap:.6rem;align-items:center}
.actions form{margin:0}
button{font:inherit;padding:.5rem .95rem;border-radius:8px;border:1px solid var(--line);background:var(--panel);
color:var(--fg);cursor:pointer}
button:hover{border-color:var(--accent)}
button.primary{background:var(--accent);color:var(--accent-fg);border-color:var(--accent)}
button.danger{border-color:var(--bad);color:var(--bad)}
.note{color:var(--muted);font-size:.85rem;margin:.6rem 0 0;max-width:70ch}
.tablebox{overflow-x:auto}
table{border-collapse:collapse;width:100%;font-size:.88rem}
th,td{text-align:left;padding:.45rem .6rem;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--muted);font-weight:600;font-size:.78rem;text-transform:uppercase;letter-spacing:.05em}
td.num,th.num{text-align:right;font-variant-numeric:tabular-nums;font-family:var(--mono)}
td.mono{font-family:var(--mono);font-size:.8rem;word-break:break-word}
tr.hl td{background:var(--chip)}
ul.gates{list-style:none;padding:0;margin:0;display:grid;gap:.4rem}
ul.gates li{display:flex;gap:.5rem;align-items:flex-start}
.mark{font-family:var(--mono);font-weight:700;min-width:1.2rem}
.mark.ok{color:var(--ok)}.mark.bad{color:var(--bad)}
pre{background:var(--chip);padding:.8rem;border-radius:8px;overflow-x:auto;font:12.5px/1.45 var(--mono);margin:0}
.chips{display:flex;flex-wrap:wrap;gap:.35rem;margin-bottom:.8rem}
.chips a{text-decoration:none;color:var(--fg);padding:.2rem .6rem;border-radius:999px;border:1px solid var(--line);font-size:.82rem}
.chips a[aria-current=true]{background:var(--accent);color:var(--accent-fg);border-color:var(--accent)}
svg.chart{width:100%;height:auto;display:block}
svg .grid{stroke:var(--line);stroke-width:1}
svg .axis{fill:var(--muted);font:11px var(--sans)}
svg .ref{stroke:var(--muted);stroke-dasharray:4 4;stroke-width:1}
svg .line{fill:none;stroke-width:2;stroke-linejoin:round;stroke-linecap:round}
svg .line.dim{stroke-width:1.5;opacity:.45}svg .line.hl{stroke-width:3}
.legend li.hl{color:var(--fg);font-weight:600}
.cn{display:none}
@media (max-width:640px){.cw{display:none}.cn{display:block}}
svg .l0{stroke:var(--s0)}svg .l1{stroke:var(--s1)}svg .l2{stroke:var(--s2)}svg .l3{stroke:var(--s3)}svg .l4{stroke:var(--s4)}
svg .bar-bg{fill:var(--chip)}svg .bar-fg{fill:var(--accent)}svg .bar-fg.warn{fill:var(--warn)}svg .bar-fg.bad{fill:var(--bad)}
.legend{display:flex;flex-wrap:wrap;gap:.4rem 1rem;margin:.5rem 0 0;padding:0;list-style:none;font-size:.82rem;color:var(--muted)}
.legend li{display:flex;align-items:center;gap:.35rem}
.sw{width:.9rem;height:.2rem;border-radius:2px;display:inline-block}
.sw.l0{background:var(--s0)}.sw.l1{background:var(--s1)}.sw.l2{background:var(--s2)}.sw.l3{background:var(--s3)}.sw.l4{background:var(--s4)}
footer{color:var(--muted);font-size:.8rem;margin-top:2rem;border-top:1px solid var(--line);padding-top:1rem}
"""


def e(x):
    return html.escape("" if x is None else str(x), quote=True)


def fmt_cad(v):
    return f"CAD {v:,.2f}"


def fmt_ts(ts):
    return datetime.fromtimestamp(ts, timezone.utc).strftime("%Y-%m-%d %H:%M UTC")


# ---------------------------------------------------------------- data access (read-only)
def read_state(mode):
    try:
        with open(cfgmod.STATE_DIR / f"state_{mode}.json") as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return None


def read_events(mode, limit=5000):
    path = cfgmod.STATE_DIR / f"events_{mode}.jsonl"
    try:
        with open(path) as f:
            lines = f.readlines()[-limit:]
    except FileNotFoundError:
        return []
    out = []
    for ln in lines:
        try:
            out.append(json.loads(ln))
        except json.JSONDecodeError:
            continue
    return out


def equity_of(state):
    return state["cash_cad"] + sum(q * state["prices"].get(a, 0) for a, q in state["holdings"].items())


def read_json(name):
    try:
        with open(DATA_DIR / name) as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return None


# ---------------------------------------------------------------- charts (server-side SVG)
def nice_ticks(lo, hi, n=5):
    if hi <= lo:
        hi = lo + 1
    raw = (hi - lo) / n
    mag = 10 ** math.floor(math.log10(raw))
    step = next(m * mag for m in (1, 2, 2.5, 5, 10) if m * mag >= raw)
    start = math.floor(lo / step) * step
    ticks, t = [], start
    while t <= hi + step * 0.5:
        ticks.append(round(t, 10))
        t += step
    return ticks


def log_ticks(lo, hi):
    ticks = []
    for p in range(math.floor(math.log10(lo)), math.ceil(math.log10(hi)) + 1):
        for m in (1, 2, 5):
            v = m * 10 ** p
            if lo * 0.9 <= v <= hi * 1.1:
                ticks.append(v)
    return ticks


def line_chart(series, ref=None, log=False, height=260, label_fmt="{:,.0f}", highlight=None):
    """series: list of (name, [(date_iso, value), ...]). Draws a wide SVG for desktop and a
    narrow one for phones (CSS shows one), so labels stay readable at both sizes."""
    series = [(n, pts) for n, pts in series if pts]
    if not series:
        return '<p class="note">No data yet.</p>'
    wide = _svg(series, 760, height, ref, log, label_fmt, highlight, xlabels=3)
    narrow = _svg(series, 380, int(height * 0.85), ref, log, label_fmt, highlight, xlabels=2)
    legend = "".join(f'<li{" class=hl" if n == highlight else ""}><span class="sw l{i % 5}"></span>'
                     f'{e(n)}: {e(label_fmt.format(pts[-1][1]))}</li>' for i, (n, pts) in enumerate(series))
    return f'<div class="cw">{wide}</div><div class="cn">{narrow}</div><ul class="legend">{legend}</ul>'


def _svg(series, W, H, ref, log, label_fmt, highlight, xlabels):
    ml, mr, mt, mb = 52, 10, 10, 24
    dates = sorted({d for _, pts in series for d, _ in pts})
    idx = {d: i for i, d in enumerate(dates)}
    vals = [v for _, pts in series for _, v in pts] + ([ref] if ref else [])
    lo, hi = min(vals), max(vals)
    if log:
        lo = max(lo, 1e-6)
        ticks = log_ticks(lo, hi)
        lo, hi = min(lo, ticks[0]), max(hi, ticks[-1])
        fy = lambda v: mt + (H - mt - mb) * (1 - (math.log10(max(v, 1e-6)) - math.log10(lo)) / (math.log10(hi) - math.log10(lo)))
    else:
        pad = (hi - lo) * 0.08 or 1
        ticks = nice_ticks(lo - pad, hi + pad, n=5 if H > 200 else 4)
        lo, hi = ticks[0], ticks[-1]
        fy = lambda v: mt + (H - mt - mb) * (1 - (v - lo) / (hi - lo))
    span = max(len(dates) - 1, 1)
    fx = lambda d: ml + (W - ml - mr) * idx[d] / span
    parts = [f'<svg class="chart" viewBox="0 0 {W} {H}" role="img" aria-label="Line chart">']
    for t in ticks:
        y = fy(t)
        parts.append(f'<line class="grid" x1="{ml}" x2="{W - mr}" y1="{y:.1f}" y2="{y:.1f}"/>')
        parts.append(f'<text class="axis" x="{ml - 6}" y="{y + 4:.1f}" text-anchor="end">{e(label_fmt.format(t))}</text>')
    if ref:
        y = fy(ref)
        parts.append(f'<line class="ref" x1="{ml}" x2="{W - mr}" y1="{y:.1f}" y2="{y:.1f}"/>')
    picks = [dates[0], dates[-1]] if (xlabels == 2 or len(dates) < 3) else [dates[0], dates[len(dates) // 2], dates[-1]]
    picks = list(dict.fromkeys(picks))
    for i, d in enumerate(picks):
        anchor = "start" if i == 0 else "end" if i == len(picks) - 1 else "middle"
        parts.append(f'<text class="axis" x="{fx(d):.1f}" y="{H - 6}" text-anchor="{anchor}">{e(d)}</text>')
    order = sorted(range(len(series)), key=lambda i: series[i][0] == highlight)  # highlight drawn last, on top
    for i in order:
        name, pts = series[i]
        path = " ".join(f"{'M' if j == 0 else 'L'}{fx(d):.1f},{fy(v):.1f}" for j, (d, v) in enumerate(pts))
        cls = "hl" if name == highlight else ("dim" if highlight else "")
        parts.append(f'<path class="line l{i % 5} {cls}" d="{path}"><title>{e(name)}</title></path>')
    parts.append("</svg>")
    return "".join(parts)


def meter(value, limit):
    frac = 0 if limit <= 0 else min(value / limit, 1)
    cls = "bad" if frac >= 0.9 else "warn" if frac >= 0.6 else ""
    return (f'<svg class="chart" viewBox="0 0 200 8" role="img" aria-label="{frac:.0%} used">'
            f'<rect class="bar-bg" x="0" y="0" width="200" height="8" rx="4"/>'
            f'<rect class="bar-fg {cls}" x="0" y="0" width="{200 * frac:.1f}" height="8" rx="4"/></svg>')


# ---------------------------------------------------------------- pages
def page(title, active, body, flash=None):
    nav = "".join(f'<a href="{href}"{" aria-current=page" if key == active else ""}>{label}</a>'
                  for key, href, label in (("overview", "/", "Overview"), ("activity", "/activity", "Activity"),
                                           ("backtests", "/backtests", "Backtests"), ("settings", "/settings", "Settings")))
    fl = ""
    if flash in FLASH:
        kind, msg = FLASH[flash]
        fl = f'<div class="flash {kind}" role="status">{e(msg)}</div>'
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{e(title)} · cryptobot</title><link rel="stylesheet" href="/static/app.css"></head>
<body><div class="wrap"><header class="top"><div class="brand">cryptobot control panel
<small>Local only · 127.0.0.1 · CAD 50 spot bot</small></div><nav aria-label="Main">{nav}</nav></header>
{fl}{body}<footer>Runs on your computer only. This panel can run paper cycles and use the kill switch.
Live trading is approved in the terminal with <code>python -m cryptobot approve</code>.
Crypto can lose all of its value.</footer></div></body></html>"""


def csrf_form(action, label, cls=""):
    return (f'<form method="post" action="{e(action)}"><input type="hidden" name="csrf" value="{e(CSRF_TOKEN)}">'
            f'<button type="submit" class="{e(cls)}">{e(label)}</button></form>')


def gates_html(cfg):
    allowed, reasons = live_trading_status(cfg)
    checks = [
        ("Live trading switched on in config.json", "config.live_trading_enabled is false"),
        ("Authorization phrase set in the environment", "env CRYPTOBOT_LIVE"),
        ("Kill switch is off", "kill switch present"),
        ("Current risk limits approved in the terminal", "approval"),
    ]
    items = []
    for label, needle in checks:
        failed = any(needle in r for r in reasons)
        items.append(f'<li><span class="mark {"bad" if failed else "ok"}">{"✗" if failed else "✓"}</span>'
                     f'<span>{e(label)}</span></li>')
    head = ('<span class="pill ok">Live trading allowed</span>' if allowed
            else '<span class="pill">Paper only: live trading is locked</span>')
    return f'{head}<ul class="gates">{"".join(items)}</ul>'


def pick_mode(query):
    m = query.get("mode", [""])[0]
    if m in ("paper", "live"):
        return m
    return "live" if read_state("live") else "paper"


def overview(cfg, query, flash):
    mode = pick_mode(query)
    st = read_state(mode)
    cap = cfg["capital"]["max_capital_cad"]
    kill = cfgmod.kill_active()
    status = [f'<span class="pill {"warn" if mode == "live" else "ok"}">{e(mode.title())} portfolio</span>',
              f'<span class="pill {"bad" if kill else "ok"}">Kill switch {"ON" if kill else "off"}</span>']
    other = "live" if mode == "paper" else "paper"
    switch = (f'<a href="/?mode={other}">Show {other} portfolio</a>' if read_state(other) else "")
    if st:
        status.append(f'<span class="pill {"bad" if st.get("paused_reason") else "ok"}">'
                      f'{"Paused: " + e(st["paused_reason"]) if st.get("paused_reason") else "Not paused"}</span>')
    nxt = next_decision(cfg)
    status.append(f'<span class="pill">Next strategy check: {e(nxt)}</span>')

    if not st:
        kpis = (f'<div class="card"><h2>No {e(mode)} runs yet</h2><p class="note">Run one paper cycle to create the '
                f'paper portfolio with CAD {cap:.2f} of simulated cash.</p></div>')
        chart = ""
        orders_tbl = ""
        signal = ""
    else:
        eq = equity_of(st)
        chg = eq / cap - 1
        lim = cfg["activity_limits"]["max_cumulative_fees_cad"]
        holdings = "".join(f'<div class="sub">{e(a)} {q:.8f} ≈ {e(fmt_cad(q * st["prices"].get(a, 0)))}</div>'
                           for a, q in st["holdings"].items() if q > 0) or '<div class="sub">No crypto held</div>'
        kpis = f"""<div class="grid">
<div class="card kpi"><div class="label">Portfolio value</div><div class="value">{e(fmt_cad(eq))}</div>
<div class="sub {"pos" if chg >= 0 else "neg"}">{chg:+.1%} vs CAD {cap:.2f} starting amount</div></div>
<div class="card kpi"><div class="label">Cash</div><div class="value">{e(fmt_cad(st["cash_cad"]))}</div>
<div class="sub">The bot never adds money</div></div>
<div class="card kpi"><div class="label">Holdings</div>{holdings}</div>
<div class="card kpi"><div class="label">Fees paid</div><div class="value">{e(fmt_cad(st["fees_paid_cad"]))}</div>
<div class="sub">of CAD {lim:.2f} fee budget</div>{meter(st["fees_paid_cad"], lim)}</div></div>"""
        pts = [(datetime.fromtimestamp(ev["ts"], timezone.utc).strftime("%Y-%m-%d %H:%M"), ev["equity"])
               for ev in read_events(mode) if ev.get("kind") == "snapshot" and "equity" in ev]
        chart = (f'<div class="card"><h2>Portfolio value over time</h2>'
                 f'{line_chart([("Value (CAD)", pts)], ref=cap, label_fmt="{:,.2f}")}'
                 f'<p class="note">Dashed line: CAD {cap:.2f} starting amount. One point per bot run.</p></div>'
                 if len(pts) > 1 else
                 '<div class="card"><h2>Portfolio value over time</h2><p class="note">The chart appears after two or more runs.</p></div>')
        last_sig = next((ev for ev in reversed(read_events(mode)) if ev.get("kind") == "signal"), None)
        if last_sig:
            tg = ", ".join(f"{a} {w:.0%}" for a, w in last_sig.get("targets", {}).items()) or "all cash"
            planned = "; ".join(f'{o["side"]} {o["asset"]} CAD {o["cad"]:.2f}' for o in last_sig.get("planned_orders", [])) or "no trades needed"
            signal = (f'<div class="card"><h2>Latest strategy decision</h2><p>{e(last_sig["iso"])}: target {e(tg)}. '
                      f'Planned: {e(planned)}.</p><p class="note">Strategy: {e(last_sig.get("strategy"))}. '
                      f'Every planned order still has to pass the risk checks.</p></div>')
        else:
            signal = ('<div class="card"><h2>Latest strategy decision</h2><p class="note">No decision yet. '
                      'The strategy only decides on its weekly check day; other runs update prices and safety checks.</p></div>')
        rows = "".join(f'<tr><td>{e(fmt_ts(o["ts"]))}</td><td>{e(o["side"])}</td><td>{e(o["asset"])}</td>'
                       f'<td class="num">{o["qty"]:.8f}</td><td class="num">{o["price"]:,.2f}</td>'
                       f'<td class="num">{o["fee_cad"]:.2f}</td><td>{e(o["status"])}</td></tr>'
                       for o in reversed(st["orders"][-20:]))
        orders_tbl = (f'<div class="card"><h2>Orders</h2><div class="tablebox"><table><thead><tr><th>Time</th><th>Side</th>'
                      f'<th>Asset</th><th class="num">Quantity</th><th class="num">Price CAD</th><th class="num">Fee CAD</th>'
                      f'<th>Status</th></tr></thead><tbody>{rows or "<tr><td colspan=7>No orders yet.</td></tr>"}</tbody>'
                      f'</table></div></div>')

    kill_btn = (csrf_form("/action/unkill", "Turn kill switch off") if kill
                else csrf_form("/action/kill", "Turn kill switch ON", "danger"))
    controls = f"""<div class="card"><h2>Controls</h2><div class="actions">
{csrf_form("/action/run-paper", "Run one paper cycle", "primary")}{kill_btn}</div>
<p class="note">A paper cycle fetches live Kraken prices and simulates orders. No real money moves.
The kill switch also stops live trading, if you ever enable it.</p></div>"""
    gates = f'<div class="card"><h2>Live trading gates</h2>{gates_html(cfg)}' \
            f'<p class="note">All four must pass before any real order. See docs/PLAN.md §6.</p></div>'
    body = f"""<h1>Overview</h1><p class="lede">What the bot holds, what it decided last, and whether it is allowed to trade. {switch}</p>
<div class="statusbar">{"".join(status)}</div><div class="stack">{kpis}
<div class="grid wide">{controls}{gates}</div>{chart}{signal}{orders_tbl}</div>"""
    return page("Overview", "overview", body, flash)


def next_decision(cfg):
    if cfg["strategy"]["name"] not in ("trend_filter", "rebalance"):
        return "every run"
    wd = cfg["strategy"]["check_weekday"]
    names = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
    today = datetime.now(timezone.utc).weekday()
    return "today (UTC)" if today == wd else f"{names[wd]} (UTC)"


def activity(cfg, query, flash):
    mode = pick_mode(query)
    kind = query.get("kind", [""])[0]
    evs = read_events(mode)
    kinds = sorted({ev.get("kind", "?") for ev in evs})
    chips = [f'<a href="/activity?mode={e(mode)}"{" aria-current=true" if not kind else ""}>All</a>']
    chips += [f'<a href="/activity?mode={e(mode)}&amp;kind={e(urllib.parse.quote(k))}"'
              f'{" aria-current=true" if k == kind else ""}>{e(k)}</a>' for k in kinds]
    shown = [ev for ev in reversed(evs) if not kind or ev.get("kind") == kind][:300]
    important = {"order_rejected", "error", "market_unsafe", "security_violation", "kill_switch", "paused", "live_refused"}
    rows = []
    for ev in shown:
        detail = {k: v for k, v in ev.items() if k not in ("ts", "iso", "mode", "kind")}
        txt = json.dumps(detail, separators=(", ", ": "))
        rows.append(f'<tr{" class=hl" if ev.get("kind") in important else ""}><td>{e(ev.get("iso"))}</td>'
                    f'<td>{e(ev.get("kind"))}</td><td class="mono">{e(txt[:600])}{"…" if len(txt) > 600 else ""}</td></tr>')
    body = f"""<h1>Activity</h1><p class="lede">Every signal, order, fee, error and safety pause the bot recorded
({e(mode)} log, newest first, up to 300 rows). Highlighted rows are refusals, errors and pauses.</p>
<div class="chips">{"".join(chips)}</div><div class="card tablebox"><table><thead><tr><th>Time (UTC)</th><th>Event</th>
<th>Details</th></tr></thead><tbody>{"".join(rows) or "<tr><td colspan=3>No events yet.</td></tr>"}</tbody></table></div>"""
    return page("Activity", "activity", body, flash)


SCEN_KEYS = {"maker": "maker (0.40% fee + 0.05% slip)", "taker": "taker (0.80% fee + 0.15% slip)",
             "app": "app/instant (1.00% fee + 0.50% spread)"}


def backtests(cfg, query, flash):
    res = read_json("backtest_results.json")
    curves = read_json("backtest_curves.json") or []
    if not res:
        body = ('<h1>Backtests</h1><div class="card"><p>No results yet. Run <code>python -m cryptobot fetch-data</code> '
                'then <code>python -m cryptobot backtest</code>.</p></div>')
        return page("Backtests", "backtests", body, flash)
    scen = query.get("fees", ["taker"])[0]
    scen = scen if scen in SCEN_KEYS else "taker"
    chips = "".join(f'<a href="/backtests?fees={k}"{" aria-current=true" if k == scen else ""}>{e(v)}</a>'
                    for k, v in SCEN_KEYS.items())
    sections = []
    for block, ckey, log in ((res[0], "kraken_cad", False), (res[1], "long_usd", True)):
        rows = [r for r in block["rows"] if r["scenario"] == SCEN_KEYS[scen]]
        trs = "".join(
            f'<tr{" class=hl" if r["strategy"].startswith("Trend filter BTC (200") else ""}><td>{e(r["strategy"])}</td>'
            f'<td class="num">{r["final_cad"]:,.2f}</td><td class="num {"pos" if r["return_pct"] >= 0 else "neg"}">{r["return_pct"]:+,.1f}%</td>'
            f'<td class="num">{r["max_drawdown_pct"]:.1f}%</td><td class="num">{r["trades"]}</td>'
            f'<td class="num">{r["fees_cad"]:.2f}</td><td>{e(r.get("fee_budget_hit") or "no")}</td></tr>' for r in rows)
        series = {}
        for c in curves:
            if c["dataset"] == ckey:
                series.setdefault(c["strategy"], []).append((c["date"], c["equity"]))
        chart = line_chart(list(series.items()), ref=50, log=log, label_fmt="{:,.0f}",
                           highlight="Trend filter BTC (200d SMA, weekly)")
        note = ("Log scale. USD prices used as a stand-in for CAD." if log else "Actual Kraken CAD prices.")
        sections.append(f"""<div class="card"><h2>{e(block["dataset"])}</h2>
<p class="note">{e(rows[0]["start"])} to {e(rows[0]["end"])}. Start CAD 50, no added money. {e(note)}
Equity chart uses taker fees.</p>{chart}<div class="tablebox"><table><thead><tr><th>Strategy</th>
<th class="num">Final</th><th class="num">Return</th><th class="num">Worst drop</th><th class="num">Trades</th>
<th class="num">Fees</th><th>Fee budget hit</th></tr></thead><tbody>{trs}</tbody></table></div></div>""")
    roll = res[2]["rows"] if len(res) > 2 else []
    rtab = ""
    if roll:
        names = [k for k in roll[0] if k != "start"]
        bench = names[0]
        trs = []
        for k in names:
            rets = sorted(r[k]["ret"] for r in roll)
            trs.append(f'<tr{" class=hl" if k.startswith("Trend filter BTC (200") else ""}><td>{e(k)}</td>'
                       f'<td class="num">{rets[len(rets) // 2]:+.1f}%</td><td class="num neg">{rets[0]:+.1f}%</td>'
                       f'<td class="num">{sum(1 for r in roll if r[k]["mdd"] > r[bench]["mdd"])}/{len(roll)}</td>'
                       f'<td class="num">{sum(1 for x in rets if x < 0)}/{len(roll)}</td></tr>')
        rtab = f"""<div class="card"><h2>Every 2-year window since 2017 ({len(roll)} windows, taker fees)</h2>
<div class="tablebox"><table><thead><tr><th>Strategy</th><th class="num">Median return</th><th class="num">Worst return</th>
<th class="num">Smaller worst drop than buy &amp; hold</th><th class="num">Windows that lost money</th></tr></thead>
<tbody>{"".join(trs)}</tbody></table></div></div>"""
    body = f"""<h1>Backtests</h1><p class="lede">Historical simulations with fees and slippage. The highlighted row is the
recommended strategy. Past results do not predict future ones.</p><div class="chips">{chips}</div>
<div class="stack">{"".join(sections)}{rtab}</div>"""
    return page("Backtests", "backtests", body, flash)


def settings(cfg, query, flash):
    appr = read_approval()
    shown = {k: v for k, v in cfg.items() if not k.startswith("_")}
    appr_txt = (f"Approved by {appr.get('approved_by')} at {appr.get('approved_at')} for config "
                f"{appr.get('config_hash', '')[:16]}" if appr else "No approval recorded.")
    match = appr and appr.get("config_hash") == config_hash(cfg)
    body = f"""<h1>Settings</h1><p class="lede">Read-only view of your risk limits. To change one, edit
<code>config/config.json</code>. Any change cancels live-trading approval until you approve again in the terminal.</p>
<div class="stack"><div class="grid wide"><div class="card"><h2>Approval</h2><p>{e(appr_txt)}</p>
<p><span class="pill {"ok" if match else "warn"}">{"Matches current limits" if match else "Does not match current limits"}</span></p>
<p class="note">Current config fingerprint: <code>{e(config_hash(cfg)[:16])}</code></p></div>
<div class="card"><h2>Where things are</h2><ul class="note"><li>Config: {e(cfgmod.CONFIG_PATH)}</li>
<li>State and logs: {e(cfgmod.STATE_DIR)}</li><li>Kill switch file: {e(cfgmod.KILL_SWITCH)}</li></ul>
<p class="note">API keys are never shown here. They live only in your env file outside the repo.</p></div></div>
<div class="card"><h2>Risk limits</h2><pre>{e(json.dumps(shown, indent=2))}</pre></div></div>"""
    return page("Settings", "settings", body, flash)


# ---------------------------------------------------------------- HTTP
SECURITY_HEADERS = {
    "Content-Security-Policy": "default-src 'none'; style-src 'self'; img-src 'self' data:; form-action 'self'; "
                               "frame-ancestors 'none'; base-uri 'none'",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "Cache-Control": "no-store",
    "Cross-Origin-Opener-Policy": "same-origin",
    "Cross-Origin-Resource-Policy": "same-origin",
}


def make_handler(port, cfg_loader=load_config, runner=None):
    allowed_hosts = {f"127.0.0.1:{port}", f"localhost:{port}", f"[::1]:{port}"}
    allowed_origins = {f"http://{h}" for h in allowed_hosts}

    def default_runner(cfg):
        from .bot import run_once
        run_once(cfg, want_live=False)  # the panel can only ever run PAPER cycles

    run = runner or default_runner

    class Handler(BaseHTTPRequestHandler):
        server_version = "cryptobot"
        sys_version = ""

        def log_message(self, fmt, *args):  # keep the console quiet but useful
            print(f"[web] {self.command} {self.path.split('?')[0]} -> {args[1] if len(args) > 1 else ''}")

        def send(self, code, body, ctype="text/html; charset=utf-8", extra=None):
            data = body.encode() if isinstance(body, str) else body
            self.send_response(code)
            for k, v in {**SECURITY_HEADERS, "Content-Type": ctype, "Content-Length": str(len(data)), **(extra or {})}.items():
                self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(data)

        def host_ok(self):
            if self.headers.get("Host", "") not in allowed_hosts:
                self.send(400, "Bad Host header. Open http://127.0.0.1:%d/ directly." % port, "text/plain; charset=utf-8")
                return False
            return True

        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            if not self.host_ok():
                return
            u = urllib.parse.urlsplit(self.path)
            q = urllib.parse.parse_qs(u.query)
            flash = q.get("msg", [""])[0]
            try:
                cfg = cfg_loader()
                if u.path == "/":
                    self.send(200, overview(cfg, q, flash))
                elif u.path == "/activity":
                    self.send(200, activity(cfg, q, flash))
                elif u.path == "/backtests":
                    self.send(200, backtests(cfg, q, flash))
                elif u.path == "/settings":
                    self.send(200, settings(cfg, q, flash))
                elif u.path == "/static/app.css":
                    self.send(200, CSS, "text/css; charset=utf-8")
                elif u.path == "/api/status.json":
                    st = {m: read_state(m) for m in ("paper", "live")}
                    allowed, reasons = live_trading_status(cfg)
                    self.send(200, json.dumps({
                        "kill_switch": cfgmod.kill_active(), "live_allowed": allowed, "live_blockers": reasons,
                        "portfolios": {m: ({"equity_cad": round(equity_of(s), 2), "cash_cad": round(s["cash_cad"], 2),
                                            "holdings": s["holdings"], "fees_paid_cad": round(s["fees_paid_cad"], 2),
                                            "paused_reason": s.get("paused_reason")} if s else None)
                                       for m, s in st.items()}}, indent=2), "application/json")
                else:
                    self.send(404, page("Not found", "", '<h1>Not found</h1><p><a href="/">Back to overview</a></p>'))
            except Exception as ex:  # never leak a traceback to the page
                print(f"[web] error rendering {u.path}: {ex!r}")
                self.send(500, page("Error", "", "<h1>Something went wrong</h1><p>The terminal running the panel "
                                                 "shows the error. Check config/config.json is valid.</p>"))

        def do_POST(self):
            if not self.host_ok():
                return
            origin = self.headers.get("Origin")
            if origin and origin not in allowed_origins:
                return self.send(403, "Cross-origin request refused.", "text/plain; charset=utf-8")
            if self.headers.get("Sec-Fetch-Site", "same-origin") not in ("same-origin", "none"):
                return self.send(403, "Cross-site request refused.", "text/plain; charset=utf-8")
            length = int(self.headers.get("Content-Length") or 0)
            if length > 4096:
                return self.send(413, "Request too large.", "text/plain; charset=utf-8")
            form = urllib.parse.parse_qs(self.rfile.read(length).decode("utf-8", "replace"))
            if not hmac.compare_digest(form.get("csrf", [""])[0], CSRF_TOKEN):
                return self.send(403, "Missing or wrong form token. Reload the page and try again.",
                                 "text/plain; charset=utf-8")
            path = urllib.parse.urlsplit(self.path).path
            if path == "/action/kill":
                cfgmod.set_kill("web panel")
                msg = "killed"
            elif path == "/action/unkill":
                cfgmod.clear_kill()
                msg = "unkilled"
            elif path == "/action/run-paper":
                msg = "busy"
                if time.time() - _last_web_run[0] >= MIN_SECONDS_BETWEEN_WEB_RUNS and RUN_LOCK.acquire(blocking=False):
                    try:
                        _last_web_run[0] = time.time()
                        run(cfg_loader())
                        msg = "ran"
                    except Exception as ex:
                        print(f"[web] paper cycle failed: {ex!r}")
                        msg = "failed"
                    finally:
                        RUN_LOCK.release()
            else:
                return self.send(404, "Unknown action.", "text/plain; charset=utf-8")
            self.send(303, "", extra={"Location": f"/?mode=paper&msg={msg}"})

    return Handler


def serve(port=8050):
    httpd = ThreadingHTTPServer(("127.0.0.1", port), make_handler(port))
    print(f"cryptobot control panel: http://127.0.0.1:{port}/  (loopback only, Ctrl+C to stop)")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
