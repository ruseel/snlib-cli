#!/usr/bin/env python3
"""Offline release-script contracts. gh and clawhub are mocked; never publishes."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ["snlib-cli-darwin-arm64",
          "snlib-cli-linux-amd64", "snlib-cli-linux-arm64"]


@unittest.skipUnless(shutil.which("bb"), "Babashka is required")
class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="snlib release ")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        self.script = scripts / "clawhub-release.bb"
        shutil.copy2(ROOT / "scripts/clawhub-release.bb", self.script)
        tables = self.root / "impl/clj/src/snlib"
        tables.mkdir(parents=True)
        for name in ("lib-code", "manage-code"):
            (tables / f"{name}.edn").write_text('{:fixture "fictional"}\n')
        self.references = self.root / "impl/skills/snlib-cli/references"
        self.tools = self.root / "tools"
        self.tools.mkdir()
        self.log = self.root / "publish.json"
        self.tool("gh", "#!/usr/bin/env python3\n"
                  "import os, pathlib, sys\n"
                  "args = sys.argv[1:]\n"
                  "assert args[:2] in (['release', 'view'], ['release', 'download'])\n"
                  "assert args[2] == 'v1.2.3'\n"
                  "assert args[args.index('--repo') + 1] == 'ruseel/snlib-cli'\n"
                  "if os.environ.get('TEST_GH_FAIL'): sys.exit(1)\n"
                  "if args[1] == 'view': print(os.environ.get('TEST_DRAFT', 'false'))\n"
                  "else:\n"
                  "    assert args[args.index('--pattern') + 1] == 'SHA256SUMS'\n"
                  "    destination = pathlib.Path(args[args.index('--dir') + 1]) / 'SHA256SUMS'\n"
                  "    destination.write_text(os.environ['TEST_SUMS'])\n")
        self.tool("clawhub", "#!/usr/bin/env python3\n"
                  "import json, os, pathlib, sys\n"
                  "args = sys.argv[1:]\n"
                  "assert args[0] == 'publish'\n"
                  "assert (pathlib.Path(args[1]) / 'references/native-release.txt').is_file()\n"
                  "pathlib.Path(os.environ['TEST_PUBLISH_LOG']).write_text(json.dumps(args))\n")
        self.env = dict(os.environ, PATH=str(self.tools) + os.pathsep + os.environ["PATH"],
                        TEST_SUMS="".join(f"{'a' * 64}  {asset}\n" for asset in ASSETS),
                        TEST_PUBLISH_LOG=str(self.log))
        for key in ("TEST_GH_FAIL", "TEST_DRAFT"):
            self.env.pop(key, None)

    def tool(self, name, content):
        path = self.tools / name
        path.write_text(content)
        path.chmod(0o755)

    def run_release(self, *args):
        return subprocess.run(["bb", str(self.script), *args], text=True,
                              capture_output=True, cwd=self.root, env=self.env, timeout=20)

    def test_prepare_pins_release_and_generates_references(self):
        result = self.run_release("prepare", "--version", "1.2.3")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.references / "native-release.txt").read_text(),
                         "v1.2.3\n" + self.env["TEST_SUMS"])
        self.assertIn("impl/clj/src/snlib/lib-code.edn",
                      (self.references / "lib-code.md").read_text())
        self.assertFalse(self.log.exists())

    def test_publish_target_version_and_comma_separated_tags(self):
        result = self.run_release("publish", "--version", "1.2.3",
                                  "--tags", "latest, stable", "--changelog", "Test changes")
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads(self.log.read_text())
        self.assertEqual(Path(args[1]), self.root / "impl/skills/snlib-cli")
        self.assertEqual(args[args.index("--version") + 1], "1.2.3")
        self.assertEqual(args[args.index("--tags") + 1], "latest,stable")
        self.assertEqual(args.count("--tags"), 1)
        self.assertEqual(args[args.index("--changelog") + 1], "Test changes")

    def test_invalid_versions_never_publish(self):
        for args in ((), ("--version", "v1.2.3"), ("--version", "../../bad")):
            result = self.run_release("publish", *args)
            self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.log.exists())
        self.assertFalse(self.references.exists())

    def test_invalid_checksums_never_publish(self):
        valid = self.env["TEST_SUMS"]
        for content in ("", valid.replace("a" * 64, "bad"), valid + valid,
                        valid.replace(ASSETS[0], "../bad"), valid.splitlines()[0] + "\n"):
            self.env["TEST_SUMS"] = content
            result = self.run_release("publish", "--version", "1.2.3")
            self.assertNotEqual(result.returncode, 0, content)
            self.assertFalse(self.log.exists())
            self.assertFalse(self.references.exists())

    def test_missing_release_or_draft_never_publishes(self):
        self.env["TEST_GH_FAIL"] = "1"
        self.assertNotEqual(self.run_release("publish", "--version", "1.2.3").returncode, 0)
        del self.env["TEST_GH_FAIL"]
        self.env["TEST_DRAFT"] = "true"
        self.assertNotEqual(self.run_release("publish", "--version", "1.2.3").returncode, 0)
        self.assertFalse(self.log.exists())
        self.assertFalse(self.references.exists())


if __name__ == "__main__":
    unittest.main()
