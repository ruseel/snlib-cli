#!/usr/bin/env python3
"""Offline process and native-store regressions. Requires moon, cc, and Python 3."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
BINARY = ROOT / "moonbit/_build/native/debug/build/cmd/snlib-cli/snlib-cli.exe"


class NativeCliTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        subprocess.run(
            ["moon", "-C", str(ROOT / "moonbit"), "build", "--target", "native"],
            check=True,
        )

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="snlib-regression-")
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        self.config = self.home / ".config/snlib-cli"
        self.config.mkdir(parents=True)
        self.env = dict(
            os.environ, HOME=str(self.home),
            XDG_CONFIG_HOME=str(self.home / ".config"),
            SNLIB_BASE_URL="http://127.0.0.1:1",
            NO_PROXY="127.0.0.1", no_proxy="127.0.0.1",
        )

    def run_cli(self, command, payload="{}"):
        result = subprocess.run(
            [str(BINARY), command], input=payload, text=True,
            capture_output=True, env=self.env, cwd=self.home, check=True,
        )
        return json.loads(result.stdout)

    def test_help_search_and_account_preserve_legacy_cookies(self):
        legacy = self.config / "session.edn"
        content = '{:last-login-at-ms 123 :cookies [{:name "JSESSIONID" :value "fictional"}]}'
        legacy.write_text(content)
        for command, payload, code in [
            ("--help", "{}", "usage"),
            ("search-books", '{"keyword":"moon"}', "http-request-failed"),
            ("my-info", "{}", "session-missing"),
        ]:
            with self.subTest(command=command):
                self.assertEqual(self.run_cli(command, payload)["code"], code)
                self.assertEqual(legacy.read_text(), content)
                self.assertFalse((self.config / "session.json").exists())

    def test_future_session_is_rejected(self):
        (self.config / "session.json").write_text(json.dumps({
            "version": 2, "patron_id": "fictional",
            "base_url": "http://127.0.0.1:1",
            "cookies": ["127.0.0.1\tFALSE\t/intro\tFALSE\t0\tAUTH\tfictional"],
            "authenticated_at_ms": int(time.time() * 1000) + 86400000,
            "remembered": True, "status": "active",
        }))
        result = self.run_cli("my-info")
        self.assertEqual(result["outcome"], "requires-login")
        self.assertEqual(result["code"], "session-expired")

    def test_invalid_inputs_are_reported_before_preflight(self):
        for command, payload in [
            ("login", '{"user_id":"","password":""}'),
            ("search-books", '{"keyword":""}'),
            ("search-books", '{"keyword":"moon","page":-1}'),
            ("search-books", '{"keyword":"moon","per_page":0}'),
        ]:
            with self.subTest(command=command, payload=payload):
                result = self.run_cli(command, payload)
                self.assertEqual(result["code"], "invalid-command-input")


class NativeStoreTest(unittest.TestCase):
    def test_sync_failure_closes_descriptor_and_preserves_original(self):
        moon_home = Path(os.environ.get("MOON_HOME", Path.home() / ".moon"))
        with tempfile.TemporaryDirectory(prefix="snlib-store-failure-") as tmp:
            tmp = Path(tmp)
            executable = tmp / "store-sync-failure"
            subprocess.run(
                shlex.split(os.environ.get("CC", "cc")) + [
                    "-Wall", "-Wextra", "-Werror",
                    "-I", str(moon_home / "include"), "-I", str(ROOT),
                    str(ROOT / "test/native/store_sync_failure.c"),
                    "-o", str(executable),
                ], check=True,
            )
            target = tmp / "session.json"
            target.write_text("original")
            subprocess.run([str(executable), str(target)], check=True)
            self.assertEqual(target.read_text(), "original")
            self.assertEqual(list(tmp.glob("session.json.tmp.*")), [])


if __name__ == "__main__":
    unittest.main()
