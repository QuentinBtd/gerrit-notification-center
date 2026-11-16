"""Execute real release/verify orchestration with cheap recording gate boundaries."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ReleaseGatesTest(unittest.TestCase):
    def run_gates(self, delegated=False, failing_gate=""):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            scripts = root / "tools/scripts"
            scripts.mkdir(parents=True)
            for name in ["lib.sh", "release.sh", "verify.sh"]:
                shutil.copyfile(ROOT / "tools/scripts" / name, scripts / name)
            for name in ["fmt", "lint", "test", "build"]:
                (scripts / f"{name}.sh").write_text(
                    f'printf "%s\\n" "{name} $*" >> "$RECEIPT"\n[[ "$FAIL_GATE" != "{name}" ]]\n'
                )
            (scripts / "check_release_manifest.py").write_text("# Fixture boundary\n")
            (root / "version.txt").write_text("0.0.0\n")
            (root / ".release-please-manifest.json").write_text('{".": "0.0.0"}\n')
            (root / "CHANGELOG.md").write_text("## 0.0.0 (fixture)\n")
            binaries = root / "bin"
            binaries.mkdir()
            for name in ["npm", "prek"]:
                tool = binaries / name
                tool.write_text(f'#!/bin/sh\nprintf "%s\\n" "{name} $*" >> "$RECEIPT"\n')
                tool.chmod(0o755)
            for args in [
                ["init", "-q"],
                ["add", "."],
                [
                    "-c",
                    "user.name=Fixture",
                    "-c",
                    "user.email=fixture@example.org",
                    "commit",
                    "-qm",
                    "fixture",
                ],
            ]:
                subprocess.run(
                    ["git", *args],
                    cwd=root,
                    check=True,
                    capture_output=True,
                )
            receipt = root / ".git/gates.log"
            env = {
                **os.environ,
                "PATH": str(binaries) + os.pathsep + os.environ["PATH"],
                "RECEIPT": str(receipt),
                "FAIL_GATE": failing_gate,
                "RELEASE_SKIP_MATRIX": "1" if delegated else "0",
            }
            result = subprocess.run(
                ["bash", "tools/scripts/release.sh", "verify", "0.0.0"],
                cwd=root,
                env=env,
                capture_output=True,
                text=True,
            )
            return result, receipt.read_text().splitlines()

    def test_local_release_runs_required_matrix_exactly_once(self):
        result, gates = self.run_gates()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(gates.count("test matrix"), 1, gates)
        self.assertEqual(gates.count("build all"), 1, gates)

    def test_hosted_release_defers_only_acceptance_and_retains_every_unit_and_build(self):
        result, gates = self.run_gates(delegated=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            [g for g in gates if g.startswith("test ")],
            ["test unit 3.12", "test unit 3.13", "test unit 3.14"],
        )
        self.assertEqual(gates.count("build all"), 1, gates)
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        self.assertIn("uses: ./.github/workflows/integration.yml", workflow)
        self.assertIn("needs: [verify, integration]", workflow)
        integration = (ROOT / ".github/workflows/integration.yml").read_text()
        self.assertIn("series: ['3.12', '3.13', '3.14']", integration)
        self.assertIn('bash tools/scripts/test.sh acceptance "$SERIES"', integration)

    def test_required_gate_failure_blocks_both_release_modes(self):
        for delegated in [False, True]:
            for failing_gate in ["test", "build"]:
                with self.subTest(delegated=delegated, failing_gate=failing_gate):
                    result, _ = self.run_gates(delegated, failing_gate)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertNotIn("release 0.0.0 verified", result.stderr)
