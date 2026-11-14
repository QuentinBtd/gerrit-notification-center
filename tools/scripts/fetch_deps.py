#!/usr/bin/env python3
"""Fetch only bounded, checksum-pinned native inputs; cached sources remain read-only."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--series", choices=["3.12", "3.13", "3.14"], default="3.14")
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()
    r = json.loads((ROOT / "tools/gerrit-releases.json").read_text())
    release = r["series"][args.series]
    version = release["version"]
    base = "https://maven-central.storage-download.googleapis.com/maven2"
    deps = {
        "api.jar": (
            f"{base}/com/google/gerrit/gerrit-plugin-api/{version}/gerrit-plugin-api-{version}.jar",
            release["pluginApiSha256"],
            "gerrit-plugin-api.jar",
        ),
        "test.jar": (
            f"{base}/com/google/gerrit/gerrit-acceptance-framework/{version}/gerrit-acceptance-framework-{version}.jar",
            release["acceptanceFrameworkSha256"],
            "gerrit-acceptance-framework.jar",
        ),
    }
    for name, sha in r["bouncycastle"][release["bouncycastle"]].items():
        v = release["bouncycastle"]
        deps[name + ".jar"] = (
            f"{base}/org/bouncycastle/{name}-jdk18on/{v}/{name}-jdk18on-{v}.jar",
            sha,
            name + ".jar",
        )
    h2 = json.loads((ROOT / "tools/dependencies.json").read_text())["h2"]
    deps["h2.jar"] = (h2["url"], h2["sha256"], "h2.jar")
    args.out.mkdir(parents=True, exist_ok=True)
    cache = os.environ.get("GERRIT_DEPENDENCY_CACHE")
    for name, (url, sha, cached_name) in deps.items():
        dest = args.out / name
        if not dest.exists():
            cached = Path(cache) / cached_name if cache else None
            if cached and cached.is_file():
                if hashlib.sha256(cached.read_bytes()).hexdigest() != sha:
                    raise SystemExit(f"cached checksum mismatch: {cached}")
                dest.symlink_to(cached.resolve())
            else:
                partial = dest.with_suffix(".part")
                with urllib.request.urlopen(url, timeout=90) as response:
                    partial.write_bytes(response.read())
                if hashlib.sha256(partial.read_bytes()).hexdigest() != sha:
                    raise SystemExit(f"download checksum mismatch: {url}")
                partial.rename(dest)
        if hashlib.sha256(dest.read_bytes()).hexdigest() != sha:
            raise SystemExit(f"checksum mismatch: {dest}")
        print(name, sha)


if __name__ == "__main__":
    main()
