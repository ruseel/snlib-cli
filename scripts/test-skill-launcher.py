#!/usr/bin/env python3
"""Offline launcher contract tests; no real credentials or sessions are used."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
LAUNCHER = ROOT / "skills/snlib-cli/scripts/snlib-cli.sh"


class LauncherTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="snlib skill ")
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.bin = self.base / "bin"
        self.bin.mkdir()
        moon = self.bin / "moon"
        moon.write_text(
            "#!/usr/bin/env python3\n"
            "import json, sys\n"
            "print(json.dumps({'args': sys.argv[1:], 'stdin': sys.stdin.read()}))\n"
        )
        moon.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"])
        self.env.pop("SNLIB_MOONBIT_DIR", None)

    def run_launcher(self, *args, launcher=LAUNCHER, payload="{}"):
        return subprocess.run(
            [str(launcher), *args], input=payload, text=True,
            capture_output=True, cwd=self.base, env=self.env, check=False,
        )

    def test_repository_discovery_and_stdin(self):
        payload = '{"keyword":"키워드"}'
        result = self.run_launcher("search-books", payload=payload)
        self.assertEqual(result.returncode, 0, result.stderr)
        output = json.loads(result.stdout)
        args = output["args"]
        self.assertEqual(args[0], "-C")
        self.assertEqual(Path(args[1]).resolve(), ROOT / "moonbit")
        self.assertEqual(args[2:], ["run", "--target", "native", "cmd/snlib-cli", "--", "search-books"])
        self.assertEqual(output["stdin"], payload)

    def test_default_help(self):
        result = self.run_launcher(payload="")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["args"][-1], "--help")

    def test_explicit_source_path_with_spaces(self):
        project = self.base / "custom moonbit"
        (project / "cmd/snlib-cli").mkdir(parents=True)
        (project / "moon.mod").write_text("")
        (project / "cmd/snlib-cli/main.mbt").write_text("")
        self.env["SNLIB_MOONBIT_DIR"] = str(project)
        result = self.run_launcher("my-info")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["args"][1], str(project))

    def test_invalid_source_path(self):
        self.env["SNLIB_MOONBIT_DIR"] = str(self.base / "missing")
        result = self.run_launcher("--help")
        self.assertEqual(result.returncode, 1)
        self.assertIn("Set SNLIB_MOONBIT_DIR", result.stderr)
        self.assertEqual(result.stdout, "")

    def test_separately_installed_bundle_needs_source(self):
        launcher = self.base / "bundle/scripts/snlib-cli.sh"
        launcher.parent.mkdir(parents=True)
        launcher.write_bytes(LAUNCHER.read_bytes())
        launcher.chmod(0o755)
        result = self.run_launcher("--help", launcher=launcher)
        self.assertEqual(result.returncode, 1)
        self.assertIn("MoonBit source not found", result.stderr)

    def test_legacy_flags_are_rejected(self):
        result = self.run_launcher("search-books", "--keyword", "book")
        self.assertEqual(result.returncode, 2)
        self.assertIn("not CLI flags", result.stderr)
        self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
