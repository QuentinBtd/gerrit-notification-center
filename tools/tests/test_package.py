"""Packaging contract; synthetic fixture, never distributed as a plugin."""

import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class PackagingTest(unittest.TestCase):
    def test_artifact_guard_requires_h2_license_in_otherwise_valid_jar(self):
        spec = importlib.util.spec_from_file_location("guard", ROOT / "tools/scripts/check_jar.py")
        guard = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(guard)
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp) / "plugin.jar"
            with zipfile.ZipFile(out, "w") as jar:
                fields = {**guard.REQUIRED, "Implementation-Version": "0.0.0", "Gerrit-ApiVersion": "3.14.4"}
                jar.writestr("META-INF/MANIFEST.MF", "\r\n".join(f"{k}: {v}" for k, v in fields.items()))
                for name in [
                    "LICENSE",
                    "static/notification-center.js",
                    "Documentation/about.md",
                    "org/h2/Driver.class",
                    "io/github/quentinbtd/gerrit/notifications/Module.class",
                ]:
                    jar.writestr(name, b"fixture")
            with self.assertRaisesRegex(SystemExit, "missing META-INF/licenses/H2-LICENSE.txt"):
                guard.main(out, "0.0.0", "3.14.4")
            with zipfile.ZipFile(out, "a") as jar:
                jar.writestr("META-INF/licenses/H2-LICENSE.txt", "fixture notice")
            guard.main(out, "0.0.0", "3.14.4")

    def test_embeds_frontend_driver_and_manifest_without_signature_or_api(self):
        spec = importlib.util.spec_from_file_location("packaging", ROOT / "tools/scripts/native_package.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp)
            classes = p / "classes/io/github/quentinbtd/gerrit/notifications"
            classes.mkdir(parents=True)
            (classes / "Module.class").write_bytes(b"fixture")
            h2 = p / "h2.jar"
            with zipfile.ZipFile(h2, "w") as jar:
                jar.writestr("org/h2/Driver.class", b"driver fixture")
                jar.writestr("META-INF/X.SF", "signature")
                jar.writestr("META-INF/services/java.sql.Driver", "org.h2.Driver")
            js = p / "frontend.js"
            js.write_text("fixture frontend")
            license = p / "LICENSE"
            license.write_text("fixture license")
            out = p / "plugin.jar"
            module.package(out, p / "classes", h2, js, license, "0.0.0", "3.14.4")
            baseline = out.read_bytes()
            module.package(out, p / "classes", h2, js, license, "0.0.0", "3.14.4")
            self.assertEqual(baseline, out.read_bytes())
            with zipfile.ZipFile(out) as jar:
                self.assertIn("static/notification-center.js", jar.namelist())
                self.assertIn("org/h2/Driver.class", jar.namelist())
                self.assertNotIn("META-INF/X.SF", jar.namelist())
                self.assertIn(
                    "Gerrit-PluginName: notification-center", jar.read("META-INF/MANIFEST.MF").decode()
                )
