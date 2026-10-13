"""CLI: python -m cryptobot <command>

  run [--live]       one bot cycle (paper unless every live gate passes)
  status             portfolio summary + live-gate status
  approve            record explicit approval of the CURRENT risk config for live trading
  kill / unkill      emergency stop switch (cancels open orders on next run, blocks trading)
  web [--port 8050]  local control panel at http://127.0.0.1:8050 (loopback only)
  fetch-data         download historical daily candles into data/
  backtest           run the strategy comparison on cached data
"""
import getpass
import json
import sys
import time

from . import config as cfgmod
from .config import config_hash, live_trading_status, load_config, write_approval


def main(argv=None):
    argv = argv or sys.argv[1:]
    cmd = argv[0] if argv else "status"
    cfg = load_config()

    if cmd == "run":
        from .bot import run_once
        led = run_once(cfg, want_live="--live" in argv)
        print(led.summary(cfg))
    elif cmd == "status":
        from .ledger import Ledger
        allowed, reasons = live_trading_status(cfg)
        print(f"Config hash: {config_hash(cfg)[:16]}")
        print(f"Live trading allowed: {allowed}")
        for r in reasons:
            print(f"  - {r}")
        for mode in ("paper", "live"):
            if (cfgmod.STATE_DIR / f"state_{mode}.json").exists():
                print(Ledger(cfg, mode).summary(cfg))
    elif cmd == "approve":
        print(json.dumps({k: cfg[k] for k in ("capital", "universe", "strategy", "orders",
                                              "activity_limits", "safety")}, indent=2))
        print("\nYou are approving REAL-MONEY trading with the limits above.")
        print("Crypto can lose all of its value. Stop-loss/limit orders do not guarantee a price.")
        phrase = "I APPROVE LIVE TRADING UP TO CAD %.2f" % cfg["capital"]["max_capital_cad"]
        typed = input(f'Type exactly "{phrase}" to approve: ')
        if typed.strip() != phrase:
            print("Not approved.")
            return 1
        rec = write_approval(cfg, getpass.getuser(), time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
        print(f"Approved config {rec['config_hash'][:16]}. You must ALSO set live_trading_enabled=true "
              "(which changes the hash, so set it BEFORE approving) and export CRYPTOBOT_LIVE.")
    elif cmd == "kill":
        path = cfgmod.set_kill("cli")
        print(f"Kill switch set at {path}. Next run cancels open orders and does not trade.")
    elif cmd == "unkill":
        cfgmod.clear_kill()
        print("Kill switch removed.")
    elif cmd == "fetch-data":
        from .report import fetch_all
        fetch_all()
    elif cmd == "web":
        from .web import serve
        port = int(argv[argv.index("--port") + 1]) if "--port" in argv else 8050
        serve(port=port)
    elif cmd == "backtest":
        from .report import main as report_main
        report_main()
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
