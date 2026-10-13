"""Configuration loading and the live-trading approval gate."""
import hashlib
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONFIG_PATH = Path(os.environ.get("CRYPTOBOT_CONFIG", ROOT / "config" / "config.json"))
STATE_DIR = Path(os.environ.get("CRYPTOBOT_STATE_DIR", ROOT / "state"))
KILL_SWITCH = STATE_DIR / "KILL"
APPROVAL_FILE = STATE_DIR / "approval.json"

# The user must export exactly this value to allow real orders.
LIVE_ENV_VAR = "CRYPTOBOT_LIVE"
LIVE_ENV_VALUE = "I_AUTHORIZE_REAL_MONEY"


def set_state_dir(path):
    """Point state, kill switch and approval at another folder (tests, web app)."""
    global STATE_DIR, KILL_SWITCH, APPROVAL_FILE
    STATE_DIR = Path(path)
    KILL_SWITCH = STATE_DIR / "KILL"
    APPROVAL_FILE = STATE_DIR / "approval.json"


def kill_active():
    return KILL_SWITCH.exists()


def set_kill(note=""):
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    import time
    KILL_SWITCH.write_text(time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()) + (f" {note}" if note else ""))
    return KILL_SWITCH


def clear_kill():
    KILL_SWITCH.unlink(missing_ok=True)


def load_config(path=None):
    path = Path(path or CONFIG_PATH)
    with open(path) as f:
        cfg = json.load(f)
    validate_config(cfg)
    return cfg


def config_hash(cfg):
    """Stable hash over everything except comments. Any risk-limit change alters it."""
    def strip(o):
        if isinstance(o, dict):
            return {k: strip(v) for k, v in sorted(o.items()) if not k.startswith("_")}
        if isinstance(o, list):
            return [strip(v) for v in o]
        return o
    blob = json.dumps(strip(cfg), sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(blob.encode()).hexdigest()


def validate_config(cfg):
    """Reject configurations that would violate the user's non-negotiable rules."""
    cap = cfg["capital"]["max_capital_cad"]
    if not (0 < cap <= 50.0):
        raise ValueError("max_capital_cad must be > 0 and <= 50.0 (user-authorized ceiling)")
    o = cfg["orders"]
    if o["max_order_cad"] > cap:
        raise ValueError("max_order_cad cannot exceed max_capital_cad")
    if o["min_order_cad"] <= 0 or o["min_order_cad"] > o["max_order_cad"]:
        raise ValueError("invalid min_order_cad")
    if o["order_type"] not in ("post_only_limit", "limit_ioc"):
        raise ValueError("only limit orders are supported (no market, margin or stop orders)")
    targets = cfg["strategy"]["targets"]
    if abs(sum(targets.values())) > 1.0 + 1e-9:
        raise ValueError("strategy targets must sum to <= 1.0 (no leverage)")
    for asset, w in targets.items():
        if asset not in cfg["universe"]:
            raise ValueError(f"{asset} is not in the approved universe")
        if w < 0:
            raise ValueError("negative weights (short selling) are not allowed")
        if w > cfg["universe"][asset]["max_allocation"] + 1e-9:
            raise ValueError(f"{asset} target {w} exceeds its max_allocation")
    for a in cfg["universe"].values():
        if not (0 <= a["max_allocation"] <= 1.0):
            raise ValueError("max_allocation must be within [0, 1]")
    if cfg["activity_limits"]["max_cumulative_fees_cad"] > 0.10 * cap:
        raise ValueError("fee budget above 10% of capital is not allowed")


def live_trading_status(cfg):
    """Return (allowed: bool, reasons: list[str]). Every gate must pass for real orders."""
    reasons = []
    if not cfg.get("live_trading_enabled"):
        reasons.append("config.live_trading_enabled is false")
    if os.environ.get(LIVE_ENV_VAR) != LIVE_ENV_VALUE:
        reasons.append(f"env {LIVE_ENV_VAR} is not set to the authorization phrase")
    if KILL_SWITCH.exists():
        reasons.append(f"kill switch present at {KILL_SWITCH}")
    approval = read_approval()
    if not approval:
        reasons.append("no recorded approval; run `python -m cryptobot approve`")
    elif approval.get("config_hash") != config_hash(cfg):
        reasons.append("risk config changed since approval; re-run `python -m cryptobot approve`")
    return (not reasons), reasons


def read_approval():
    try:
        with open(APPROVAL_FILE) as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return None


def write_approval(cfg, who, when_iso):
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    data = {"config_hash": config_hash(cfg), "approved_by": who, "approved_at": when_iso,
            "max_capital_cad": cfg["capital"]["max_capital_cad"]}
    tmp = APPROVAL_FILE.with_suffix(".tmp")
    with open(tmp, "w") as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, APPROVAL_FILE)
    return data
