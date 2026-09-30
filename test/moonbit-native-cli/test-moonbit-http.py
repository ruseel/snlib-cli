#!/usr/bin/env python3
"""Offline native CLI/HTTP integration tests. Run: python3 test/moonbit-native-cli/test-moonbit-http.py

Requires moon, a C compiler, libcurl development files, and optionally openssl
(for the untrusted TLS certificate test). No library account or live server used.
"""

import gzip
import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parents[2]
BINARY = ROOT / "impl/moonbit/_build/native/debug/build/cmd/snlib-cli/snlib-cli.exe"
FIXTURE = (ROOT / "fixtures/snlib/search-books/interloan-target.html").read_bytes()
EMPTY = "<html><p class='rtitle'><strong class='themeFC'>0건</strong></p><ul class='resultList imageType'></ul></html>".encode("utf-8")
ACCOUNT = (ROOT / "fixtures/snlib/my-info/page.html").read_bytes()
DUMMY_PASSWORD = "dummy&+비밀번호 =?"
LOGIN_PAGE = "/intro/memberLogin.do"
LOGIN_POST = "/intro/menu/10068/program/30025/memberLoginProc.do"
LOGIN_VERIFY = "/intro/menu/10060/program/30019/mypage/loanStatusList.do"
MY_INFO = "/intro/menu/10055/program/30017/mypage/myInfo.do"
LOGGED_OUT = b"<form id='redirectForm' action='/intro/menu/10068/program/30025/memberLogin.do'></form>"
AUTHENTICATED = b"<li class='logout'><a href='/intro/memberLogout.do'>Logout</a></li>"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.server.requests.append((self.path, dict(self.headers)))
        if urlsplit(self.path).path in (LOGIN_PAGE, LOGIN_VERIFY, MY_INFO):
            self.account_get()
            return
        mode = self.server.mode
        if mode == "redirect" and not self.path.startswith("/redirected"):
            self.send_response(302)
            self.send_header("Location", "/redirected")
            self.end_headers()
            return
        if mode == "loop":
            self.send_response(302)
            self.send_header("Location", self.path)
            self.end_headers()
            return
        body = FIXTURE
        if mode == "malformed":
            body = b"<html>not a catalogue</html>"
        elif mode == "empty":
            body = EMPTY
        elif mode == "oversized":
            body = b"x" * (8 * 1024 * 1024 + 1)
        elif mode == "gzip":
            body = gzip.compress(body)
        self.send_response(503 if mode == "http-error" else 200)
        self.send_header("Content-Type", "text/html; charset=UTF-8")
        if mode == "gzip":
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Expected when the client stops a too-large response.

    def reply(self, status, body=b"", cookie=None, location=None):
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=UTF-8")
        self.send_header("Content-Length", str(len(body)))
        for value in cookie or []:
            self.send_header("Set-Cookie", value)
        if location:
            self.send_header("Location", location)
        self.end_headers()
        self.wfile.write(body)

    def account_get(self):
        path = urlsplit(self.path).path
        mode = self.server.mode
        if path == LOGIN_PAGE:
            self.reply(503 if mode == "login-page-error" else 200,
                       b"<form id='loginForm'></form>", cookie=[] if mode == "login-cookie-absent" else [
                           "PRE=preflight; Path=/intro; HttpOnly",
                           "WRONGPATH=hidden; Path=/elsewhere",
                           "WRONGDOMAIN=hidden; Domain=example.invalid; Path=/intro",
                       ])
            return
        valid = (self.server.current_cookie is not None and
                 f"AUTH={self.server.current_cookie}" in self.headers.get("Cookie", ""))
        if path == LOGIN_VERIFY:
            if mode == "login-verify-error":
                self.reply(503, AUTHENTICATED)
            elif mode == "login-unknown-html":
                self.reply(200, b"<html>HTTP 200 alone is not authentication</html>")
            elif valid or mode == "login-cookie-absent":
                self.reply(200, AUTHENTICATED)
            else:
                self.reply(200, LOGGED_OUT)
            return
        if mode == "account-http-error":
            self.reply(503, b"Unavailable")
        elif mode == "account-invalid-html":
            self.reply(200, b"<html>not account information</html>")
        elif mode == "account-unauthorized":
            self.reply(401, b"Unauthorized")
        elif not valid or mode == "account-logged-out":
            self.reply(200, LOGGED_OUT)
        elif mode == "account-rotate":
            self.server.current_cookie += "-rotated"
            self.reply(200, ACCOUNT, cookie=[f"AUTH={self.server.current_cookie}; Path=/intro; HttpOnly"])
        else:
            self.reply(200, ACCOUNT)

    def do_POST(self):
        self.server.requests.append((self.path, dict(self.headers)))
        body = self.rfile.read(int(self.headers.get("Content-Length", "0"))).decode("utf-8")
        form = parse_qs(body, keep_blank_values=True)
        self.server.forms.append(form)
        if self.path != LOGIN_POST or (self.server.mode != "login-cookie-absent" and
                "PRE=preflight" not in self.headers.get("Cookie", "")):
            self.reply(400, b"Missing preflight cookie")
            return
        if self.server.mode == "login-submit-error":
            self.reply(503)
            return
        if self.server.mode == "login-forbidden":
            self.reply(403)
            return
        accepted = (form.get("userId") == ["fixture-reader"] and
                    form.get("password") == [DUMMY_PASSWORD] and
                    self.server.mode != "login-rejected")
        cookies = []
        if accepted and self.server.mode != "login-cookie-absent":
            self.server.current_cookie = "fictional-auth-cookie"
            cookies = [f"AUTH={self.server.current_cookie}; Path=/intro; HttpOnly"]
        if self.server.mode == "login-redirect":
            self.reply(307, cookie=cookies, location="http://example.invalid/must-not-receive-password")
        else:
            self.reply(200, b"Submitted", cookie=cookies)

    def log_message(self, *_):
        pass


def start_server(context=None):
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.mode = "fixture"
    server.requests = []
    server.forms = []
    server.current_cookie = None
    if context:
        server.socket = context.wrap_socket(server.socket, server_side=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread


class NativeCliTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        subprocess.run(["moon", "-C", "impl/moonbit", "build", "--target", "native"], cwd=ROOT, check=True)
        cls.server, cls.thread = start_server()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def setUp(self):
        self.server.mode = "fixture"
        self.server.requests.clear()
        self.server.forms.clear()
        self.server.current_cookie = None
        self.home = tempfile.TemporaryDirectory(prefix="snlib-http-test-")

    def tearDown(self):
        self.home.cleanup()

    def run_cli(self, payload=None, base_url=None, raw=None, command="search-books", config_dir=None):
        env = dict(os.environ, HOME=self.home.name, XDG_CONFIG_HOME=config_dir or self.home.name,
                   NO_PROXY="127.0.0.1,localhost", no_proxy="127.0.0.1,localhost",
                   SNLIB_BASE_URL=base_url or f"http://127.0.0.1:{self.server.server_port}/")
        completed = subprocess.run(
            [str(BINARY), command],
            input=raw if raw is not None else json.dumps(payload if payload is not None else
                  ({"keyword": "삼국지 1"} if command == "search-books" else {})),
            text=True, capture_output=True, env=env, timeout=40, check=True,
        )
        # stdout must contain exactly one JSON result, with no transport logging.
        self.assertEqual(len(completed.stdout.splitlines()), 1)
        self.assertEqual(completed.stderr, "")
        return json.loads(completed.stdout)


class NativeHttpTest(NativeCliTest):
    def test_fixture_and_request_contract(self):
        keyword = "삼국지 & +/?#"
        result = self.run_cli({"keyword": keyword, "manage_codes": ["MG", "MB"],
                               "page": 3, "per_page": 20, "sort": "TITLE", "order": "ASC"})
        self.assertEqual(result["outcome"], "ok")
        self.assertEqual(result["code"], "search-ok")
        data = result["data"]
        self.assertEqual(data["page"], 3)
        self.assertEqual(data["total_count"], 12)
        self.assertEqual(len(data["items"]), 10)
        self.assertEqual(data["items"][0]["title"], "삼국지 1")
        self.assertEqual(data["items"][0]["manage_code"], "MG")
        self.assertEqual(data["items"][0]["registration_number"], "FIC000000101")
        path, headers = self.server.requests[0]
        url = urlsplit(path)
        self.assertEqual(url.path, "/intro/menu/10041/program/30009/plusSearchResultList.do")
        query = parse_qs(url.query, keep_blank_values=True)
        self.assertEqual(query["searchKeyword"], [keyword])
        self.assertEqual(query["preSearchKeyword"], [keyword])
        self.assertEqual(query["searchLibraryArr"], ["MG", "MB"])
        self.assertEqual(query["searchPbLibrary"], [""])
        self.assertEqual(query["currentPageNo"], ["3"])
        self.assertEqual(query["searchRecordCount"], ["20"])
        self.assertEqual(query["searchSort"], ["TITLE"])
        self.assertEqual(query["searchOrder"], ["ASC"])
        self.assertEqual(headers["User-Agent"], "Mozilla/5.0 snlib-lib")
        self.assertEqual(headers["Referer"], "https://snlib.go.kr/intro/main/index.do")

    def test_default_all_libraries(self):
        self.assertEqual(self.run_cli()["outcome"], "ok")
        query = parse_qs(urlsplit(self.server.requests[0][0]).query, keep_blank_values=True)
        self.assertEqual(query["searchPbLibrary"], ["ALL"])
        self.assertNotIn("searchLibraryArr", query)

    def test_redirect(self):
        self.server.mode = "redirect"
        self.assertEqual(self.run_cli()["outcome"], "ok")
        self.assertEqual(len(self.server.requests), 2)

    def test_redirect_limit(self):
        self.server.mode = "loop"
        self.assertEqual(self.run_cli()["code"], "http-request-failed")
        self.assertEqual(len(self.server.requests), 6)

    def test_compressed_response(self):
        self.server.mode = "gzip"
        self.assertEqual(self.run_cli()["outcome"], "ok")

    def test_empty_catalogue(self):
        self.server.mode = "empty"
        result = self.run_cli()
        self.assertEqual(result["outcome"], "ok")
        self.assertEqual(result["data"], {"items": [], "page": 1, "total_count": 0})

    def test_http_status_is_not_a_network_error(self):
        self.server.mode = "http-error"
        result = self.run_cli()
        self.assertEqual(result["outcome"], "remote-error")
        self.assertEqual(result["code"], "search-request-failed")
        self.assertIn("503", result["message"])

    def test_parse_failure(self):
        self.server.mode = "malformed"
        self.assertEqual(self.run_cli()["code"], "search-response-parse-failed")

    def test_connection_failure(self):
        with socket.socket() as unused:
            unused.bind(("127.0.0.1", 0))
            port = unused.getsockname()[1]
        result = self.run_cli(base_url=f"http://127.0.0.1:{port}")
        self.assertEqual(result["outcome"], "remote-error")
        self.assertEqual(result["code"], "http-request-failed")

    def test_invalid_input_does_not_send_request(self):
        self.assertEqual(self.run_cli(raw="not json")["code"], "malformed-json")
        self.assertEqual(self.run_cli({"keyword": ""})["outcome"], "invalid-input")
        self.assertEqual(self.server.requests, [])

    def test_non_http_url_is_rejected(self):
        self.assertEqual(self.run_cli(base_url="file:///etc/passwd")["code"], "http-request-failed")
        self.assertEqual(self.server.requests, [])

    def test_response_size_limit(self):
        self.server.mode = "oversized"
        result = self.run_cli()
        self.assertEqual(result["code"], "http-request-failed")
        self.assertIn("8 MiB", result["message"])

    @unittest.skipUnless(shutil.which("openssl"), "openssl needed for local TLS certificate")
    def test_untrusted_tls_certificate_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            cert, key = Path(directory) / "cert.pem", Path(directory) / "key.pem"
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                            "-days", "1", "-subj", "/CN=localhost", "-keyout", str(key),
                            "-out", str(cert)], check=True, capture_output=True)
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(cert, key)
            server, thread = start_server(context)
            try:
                result = self.run_cli(base_url=f"https://127.0.0.1:{server.server_port}")
                self.assertEqual(result["code"], "http-request-failed")
                self.assertEqual(server.requests, [])
            finally:
                server.shutdown()
                server.server_close()
                thread.join()


class NativeAccountTest(NativeCliTest):
    def login(self, **kwargs):
        return self.run_cli({"user_id": "fixture-reader", "password": DUMMY_PASSWORD},
                            command="login", **kwargs)

    def session_path(self):
        return Path(self.home.name) / "snlib-cli/session.json"

    def saved_session(self):
        return json.loads(self.session_path().read_text())

    def test_login_and_account_across_processes(self):
        result = self.login()
        self.assertEqual(result["code"], "login-ok")
        self.assertEqual(result["data"], {"authenticated": True, "user_id": "fixture-reader"})
        self.assertEqual([path for path, _ in self.server.requests], [LOGIN_PAGE, LOGIN_POST, LOGIN_VERIFY])
        form = self.server.forms[0]
        self.assertEqual(form["userId"], ["fixture-reader"])
        self.assertEqual(form["password"], [DUMMY_PASSWORD])
        self.assertEqual(form["returnUrl"], ["aHR0cHM6Ly9zbmxpYi5nby5rci9pbnRyby9pbmRleC5kbw=="])
        self.assertIn("application/x-www-form-urlencoded", self.server.requests[1][1]["Content-Type"])
        saved = self.saved_session()
        self.assertEqual(saved["version"], 2)
        self.assertEqual(self.session_path().stat().st_mode & 0o777, 0o600)
        self.assertNotIn("password", saved)
        self.assertNotIn(DUMMY_PASSWORD, self.session_path().read_text())
        self.assertNotIn("fictional-auth-cookie", json.dumps(result))
        info = self.run_cli(command="my-info")
        self.assertEqual(info["code"], "my-info-ok")
        self.assertEqual(info["data"], {
            "user_id": "fixture-reader", "member_no": "FICTIONAL-000001", "member_type": "정회원",
            "join_date": "2026.01.01", "privacy_expiry_date": "2028.01.01",
            "phone": "010-0000-0000", "email": "reader@example.invalid",
        })
        for _, headers in self.server.requests[1:]:
            self.assertNotIn("WRONGPATH", headers.get("Cookie", ""))
            self.assertNotIn("WRONGDOMAIN", headers.get("Cookie", ""))
        self.assertIn("AUTH=fictional-auth-cookie", self.server.requests[-1][1]["Cookie"])

    def test_new_login_does_not_reuse_old_account_cookies(self):
        self.assertEqual(self.login()["outcome"], "ok")
        self.server.requests.clear()
        self.assertEqual(self.login()["outcome"], "ok")
        self.assertNotIn("Cookie", self.server.requests[0][1])
        self.assertNotIn("AUTH=", self.server.requests[1][1].get("Cookie", ""))

    def test_failed_login_invalidates_old_session(self):
        self.login()
        self.server.mode = "login-rejected"
        result = self.login()
        self.assertEqual(result["code"], "login-rejected")
        self.assertFalse(result["data"]["authenticated"])
        self.assertNotIn("cookies", self.saved_session())
        self.assertEqual(self.run_cli(command="my-info")["code"], "session-missing")

    def test_missing_session_sends_no_request(self):
        self.assertEqual(self.run_cli(command="my-info")["code"], "session-missing")
        self.assertEqual(self.server.requests, [])

    def test_local_expiry_and_future_timestamp_send_no_request(self):
        self.login()
        saved = self.saved_session()
        for timestamp in (0, saved["authenticated_at_ms"] + 24 * 60 * 60 * 1000):
            saved["authenticated_at_ms"] = timestamp
            self.session_path().write_text(json.dumps(saved))
            self.server.requests.clear()
            self.assertEqual(self.run_cli(command="my-info")["code"], "session-expired")
            self.assertEqual(self.server.requests, [])

    def test_origin_mismatch_never_sends_cookies(self):
        self.login()
        server, thread = start_server()
        try:
            result = self.run_cli(command="my-info", base_url=f"http://127.0.0.1:{server.server_port}")
            self.assertEqual(result["code"], "session-origin-mismatch")
            self.assertEqual(server.requests, [])
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_server_rejection_removes_cookie_session(self):
        for mode in ("account-logged-out", "account-unauthorized"):
            self.server.mode = "fixture"
            self.login()
            self.server.mode = mode
            self.assertEqual(self.run_cli(command="my-info")["code"], "session-rejected")
            self.assertNotIn("cookies", self.saved_session())
            self.assertEqual(self.run_cli(command="my-info")["code"], "session-missing")

    def test_account_errors_do_not_destroy_a_valid_session(self):
        self.login()
        saved = self.saved_session()
        for mode, code in (("account-http-error", "my-info-request-failed"),
                           ("account-invalid-html", "my-info-response-parse-failed")):
            self.server.mode = mode
            self.assertEqual(self.run_cli(command="my-info")["code"], code)
            self.assertEqual(self.saved_session(), saved)

    def test_cookie_rotation_preserves_original_ttl(self):
        self.login()
        original = self.saved_session()
        self.server.mode = "account-rotate"
        self.assertEqual(self.run_cli(command="my-info")["outcome"], "ok")
        rotated = self.saved_session()
        self.assertNotEqual(original["cookies"], rotated["cookies"])
        self.assertEqual(original["authenticated_at_ms"], rotated["authenticated_at_ms"])
        self.assertEqual(self.run_cli(command="my-info")["outcome"], "ok")
        self.assertIn("AUTH=fictional-auth-cookie-rotated", self.server.requests[-1][1]["Cookie"])

    def test_public_search_does_not_send_account_cookies(self):
        self.login()
        self.assertEqual(self.run_cli()["outcome"], "ok")
        self.assertNotIn("Cookie", self.server.requests[-1][1])

    def test_post_redirect_never_replays_credentials(self):
        self.server.mode = "login-redirect"
        self.assertEqual(self.login()["outcome"], "ok")
        self.assertEqual([path for path, _ in self.server.requests], [LOGIN_PAGE, LOGIN_POST, LOGIN_VERIFY])
        self.assertEqual(len(self.server.forms), 1)

    def test_login_errors_and_unconfirmed_authentication(self):
        for mode, code in (("login-page-error", "login-request-failed"),
                           ("login-submit-error", "login-request-failed"),
                           ("login-verify-error", "login-request-failed"),
                           ("login-unknown-html", "login-response-parse-failed"),
                           ("login-forbidden", "login-rejected"),
                           ("login-cookie-absent", "session-save-failed")):
            self.server.mode = mode
            result = self.login()
            self.assertEqual(result["code"], code, mode)
            self.assertNotIn("cookies", self.saved_session())
            self.assertNotIn(DUMMY_PASSWORD, json.dumps(result))

    def test_invalid_login_keeps_existing_session_and_sends_no_request(self):
        self.login()
        saved = self.saved_session()
        self.server.requests.clear()
        for payload in ({"user_id": "", "password": DUMMY_PASSWORD},
                        {"user_id": "fixture-reader", "password": " "}, {}):
            self.assertEqual(self.run_cli(payload, command="login")["outcome"], "invalid-input")
        self.assertEqual(self.server.requests, [])
        self.assertEqual(self.saved_session(), saved)

    def test_legacy_or_corrupt_session_requires_new_login(self):
        self.session_path().parent.mkdir()
        for content in ("not JSON", json.dumps({"version": 1, "patron_id": "reader",
                        "authenticated_at_ms": 0, "remembered": True, "status": "active"})):
            self.session_path().write_text(content)
            self.assertEqual(self.run_cli(command="my-info")["code"], "session-missing")
            self.assertEqual(self.server.requests, [])

    def test_session_save_failure_is_not_reported_as_login_success(self):
        blocked = Path(self.home.name) / "not-a-directory"
        blocked.write_text("file")
        self.assertEqual(self.login(config_dir=str(blocked))["code"], "session-save-failed")


if __name__ == "__main__":
    unittest.main(verbosity=2)
