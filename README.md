# cryptobot: CAD $50 rules-based spot crypto bot (Kraken, paper by default)

**Read [`docs/PLAN.md`](docs/PLAN.md) first.** It has the platform research, backtest results, risk policy, setup guide, security checklist, and free-crypto research.

- Spot only, limit orders only. There is no leverage, margin, shorting, or martingale anywhere in the code.
- Hard cap of CAD $50. The client has no deposit or withdrawal methods at all.
- **Paper mode by default.** Live trading needs four separate gates: the config flag, a recorded approval of the exact risk-config hash, an environment-variable phrase, and no kill switch.
- Zero third-party dependencies (Python 3.10+ standard library only).

## Web apps

- **Local control panel:** `python -m cryptobot web`, then open http://127.0.0.1:8050. It shows your portfolio, orders, the full event log, backtests, the live-trading gates and your settings, and has buttons for "run one paper cycle" and the kill switch. It listens on loopback only, uses no JavaScript, and sends a strict CSP. Every action is a CSRF-protected POST. It **cannot** approve live trading, change limits or see API keys.
- **Hosted dashboard (claude.ai, private):** backtests, platform comparison, risk rules and free-crypto research, readable from any device. Rebuild its data with `python scripts/export_dashboard.py` after a new backtest.

```
python -m unittest discover -s tests       # 39 safety/integrity/web tests
python -m cryptobot web                    # local control panel on http://127.0.0.1:8050
python -m cryptobot fetch-data             # Kraken CAD + Coinbase USD daily history
python -m cryptobot backtest               # strategy comparison -> data/backtest_report.txt
python -m cryptobot run                    # one paper cycle (cron it hourly via scripts/run_bot.sh)
python -m cryptobot status                 # portfolio + live-gate status
python -m cryptobot kill | unkill          # emergency stop
python -m cryptobot approve                # explicit live-trading approval (after paper trading)
```

| File | Role |
|---|---|
| `config/config.json` | Risk limits and strategy (editing it revokes live approval) |
| `cryptobot/strategy.py` | Hold / scheduled entry / 200-day trend filter / rebalance |
| `cryptobot/risk.py` | Order and market-data safety checks (fail closed) |
| `cryptobot/bot.py` | One cycle: data → checks → signal → orders → ledger → alerts |
| `cryptobot/kraken.py` | Minimal Kraken REST client plus withdraw-permission canary |
| `cryptobot/backtest.py` | No-look-ahead daily backtester with fees and slippage |
| `cryptobot/web.py` | Local control panel (stdlib HTTP server, server-rendered SVG charts) |
| `scripts/export_dashboard.py` | Data files for the hosted dashboard (`data/dashboard/`) |

Not financial advice. You can lose the entire amount.

## Android: NetPilot (separate project)

[`android/netpilot/`](android/netpilot/README.md) is a Wi-Fi/cellular failover and diagnostics app for non-rooted Android. It does no bandwidth bonding; [`docs/DESIGN.md`](android/netpilot/docs/DESIGN.md) explains why device-wide bonding isn't possible without a server.
