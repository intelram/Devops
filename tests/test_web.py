import http.client
import json
import tempfile
import threading
import unittest
import urllib.parse
from http.server import ThreadingHTTPServer
from pathlib import Path

from cryptobot import config as config_mod
from cryptobot import web


class WebPanel(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls._saved = config_mod.STATE_DIR
        config_mod.set_state_dir(Path(cls.tmp.name))
        cls.runs = []
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), web.make_handler(0))
        cls.port = cls.httpd.server_port
        cls.httpd.RequestHandlerClass = web.make_handler(cls.port, runner=lambda cfg: cls.runs.append(cfg))
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        config_mod.set_state_dir(cls._saved)
        cls.tmp.cleanup()

    def setUp(self):
        config_mod.clear_kill()
        web._last_web_run[0] = 0.0

    def req(self, method, path, body=None, host=None, headers=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        h = {"Host": host or f"127.0.0.1:{self.port}"}
        if body is not None:
            body = urllib.parse.urlencode(body)
            h["Content-Type"] = "application/x-www-form-urlencoded"
        h.update(headers or {})
        c.request(method, path, body=body, headers=h)
        r = c.getresponse()
        data = r.read().decode()
        c.close()
        return r, data

    def test_pages_render_with_strict_headers(self):
        for path in ("/", "/activity", "/backtests", "/settings"):
            r, body = self.req("GET", path)
            self.assertEqual(r.status, 200, path)
            csp = r.getheader("Content-Security-Policy")
            self.assertIn("default-src 'none'", csp)
            self.assertIn("frame-ancestors 'none'", csp)
            self.assertNotIn("<script", body.lower())
            self.assertNotIn("style=", body)  # CSP forbids inline styles; keep markup clean

    def test_rejects_foreign_host_header(self):
        r, _ = self.req("GET", "/", host="evil.example:80")
        self.assertEqual(r.status, 400)
        r, _ = self.req("POST", "/action/kill", body={"csrf": web.CSRF_TOKEN}, host="attacker.test")
        self.assertEqual(r.status, 400)
        self.assertFalse(config_mod.kill_active())

    def test_post_requires_csrf_token(self):
        r, _ = self.req("POST", "/action/kill", body={})
        self.assertEqual(r.status, 403)
        r, _ = self.req("POST", "/action/kill", body={"csrf": "wrong"})
        self.assertEqual(r.status, 403)
        self.assertFalse(config_mod.kill_active())

    def test_cross_origin_post_refused_even_with_token(self):
        r, _ = self.req("POST", "/action/kill", body={"csrf": web.CSRF_TOKEN},
                        headers={"Origin": "http://evil.example"})
        self.assertEqual(r.status, 403)
        r, _ = self.req("POST", "/action/kill", body={"csrf": web.CSRF_TOKEN},
                        headers={"Sec-Fetch-Site": "cross-site"})
        self.assertEqual(r.status, 403)
        self.assertFalse(config_mod.kill_active())

    def test_kill_switch_round_trip(self):
        r, _ = self.req("POST", "/action/kill", body={"csrf": web.CSRF_TOKEN},
                        headers={"Origin": f"http://127.0.0.1:{self.port}"})
        self.assertEqual(r.status, 303)
        self.assertTrue(config_mod.kill_active())
        _, body = self.req("GET", "/?msg=killed")
        self.assertIn("Kill switch ON", body)
        r, _ = self.req("POST", "/action/unkill", body={"csrf": web.CSRF_TOKEN})
        self.assertEqual(r.status, 303)
        self.assertFalse(config_mod.kill_active())

    def test_run_paper_is_rate_limited(self):
        n = len(self.runs)
        r, _ = self.req("POST", "/action/run-paper", body={"csrf": web.CSRF_TOKEN})
        self.assertIn("msg=ran", r.getheader("Location"))
        r, _ = self.req("POST", "/action/run-paper", body={"csrf": web.CSRF_TOKEN})
        self.assertIn("msg=busy", r.getheader("Location"))
        self.assertEqual(len(self.runs), n + 1)

    def test_event_text_is_escaped(self):
        with open(config_mod.STATE_DIR / "events_paper.jsonl", "a") as f:
            f.write(json.dumps({"ts": 1, "iso": "x", "mode": "paper", "kind": "<script>alert(1)</script>",
                                "message": "<img src=x onerror=alert(2)>"}) + "\n")
        _, body = self.req("GET", "/activity?mode=paper")
        self.assertNotIn("<script>alert", body)
        self.assertNotIn("<img src=x", body)
        self.assertIn("&lt;script&gt;", body)

    def test_flash_cannot_inject(self):
        _, body = self.req("GET", "/?msg=%3Cb%3Ehi%3C%2Fb%3E")
        self.assertNotIn("<b>hi</b>", body)

    def test_status_api(self):
        r, body = self.req("GET", "/api/status.json")
        self.assertEqual(r.status, 200)
        data = json.loads(body)
        self.assertIn("live_allowed", data)
        self.assertFalse(data["live_allowed"])
        self.assertNotIn("KRAKEN_API", body)


if __name__ == "__main__":
    unittest.main()
