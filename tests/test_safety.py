import copy
import json
import os
import tempfile
import time
import unittest
from pathlib import Path

from cryptobot import config as config_mod
from cryptobot.backtest import run_backtest, variant
from cryptobot.config import config_hash, live_trading_status, load_config, validate_config
from cryptobot.risk import RiskEngine
from cryptobot.strategy import plan_trades, trend_filter_targets

CFG = load_config()
PAIR = {"ordermin": "0.00005", "costmin": "1"}
NOW = 1_800_000_000


def state(**kw):
    s = {"cash_cad": 50.0, "holdings": {}, "cost_basis_cad": 0.0, "fees_paid_cad": 0.0,
         "orders": [], "prices": {"BTC": 100000.0, "ETH": 3000.0}, "paused_reason": None}
    s.update(kw)
    return s


class ConfigRules(unittest.TestCase):
    def test_capital_above_50_rejected(self):
        c = copy.deepcopy(CFG)
        c["capital"]["max_capital_cad"] = 50.01
        with self.assertRaises(ValueError):
            validate_config(c)

    def test_leverage_rejected(self):
        c = copy.deepcopy(CFG)
        c["strategy"]["targets"] = {"BTC": 1.0, "ETH": 0.4}
        with self.assertRaises(ValueError):
            validate_config(c)

    def test_market_orders_rejected(self):
        c = copy.deepcopy(CFG)
        c["orders"]["order_type"] = "market"
        with self.assertRaises(ValueError):
            validate_config(c)

    def test_unapproved_asset_rejected(self):
        c = copy.deepcopy(CFG)
        c["strategy"]["targets"] = {"DOGE": 0.5}
        with self.assertRaises(ValueError):
            validate_config(c)

    def test_hash_ignores_comments_but_not_limits(self):
        c = copy.deepcopy(CFG)
        c["_comment"] = "changed"
        self.assertEqual(config_hash(c), config_hash(CFG))
        c["activity_limits"]["max_orders_per_day"] = 3
        self.assertNotEqual(config_hash(c), config_hash(CFG))


class LiveGate(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        d = Path(self.tmp.name)
        self._saved = (config_mod.KILL_SWITCH, config_mod.APPROVAL_FILE, config_mod.STATE_DIR)
        config_mod.STATE_DIR, config_mod.KILL_SWITCH, config_mod.APPROVAL_FILE = d, d / "KILL", d / "approval.json"
        self.cfg = copy.deepcopy(CFG)
        self.cfg["live_trading_enabled"] = True
        os.environ[config_mod.LIVE_ENV_VAR] = config_mod.LIVE_ENV_VALUE

    def tearDown(self):
        config_mod.KILL_SWITCH, config_mod.APPROVAL_FILE, config_mod.STATE_DIR = self._saved
        os.environ.pop(config_mod.LIVE_ENV_VAR, None)
        self.tmp.cleanup()

    def test_all_gates_pass(self):
        config_mod.write_approval(self.cfg, "me", "now")
        self.assertEqual(live_trading_status(self.cfg), (True, []))

    def test_default_config_is_paper(self):
        self.assertFalse(live_trading_status(CFG)[0])

    def test_changed_limits_revoke_approval(self):
        config_mod.write_approval(self.cfg, "me", "now")
        self.cfg["orders"]["max_order_cad"] = 40.0
        ok, reasons = live_trading_status(self.cfg)
        self.assertFalse(ok)
        self.assertTrue(any("changed since approval" in r for r in reasons))

    def test_kill_switch_blocks(self):
        config_mod.write_approval(self.cfg, "me", "now")
        config_mod.KILL_SWITCH.write_text("x")
        self.assertFalse(live_trading_status(self.cfg)[0])

    def test_env_phrase_required(self):
        config_mod.write_approval(self.cfg, "me", "now")
        os.environ[config_mod.LIVE_ENV_VAR] = "yes"
        self.assertFalse(live_trading_status(self.cfg)[0])


class OrderRisk(unittest.TestCase):
    def setUp(self):
        self.r = RiskEngine(CFG)

    def check(self, order, st, price=100000.0):
        return self.r.check_order(order, st, price, PAIR, NOW)

    def test_normal_buy_ok(self):
        self.assertEqual(self.check({"asset": "BTC", "side": "buy", "cad": 49.0}, state()), [])

    def test_full_capital_buy_ok(self):
        # Regression: fee is taken out of the order amount, so a full CAD 50 buy must pass.
        self.assertEqual(self.check({"asset": "BTC", "side": "buy", "cad": 50.0}, state()), [])

    def test_cannot_exceed_capital(self):
        v = self.check({"asset": "BTC", "side": "buy", "cad": 49.0}, state(cash_cad=60.0, cost_basis_cad=10.0))
        self.assertTrue(any("authorized capital" in x for x in v))

    def test_never_spends_money_it_does_not_have(self):
        v = self.check({"asset": "BTC", "side": "buy", "cad": 30.0}, state(cash_cad=20.0))
        self.assertTrue(any("never deposits" in x for x in v))

    def test_below_exchange_minimum(self):
        v = self.check({"asset": "BTC", "side": "buy", "cad": 5.0}, state(), price=200000.0)
        self.assertTrue(any("exchange minimum" in x for x in v))

    def test_fee_budget(self):
        v = self.check({"asset": "BTC", "side": "buy", "cad": 40.0}, state(fees_paid_cad=2.3))
        self.assertTrue(any("fee budget" in x for x in v))

    def test_daily_and_spacing_limits(self):
        recent = [{"ts": NOW - 3600, "asset": "BTC", "side": "buy", "status": "paper_filled"},
                  {"ts": NOW - 7200, "asset": "ETH", "side": "buy", "status": "paper_filled"}]
        v = self.check({"asset": "BTC", "side": "buy", "cad": 10.0}, state(orders=recent))
        self.assertTrue(any("daily order limit" in x for x in v))
        self.assertTrue(any("too recently" in x for x in v))

    def test_no_short_selling(self):
        v = self.check({"asset": "BTC", "side": "sell", "cad": 20.0}, state(holdings={"BTC": 0.0001}))
        self.assertTrue(any("no short" in x for x in v))

    def test_drawdown_breaker_blocks_buys(self):
        st = state(cash_cad=10.0, holdings={"BTC": 0.0001}, cost_basis_cad=40.0)  # equity 20
        v = self.check({"asset": "BTC", "side": "buy", "cad": 9.0}, st)
        self.assertTrue(any("drawdown" in x for x in v))

    def test_allocation_cap(self):
        st = state(cash_cad=50.0)
        v = self.check({"asset": "ETH", "side": "buy", "cad": 30.0}, st, price=3000.0)
        self.assertTrue(any("allocation" in x for x in v))

    def test_paused(self):
        v = self.check({"asset": "BTC", "side": "buy", "cad": 10.0}, state(paused_reason="x"))
        self.assertTrue(any("paused" in x for x in v))


class MarketSafety(unittest.TestCase):
    def snap(self, **t):
        ticker = {"bid": 100000.0, "ask": 100100.0, "last": 100050.0, "open_24h": 99000.0}
        ticker.update(t)
        return {"status": "online", "server_time": NOW, "ticker": ticker, "last_close": 100000.0,
                "last_closed_candle_ts": NOW - 3600}

    def test_ok(self):
        self.assertEqual(RiskEngine(CFG).check_market(self.snap(), NOW), [])

    def test_halts(self):
        r = RiskEngine(CFG)
        self.assertTrue(r.check_market(self.snap(ask=103000.0), NOW))      # wide spread
        self.assertTrue(r.check_market(self.snap(open_24h=80000.0), NOW))  # +25% in 24h
        s = self.snap(); s["status"] = "cancel_only"
        self.assertTrue(r.check_market(s, NOW))
        s = self.snap(); s["server_time"] = NOW - 3600
        self.assertTrue(r.check_market(s, NOW))                            # stale
        s = self.snap(); s["last_closed_candle_ts"] = NOW - 4 * 86400
        self.assertTrue(r.check_market(s, NOW))


class StrategyBehaviour(unittest.TestCase):
    def test_trend_filter(self):
        c = variant(CFG, name="trend_filter", targets={"BTC": 1.0}, sma_days=3)
        self.assertEqual(trend_filter_targets(c, {"closes": {"BTC": [1, 2, 3, 10]}})["BTC"], 1.0)
        self.assertEqual(trend_filter_targets(c, {"closes": {"BTC": [10, 9, 8, 1]}})["BTC"], 0.0)
        self.assertEqual(trend_filter_targets(c, {"closes": {"BTC": [1]}})["BTC"], 0.0)

    def test_losses_never_topped_up_and_gains_not_relevered(self):
        c = variant(CFG, name="hold", targets={"BTC": 1.0})
        # Lost half: wants to buy but has no cash -> plan asks, risk/cash stops it; nothing above cap.
        orders = plan_trades(c, {"BTC": 1.0}, {"BTC": 25.0}, 0.0, {})
        self.assertTrue(all(o["cad"] <= 50.0 for o in orders))
        # Doubled: hold never trims and never buys more.
        self.assertEqual(plan_trades(c, {"BTC": 1.0}, {"BTC": 100.0}, 0.0, {}), [])

    def test_exit_on_zero_target(self):
        c = variant(CFG, name="trend_filter", targets={"BTC": 1.0})
        self.assertEqual(plan_trades(c, {"BTC": 0.0}, {"BTC": 48.0}, 0.0, {}),
                         [{"asset": "BTC", "side": "sell", "cad": 48.0}])


class BacktestIntegrity(unittest.TestCase):
    def bars(self, closes):
        t0 = 1_600_000_000 // 86400 * 86400
        return {"BTC": [(t0 + i * 86400, c, c, c, c) for i, c in enumerate(closes)]}

    def test_no_lookahead(self):
        """A spike on the final day must not influence any decision."""
        c = variant(CFG, name="trend_filter", targets={"BTC": 1.0}, sma_days=5)
        base = [100.0] * 30 + [90.0] * 20
        a = run_backtest(c, self.bars(base + [90.0]), 0.008, 0.0)
        b = run_backtest(c, self.bars(base + [500.0]), 0.008, 0.0)
        self.assertEqual(a["trades"], b["trades"])

    def test_fees_reduce_value(self):
        c = variant(CFG, name="hold", targets={"BTC": 1.0})
        flat = self.bars([100.0] * 10)
        r = run_backtest(c, flat, 0.01, 0.0)
        self.assertAlmostEqual(r["final_cad"], 49.5, places=2)


class KrakenSigning(unittest.TestCase):
    def test_matches_published_vector(self):
        """Test vector from Kraken's REST authentication documentation."""
        from cryptobot.kraken import KrakenPrivate
        k = KrakenPrivate("k", "kQH5HW/8p1uGOVjbgWA7FunAmGO8lsSUXNsu3eow76sz84Q18fWxnyRzBHCd3pd5nE9qa99HAZtuZuj6F1huXg==")
        sig, _ = k._sign("/0/private/AddOrder", {"nonce": "1616492376594", "ordertype": "limit", "pair": "XBTUSD",
                                                "price": "37500", "type": "buy", "volume": "1.25"})
        self.assertEqual(sig, "4/dpxb3iT4tp/ZCVEwSnEsLxx0bqyhLpdfOpc6fn7OR8+UClSV5n9E6aSS8MPtnRfp32bAb0nmbRn6H8ndwLUQ==")

    def test_client_has_no_money_movement_methods(self):
        from cryptobot.kraken import KrakenPrivate
        names = {n.lower() for n in dir(KrakenPrivate)} - {"key_can_withdraw"}  # read-only canary
        self.assertFalse(any(w in n for n in names for w in ("withdraw", "deposit", "transfer")))


if __name__ == "__main__":
    unittest.main()
