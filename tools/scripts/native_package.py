#!/usr/bin/env python3
"""Deterministic native fallback JAR. Canonical release builds use Bazel."""

import json
import os
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def package(out, classes, h2, js, license_file, version, gerrit):
    entries = {}
    for source in sorted(classes.rglob("*.class")):
        entries[source.relative_to(classes).as_posix()] = source.read_bytes()
    with zipfile.ZipFile(h2) as jar:
        for name in jar.namelist():
            if (
                name.endswith("/")
                or name == "META-INF/MANIFEST.MF"
                or name.upper().endswith((".SF", ".RSA", ".DSA"))
            ):
                continue
            entries[name] = jar.read(name)
    manifest = {
        "Manifest-Version": "1.0",
        "Gerrit-PluginName": "notification-center",
        "Gerrit-ApiType": "plugin",
        "Gerrit-ReloadMode": "restart",
        "Gerrit-Module": "io.github.quentinbtd.gerrit.notifications.Module",
        "Gerrit-ApiVersion": gerrit,
        "Implementation-Version": version,
        "Implementation-Title": "notification-center",
    }
    entries["META-INF/MANIFEST.MF"] = (
        "\r\n".join(f"{k}: {v}" for k, v in manifest.items()) + "\r\n\r\n"
    ).encode()
    entries["LICENSE"] = license_file.read_bytes()
    resources = ROOT / "src/main/resources"
    if resources.exists():
        for resource in sorted(resources.rglob("*")):
            if resource.is_file():
                entries[resource.relative_to(resources).as_posix()] = resource.read_bytes()
    entries["static/notification-center.js"] = js.read_bytes()
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as jar:
        for name, content in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            jar.writestr(info, content)


if __name__ == "__main__":
    series, classes, h2 = sys.argv[1:]
    version = os.environ.get("NOTIFICATION_CENTER_VERSION", (ROOT / "version.txt").read_text().strip())
    release = json.loads((ROOT / "tools/gerrit-releases.json").read_text())["series"][series]["version"]
    out = ROOT / f"dist/notification-center-{version}-gerrit-{series}.jar"
    package(
        out,
        Path(classes),
        Path(h2),
        ROOT / "build/frontend/static/notification-center.js",
        ROOT / "LICENSE",
        version,
        release,
    )
    print(out)
