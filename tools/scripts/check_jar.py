#!/usr/bin/env python3
"""Verify identity, frontend, embedded H2 and absence of bundled Gerrit classes."""

import sys
import zipfile

REQUIRED = {
    "Gerrit-PluginName": "notification-center",
    "Gerrit-ApiType": "plugin",
    "Gerrit-ReloadMode": "restart",
    "Gerrit-Module": "io.github.quentinbtd.gerrit.notifications.Module",
}


def main(path, version, release):
    with zipfile.ZipFile(path) as jar:
        raw = jar.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
        manifest = dict(line.split(": ", 1) for line in raw.splitlines() if ": " in line)
        for key, value in {
            **REQUIRED,
            "Implementation-Version": version,
            "Gerrit-ApiVersion": release,
        }.items():
            if manifest.get(key) != value:
                raise SystemExit(f"incorrect {key}: {manifest.get(key)!r}")
        names = set(jar.namelist())
        for name in [
            "LICENSE",
            "META-INF/licenses/H2-LICENSE.txt",
            "static/notification-center.js",
            "Documentation/about.md",
            "org/h2/Driver.class",
            "io/github/quentinbtd/gerrit/notifications/Module.class",
        ]:
            if name not in names:
                raise SystemExit(f"missing {name}")
        if any(n.startswith(("com/google/gerrit/", "org/junit/", "com/google/inject/")) for n in names):
            raise SystemExit("server or test API bundled")
    print(f"{path}: verified ({version}, Gerrit {release})")


if __name__ == "__main__":
    main(*sys.argv[1:])
