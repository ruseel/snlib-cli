#!/usr/bin/env python3
"""Offline command contract tests, including fictional submissions to localhost only."""
import importlib.util
import json
from pathlib import Path
import subprocess
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

spec = importlib.util.spec_from_file_location("native_http", Path(__file__).with_name("test-moonbit-http.py"))
http = importlib.util.module_from_spec(spec)
spec.loader.exec_module(http)
FIXTURES = http.ROOT / "fixtures/snlib"
PATHS = {
    "loan-status": http.LOGIN_VERIFY,
    "loan-history": "/intro/menu/10062/program/30021/mypage/loanHistoryList.do",
    "reservation-status": "/intro/menu/10061/program/30020/mypage/reservationStatusList.do",
    "interloan-status": "/intro/bandLillStatusList.do",
    "hope-book-list": "/intro/menu/10065/program/30011/mypage/hopeBookList.do",
    "hope-book-detail": "/intro/menu/10065/program/30011/mypage/hopeBookDetail.do",
    "basket-list": "/intro/menu/10057/program/30018/mypage/basketGroupMain.do",
    "interloan-request": "/intro/doorae/bandLillApplyPop.do",
    "hope-book-request": "/intro/menu/10045/program/30011/hopeBookApply.do",
    "interlibrary-loan-request": "/intro/doorae/bandLillApplyPop.do",
}
BASKET_BOOKS = "/intro/menu/10057/program/30018/mypage/basketGroupBookList.do"
INTERLOAN_POST = "/intro/doorae/bandLillApplyPopProc.do"
HOPE_POST = "/intro/menu/10045/program/30011/hopeBookApplyProc.do"
INTERLOAN = {"manage_code": "MG", "reg_no": "FIC000000101", "apl_lib_code": "141484"}
HOPE = {"manage_code": "MU", "request": {
    "title": "Fictional & +도서", "author": "Fictional", "publisher": "가상출판사",
    "publish_year": "2026", "sms_receipt_yn": "Y", "hand_phone": "01098765432",
}}
INPUTS = {command: {} for command in PATHS}
INPUTS.update({"hope-book-detail": {"rec_key": "12345"},
               "interloan-request": INTERLOAN, "interlibrary-loan-request": INTERLOAN,
               "hope-book-request": HOPE})


def fixture(name):
    return (FIXTURES / name).read_bytes()


class CommandHandler(http.Handler):
    def command_response(self, path, body):
        mode = self.server.mode
        if mode == "command-http-error":
            self.reply(503, b"Unavailable")
        elif mode == "command-unauthorized":
            self.reply(403, b"Unauthorized")
        elif mode == "command-logged-out":
            self.reply(200, http.LOGGED_OUT)
        elif mode == "command-invalid-html":
            self.reply(200, b"<html>Unknown page</html>")
        elif not self.server.current_cookie or (
                "AUTH=" + self.server.current_cookie) not in self.headers.get("Cookie", ""):
            self.reply(401, b"No session")
        else:
            cookie = None
            if mode == "command-rotate":
                self.server.current_cookie += "-rotated"
                cookie = [f"AUTH={self.server.current_cookie}; Path=/intro; HttpOnly"]
            self.reply(200, body, cookie=cookie)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path not in PATHS.values() or path == http.LOGIN_VERIFY and self.server.mode == "fixture":
            return super().do_GET()
        self.server.requests.append((self.path, dict(self.headers)))
        if path == http.LOGIN_VERIFY:
            body = fixture("account-queries/loan-table.html") if self.server.mode == "command-table" else fixture("loan-status/page-article-list.html")
        elif path == PATHS["hope-book-detail"]:
            body = fixture("account-queries/detail.html")
        elif path == PATHS["basket-list"]:
            body = fixture("account-queries/basket-main.html")
        elif path == PATHS["interloan-request"]:
            body = fixture("interloan-request/popup-samgukji-1.html")
            if self.server.mode == "command-wrong-book":
                body = body.replace(b"FIC000000101", b"WRONG0001")
            if self.server.mode == "command-missing-popup":
                body = body.replace(b'name="giveLibCode"', b'name="unused"')
        elif path == PATHS["hope-book-request"]:
            body = fixture("hope-book-request/page.html")
        else:
            body = fixture("account-queries/empty.html" if self.server.mode == "command-empty" else "account-queries/article.html")
        self.command_response(path, body)

    def do_POST(self):
        if self.path == http.LOGIN_POST:
            return super().do_POST()
        self.server.requests.append((self.path, dict(self.headers)))
        self.server.forms.append(parse_qs(
            self.rfile.read(int(self.headers.get("Content-Length", "0"))).decode(),
            keep_blank_values=True))
        if self.path == BASKET_BOOKS:
            if self.server.mode == "command-books-logged-out":
                self.reply(200, http.LOGGED_OUT)
                return
            if self.server.mode == "command-books-http-error":
                self.reply(503)
                return
            if self.server.mode == "command-books-invalid-html":
                self.reply(200, b"Unknown")
                return
            self.command_response(self.path, fixture("account-queries/basket-books.html"))
            return
        if self.path not in (INTERLOAN_POST, HOPE_POST):
            self.reply(404)
            return
        mode = self.server.mode
        if mode == "command-submit-redirect":
            self.reply(307, location="http://example.invalid/must-not-replay")
        elif mode == "command-submit-http-error":
            self.reply(503)
        elif mode == "command-submit-logged-out":
            self.reply(200, http.LOGGED_OUT)
        elif mode == "command-submit-unknown":
            self.reply(200, b"<html>HTTP 200</html>")
        elif mode == "command-submit-cross-token":
            self.reply(200, ("신청이 완료 되었습니다." if self.path == INTERLOAN_POST else
                             "상호대차 신청이 완료되었습니다.").encode())
        elif mode == "command-submit-rejected":
            self.reply(200, "<div class='messageBox'><p>신청 불가</p></div>".encode())
        else:
            body = ("상호대차 신청이 완료되었습니다." if self.path == INTERLOAN_POST else
                    "신청이 완료 되었습니다.").encode()
            self.command_response(self.path, body)


class CommandsTest(http.NativeCliTest):
    @classmethod
    def setUpClass(cls):
        subprocess.run(["moon", "-C", "moonbit", "build", "--target", "native"], cwd=http.ROOT, check=True)
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), CommandHandler)
        cls.server.mode = "fixture"
        cls.server.requests, cls.server.forms = [], []
        cls.server.current_cookie = None
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    def login(self):
        self.server.mode = "fixture"
        result = self.run_cli({"user_id": "fixture-reader", "password": http.DUMMY_PASSWORD}, command="login")
        self.assertEqual(result["outcome"], "ok", result)
        self.server.mode = "command"
        self.server.requests.clear()
        self.server.forms.clear()

    def saved(self):
        return json.loads((Path(self.home.name) / "snlib-cli/session.json").read_text())

    def test_all_account_commands_execute_and_render_typed_data(self):
        self.login()
        expectations = {
            "loan-status": ("loans", "테스트 도서 1"),
            "loan-history": ("loans", "테스트 도서"),
            "reservation-status": ("reservations", "테스트 도서"),
            "interloan-status": ("requests", "테스트 도서"),
            "hope-book-list": ("items", "테스트 도서"),
            "basket-list": ("books", "테스트 도서"),
        }
        for command, (key, title) in expectations.items():
            with self.subTest(command=command):
                result = self.run_cli(INPUTS[command], command=command)
                self.assertEqual(result["outcome"], "ok", result)
                self.assertEqual(result["data"][key][0]["title"], title)
                self.assertIn("AUTH=", self.server.requests[-1][1]["Cookie"])
        result = self.run_cli(INPUTS["hope-book-detail"], command="hope-book-detail")
        self.assertEqual(result["data"]["title"], "테스트 도서")
        self.assertEqual(result["data"]["author"], "Fictional")
        self.assertEqual(parse_qs(urlsplit(self.server.requests[-1][0]).query)["recKey"], ["12345"])
        result = self.run_cli(command="interloan-status")
        self.assertEqual(result["data"]["requests"][0]["cancel_key"], "67890")
        result = self.run_cli(command="hope-book-list")
        self.assertEqual(result["data"]["items"][0]["rec_key"], "12345")
        self.assertEqual(result["data"]["count"], 2)  # external total, not page length

    def test_article_loans_do_not_include_select_all_row(self):
        self.login()
        result = self.run_cli({"include_history": True}, command="loan-status")
        self.assertEqual(result["data"]["count"], 6)
        self.assertTrue(all(loan["title"] for loan in result["data"]["loans"]))
        self.assertEqual(result["data"]["loans"][0]["loan_date"], "2026.04.01")

    def test_basket_second_request_errors_and_rejection(self):
        for mode, code in (
                ("command-books-http-error", "basket-list-request-failed"),
                ("command-books-invalid-html", "basket-list-response-parse-failed"),
                ("command-books-logged-out", "session-rejected")):
            with self.subTest(mode=mode):
                self.login()
                saved = self.saved()
                self.server.mode = mode
                self.assertEqual(self.run_cli(command="basket-list")["code"], code)
                if mode == "command-books-logged-out":
                    self.assertNotIn("cookies", self.saved())
                else:
                    self.assertEqual(self.saved(), saved)

    def test_invalid_identifiers_and_submit_type_send_no_request(self):
        self.login()
        for command, payload in (
                ("hope-book-detail", {"rec_key": ""}),
                ("hope-book-detail", {"rec_key": "abc"}),
                ("basket-list", {"group_key": ""}),
                ("basket-list", {"group_key": "abc"}),
                ("hope-book-request", {"manage_code": "INVALID"}),
                ("hope-book-request", {"submit": "true"}),
                ("interloan-request", dict(INTERLOAN, reg_no=" ")),
                ("interloan-request", dict(INTERLOAN, appendix_apply_yn="INVALID")),
                ("interloan-request", dict(INTERLOAN, submit="true"))):
            with self.subTest(command=command, payload=payload):
                self.assertEqual(self.run_cli(payload, command=command)["outcome"], "invalid-input")
                self.assertEqual(self.server.requests, [])

    def test_table_loans_filter_history_and_preserve_renewable(self):
        self.login()
        self.server.mode = "command-table"
        current = self.run_cli(command="loan-status")["data"]
        self.assertEqual(current["count"], 1)
        self.assertEqual(current["loans"][0]["title"], "Current")
        self.assertTrue(current["loans"][0]["renewable"])
        all_loans = self.run_cli({"include_history": True}, command="loan-status")["data"]
        self.assertEqual(all_loans["count"], 2)
        self.assertFalse(all_loans["loans"][1]["renewable"])

    def test_empty_lists_are_valid(self):
        self.login()
        self.server.mode = "command-empty"
        for command in ("loan-history", "reservation-status", "interloan-status", "hope-book-list"):
            with self.subTest(command=command):
                result = self.run_cli(command=command)
                self.assertEqual(result["outcome"], "ok", result)
                self.assertEqual(result["data"]["count"], 0)

    def test_explicit_basket_group_form(self):
        self.login()
        result = self.run_cli({"group_key": "77777"}, command="basket-list")
        self.assertEqual(result["data"]["group_key"], "77777")
        self.assertEqual(self.server.forms[-1], {
            "searchGroupKey": ["77777"], "searchLibrary": ["ALL"],
            "searchKey": ["TITLE"], "searchValue": [""],
        })

    def test_missing_sessions_send_no_requests_for_every_command(self):
        for command, payload in INPUTS.items():
            with self.subTest(command=command):
                self.assertEqual(self.run_cli(payload, command=command)["code"], "session-missing")
        self.assertEqual(self.server.requests, [])

    def test_origin_mismatch_and_expiry_send_no_requests_for_every_command(self):
        self.login()
        path = Path(self.home.name) / "snlib-cli/session.json"
        saved = self.saved()
        for origin, timestamp, code in (
                ("https://example.invalid", saved["authenticated_at_ms"], "session-origin-mismatch"),
                (saved["base_url"], 0, "session-expired"),
                (saved["base_url"], saved["authenticated_at_ms"] + 86400000, "session-expired")):
            value = dict(saved, base_url=origin, authenticated_at_ms=timestamp)
            path.write_text(json.dumps(value))
            for command, payload in INPUTS.items():
                with self.subTest(command=command, code=code):
                    self.assertEqual(self.run_cli(payload, command=command)["code"], code)
            self.assertEqual(self.server.requests, [])

    def test_server_rejection_invalidates_every_command_session(self):
        for command, payload in INPUTS.items():
            for mode in ("command-unauthorized", "command-logged-out"):
                with self.subTest(command=command, mode=mode):
                    self.login()
                    self.server.mode = mode
                    self.assertEqual(self.run_cli(payload, command=command)["code"], "session-rejected")
                    self.assertNotIn("cookies", self.saved())

    def test_http_and_parse_errors_keep_sessions(self):
        self.login()
        saved = self.saved()
        for command, payload in INPUTS.items():
            for mode, suffix in (("command-http-error", "-request-failed"),
                                 ("command-invalid-html", "-response-parse-failed")):
                with self.subTest(command=command, mode=mode):
                    self.server.mode = mode
                    result = self.run_cli(payload, command=command)
                    self.assertEqual(result["code"], command + suffix)
                    self.assertEqual(self.saved(), saved)

    def test_cookie_rotation_preserves_ttl_for_every_command(self):
        for command, payload in INPUTS.items():
            with self.subTest(command=command):
                self.login()
                saved = self.saved()
                self.server.mode = "command-rotate"
                result = self.run_cli(payload, command=command)
                self.assertEqual(result["outcome"], "ok", result)
                rotated = self.saved()
                self.assertNotEqual(rotated["cookies"], saved["cookies"])
                self.assertEqual(rotated["authenticated_at_ms"], saved["authenticated_at_ms"])

    def test_interloan_prepare_and_confirmed_submit_including_alias(self):
        self.login()
        result = self.run_cli(INTERLOAN, command="interloan-request")
        self.assertEqual(result["code"], "interloan-request-prepared")
        self.assertFalse(result["data"]["submit_attempted"])
        self.assertEqual(self.server.forms, [])
        self.assertEqual(result["data"]["prepared_payload"]["giveLibCode"], "141142")
        self.assertEqual(result["data"]["prepared_payload"]["userKey"], "9999999999")
        for command in ("interloan-request", "interlibrary-loan-request"):
            result = self.run_cli(dict(INTERLOAN, submit=True), command=command)
            self.assertEqual(result["outcome"], "ok", result)
            self.assertTrue(result["data"]["submit_attempted"])
            self.assertEqual(self.server.forms[-1]["aplLibCode"], ["141484"])
            self.assertEqual(urlsplit(self.server.requests[-1][0]).path, INTERLOAN_POST)

    def test_hope_prepare_and_submit_defaults_aliases_and_no_implicit_consent(self):
        self.login()
        result = self.run_cli({}, command="hope-book-request")
        payload = result["data"]["prepared_payload"]
        self.assertNotIn("smsReceiptYn", payload)
        self.assertNotIn("searchKeyword", payload)  # unrelated form must not leak
        self.assertEqual(payload["handPhone"], "010-1234-5678")
        self.assertEqual(self.server.forms, [])
        result = self.run_cli(dict(HOPE, submit=True), command="hope-book-request")
        self.assertEqual(result["outcome"], "ok", result)
        self.assertEqual(self.server.forms[-1]["title"], ["Fictional & +도서"])
        self.assertEqual(self.server.forms[-1]["mobileNo2"], ["9876"])
        self.assertEqual(self.server.forms[-1]["handPhone"], ["010-9876-5432"])
        self.assertEqual(self.server.forms[-1]["publishYear"], ["2026"])

    def test_invalid_hope_payload_does_not_submit(self):
        self.login()
        for field, value in (("title", ""), ("author", ""), ("publisher", ""),
                             ("sms_receipt_yn", "N"), ("price", "-1"), ("email", "bad"),
                             ("hand_phone", "bad"), ("manage_code", "INVALID")):
            with self.subTest(field=field):
                request = dict(HOPE["request"], **{field: value})
                result = self.run_cli(dict(HOPE, request=request, submit=True), command="hope-book-request")
                self.assertEqual(result["outcome"], "invalid-input", result)
                self.assertEqual(self.server.forms, [])

    def test_wrong_book_and_missing_interloan_fields_do_not_submit(self):
        self.login()
        for mode, outcome in (("command-wrong-book", "remote-error"),
                              ("command-missing-popup", "invalid-input")):
            self.server.mode = mode
            result = self.run_cli(dict(INTERLOAN, submit=True), command="interloan-request")
            self.assertEqual(result["outcome"], outcome, result)
            self.assertEqual(self.server.forms, [])

    def test_submission_requires_evidence_and_never_follows_redirects(self):
        for command, payload in (("interloan-request", INTERLOAN), ("hope-book-request", HOPE)):
            for mode in ("command-submit-unknown", "command-submit-rejected", "command-submit-cross-token", "command-submit-redirect",
                         "command-submit-http-error", "command-submit-logged-out"):
                with self.subTest(command=command, mode=mode):
                    self.login()
                    self.server.mode = mode
                    result = self.run_cli(dict(payload, submit=True), command=command)
                    self.assertNotEqual(result["outcome"], "ok", result)
                    if mode in ("command-submit-unknown", "command-submit-rejected", "command-submit-cross-token"):
                        self.assertEqual(result["outcome"], "submit-failed")
                    if mode == "command-submit-logged-out":
                        self.assertEqual(result["code"], "session-rejected")
                    self.assertEqual(len(self.server.forms), 1)
                    self.assertEqual(len(self.server.requests), 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
