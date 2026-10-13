"""Minimal Kraken REST client (stdlib only).

Deliberately exposes NO deposit or withdrawal methods. The only private calls are
balance/order queries, order placement, and cancellation, plus a permission canary.
"""
import base64
import hashlib
import hmac
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

API_URL = "https://api.kraken.com"
TIMEOUT = 15


class KrakenError(Exception):
    pass


def _http(req):
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            body = json.loads(resp.read().decode())
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
        raise KrakenError(f"network/parse error: {e}") from e
    if body.get("error"):
        raise KrakenError(", ".join(body["error"]))
    return body["result"]


class KrakenPublic:
    def get(self, method, **params):
        url = f"{API_URL}/0/public/{method}"
        if params:
            url += "?" + urllib.parse.urlencode(params)
        return _http(urllib.request.Request(url, headers={"User-Agent": "cryptobot/1.0"}))

    def system_status(self):
        return self.get("SystemStatus")["status"]

    def asset_pair(self, pair):
        res = self.get("AssetPairs", pair=pair)
        return next(iter(res.values()))

    def ticker(self, pair):
        res = self.get("Ticker", pair=pair)
        t = next(iter(res.values()))
        return {"ask": float(t["a"][0]), "bid": float(t["b"][0]), "last": float(t["c"][0]),
                "open_24h": float(t["o"]), "high_24h": float(t["h"][1]), "low_24h": float(t["l"][1])}

    def daily_ohlc(self, pair):
        """Up to 720 daily candles: list of (ts, open, high, low, close). Last candle is in-progress."""
        res = self.get("OHLC", pair=pair, interval=1440)
        rows = next(v for k, v in res.items() if k != "last")
        return [(int(r[0]), float(r[1]), float(r[2]), float(r[3]), float(r[4])) for r in rows]

    def server_time(self):
        return self.get("Time")["unixtime"]


class KrakenPrivate:
    """Credentials come only from environment variables; never from files in the repo."""

    def __init__(self, key=None, secret=None):
        self.key = key or os.environ.get("KRAKEN_API_KEY")
        self.secret = secret or os.environ.get("KRAKEN_API_SECRET")
        if not self.key or not self.secret:
            raise KrakenError("KRAKEN_API_KEY / KRAKEN_API_SECRET not set")

    def _sign(self, path, data):
        postdata = urllib.parse.urlencode(data)
        message = path.encode() + hashlib.sha256((data["nonce"] + postdata).encode()).digest()
        mac = hmac.new(base64.b64decode(self.secret), message, hashlib.sha512)
        return base64.b64encode(mac.digest()).decode(), postdata

    def post(self, method, **params):
        path = f"/0/private/{method}"
        data = {"nonce": str(time.time_ns() // 1000)}
        data.update({k: v for k, v in params.items() if v is not None})
        sig, postdata = self._sign(path, data)
        req = urllib.request.Request(API_URL + path, data=postdata.encode(), method="POST", headers={
            "API-Key": self.key, "API-Sign": sig, "User-Agent": "cryptobot/1.0",
            "Content-Type": "application/x-www-form-urlencoded"})
        return _http(req)

    def balance(self):
        return {k: float(v) for k, v in self.post("Balance").items()}

    def add_limit_order(self, pair, side, volume, price, post_only=True, ttl_seconds=3600,
                        validate=False, client_id=None):
        return self.post("AddOrder", pair=pair, type=side, ordertype="limit",
                         price=f"{price}", volume=f"{volume}",
                         oflags="post" if post_only else None,
                         timeinforce="GTD", expiretm=f"+{int(ttl_seconds)}",
                         validate="true" if validate else None, cl_ord_id=client_id)

    def open_orders(self):
        return self.post("OpenOrders").get("open", {})

    def query_orders(self, txids):
        return self.post("QueryOrders", txid=",".join(txids), trades="true")

    def cancel_all(self):
        return self.post("CancelAll")

    def key_can_withdraw(self):
        """Permission canary. WithdrawMethods requires the 'Withdraw Funds' permission
        (per docs.kraken.com). If this call SUCCEEDS the key is over-privileged."""
        try:
            self.post("WithdrawMethods")
            return True
        except KrakenError as e:
            if "Permission denied" in str(e):
                return False
            raise
