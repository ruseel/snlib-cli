#!/usr/bin/env python3
"""Offline prebuilt launcher tests. No network, real credentials, or MoonBit."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
LAUNCHER = ROOT / "impl/skills/snlib-cli/scripts/snlib-cli.sh"
ASSET = "snlib-cli-linux-amd64"


class LauncherTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="snlib skill ")
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.bin = self.base / "tools"
        self.bin.mkdir()
        self.bundle = self.base / "standalone bundle"
        self.launcher = self.bundle / "scripts/snlib-cli.sh"
        self.launcher.parent.mkdir(parents=True)
        shutil.copy2(LAUNCHER, self.launcher)
        self.manifest = self.bundle / "references/native-release.txt"
        self.manifest.parent.mkdir()
        self.payload_binary = self.base / "trusted binary"
        self.payload_binary.write_text(
            "#!/usr/bin/env python3\n"
            "import json, sys\n"
            "print(json.dumps({'args': sys.argv[1:], 'stdin': sys.stdin.read()}))\n"
        )
        self.payload_binary.chmod(0o755)
        self.digest = hashlib.sha256(self.payload_binary.read_bytes()).hexdigest()
        self.manifest.write_text(f"v1.2.3\n{self.digest}  {ASSET}\n")
        self.log = self.base / "downloads.jsonl"
        self.tool("uname", "#!/usr/bin/env bash\n"
                  'if [[ "$1" == "-s" ]]; then echo "${TEST_OS:-Linux}"; '
                  'else echo "${TEST_ARCH:-x86_64}"; fi\n')
        self.tool("curl", "#!/usr/bin/env python3\n"
                  "import json, os, pathlib, shutil, sys\n"
                  "args = sys.argv[1:]\n"
                  "with open(os.environ['TEST_LOG'], 'a') as f:\n"
                  "    f.write(json.dumps({'args': args, 'stdin': sys.stdin.read()}) + '\\n')\n"
                  "if os.environ.get('TEST_CURL_FAIL'): sys.exit(22)\n"
                  "shutil.copyfile(os.environ['TEST_BINARY'], args[args.index('--output') + 1])\n")
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        HOME=str(self.base), XDG_CACHE_HOME=str(self.base / "cache"),
                        TEST_BINARY=str(self.payload_binary), TEST_LOG=str(self.log))
        for key in ("SNLIB_CLI_BINARY", "SNLIB_MOONBIT_DIR", "TEST_OS", "TEST_ARCH", "TEST_CURL_FAIL"):
            self.env.pop(key, None)

    def tool(self, name, content):
        path = self.bin / name
        path.write_text(content)
        path.chmod(0o755)

    def run_launcher(self, *args, payload="{}"):
        return subprocess.run([str(self.launcher), *args], input=payload, text=True,
                              capture_output=True, cwd=self.base, env=self.env, timeout=10)

    def cached_binary(self):
        return self.base / f"cache/snlib-cli/v1.2.3/{self.digest}/{ASSET}"

    def test_download_verify_cache_and_preserve_stdin(self):
        payload = '{"user_id":"fixture","password":"dummy & + 비밀번호"}'
        result = self.run_launcher("login", payload=payload)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), {"args": ["login"], "stdin": payload})
        self.assertEqual(result.stderr, "")
        self.assertEqual(self.cached_binary().read_bytes(), self.payload_binary.read_bytes())
        request = json.loads(self.log.read_text())
        self.assertEqual(request["stdin"], "")
        self.assertIn(f"https://github.com/ruseel/snlib-cli/releases/download/v1.2.3/{ASSET}",
                      request["args"])
        self.assertIn("--proto-redir", request["args"])
        self.assertEqual(self.run_launcher("my-info").returncode, 0)
        self.assertEqual(len(self.log.read_text().splitlines()), 1)

    def test_default_help_and_restore_executable_bit(self):
        result = self.run_launcher(payload="")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["args"], ["--help"])
        self.cached_binary().chmod(0o600)
        self.assertEqual(self.run_launcher().returncode, 0)

    def test_download_checksum_mismatch_is_never_executed(self):
        self.payload_binary.write_text("corrupted")
        result = self.run_launcher("login", payload='{"password":"dummy-secret"}')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("checksum mismatch", result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertNotIn("dummy-secret", result.stderr)
        self.assertFalse(self.cached_binary().exists())
        self.assertEqual(list(self.cached_binary().parent.glob(".download.*")), [])

    def test_cache_checksum_mismatch_is_never_executed(self):
        self.assertEqual(self.run_launcher().returncode, 0)
        self.cached_binary().write_text("corrupted")
        result = self.run_launcher()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Cached binary checksum mismatch", result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertEqual(len(self.log.read_text().splitlines()), 1)

    def test_download_failure_leaves_no_executable(self):
        self.env["TEST_CURL_FAIL"] = "1"
        result = self.run_launcher()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.cached_binary().exists())
        self.assertEqual(list(self.cached_binary().parent.glob(".download.*")), [])

    def test_local_override_with_spaces_never_downloads(self):
        self.env["SNLIB_CLI_BINARY"] = str(self.payload_binary)
        self.manifest.unlink()
        result = self.run_launcher("search-books", payload='{"keyword":"키워드"}')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["args"], ["search-books"])
        self.assertFalse(self.log.exists())

    def test_invalid_local_override(self):
        for path in ("relative/path", str(self.base / "missing")):
            self.env["SNLIB_CLI_BINARY"] = path
            result = self.run_launcher()
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("absolute path to a trusted executable", result.stderr)
        self.assertFalse(self.log.exists())

    def test_missing_or_invalid_manifest_never_downloads(self):
        for content in ("../../bad\n", "v1.2.3\n", f"v1.2.3\n{'a' * 63}  {ASSET}\n"):
            self.manifest.write_text(content)
            self.assertNotEqual(self.run_launcher().returncode, 0)
        self.manifest.unlink()
        self.assertIn("Release manifest missing", self.run_launcher().stderr)
        self.assertFalse(self.log.exists())

    def test_platform_mapping(self):
        for system, arch, asset in (("Darwin", "arm64", "snlib-cli-darwin-arm64"),
                                    ("Linux", "aarch64", "snlib-cli-linux-arm64")):
            with self.subTest(system=system, arch=arch):
                self.env.update(TEST_OS=system, TEST_ARCH=arch)
                self.manifest.write_text(f"v1.2.3\n{self.digest}  {asset}\n")
                result = self.run_launcher()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(asset, self.log.read_text())

    def test_unsupported_platform_never_downloads(self):
        self.env.update(TEST_OS="Darwin", TEST_ARCH="x86_64")
        self.assertIn("Intel macOS is not supported", self.run_launcher().stderr)
        self.env["TEST_OS"] = "Windows"
        self.assertIn("Supported platforms", self.run_launcher().stderr)
        self.env.update(TEST_OS="Linux", TEST_ARCH="i686")
        self.assertIn("Supported architectures", self.run_launcher().stderr)
        self.assertFalse(self.log.exists())

    def test_legacy_flags_are_rejected_before_download(self):
        result = self.run_launcher("search-books", "--keyword", "book")
        self.assertEqual(result.returncode, 2)
        self.assertIn("not CLI flags", result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertFalse(self.log.exists())


if __name__ == "__main__":
    unittest.main()
