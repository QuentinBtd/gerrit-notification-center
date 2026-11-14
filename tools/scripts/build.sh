#!/usr/bin/env bash
# Canonical Bazel build; npm ci is a setup prerequisite.
source "$(dirname "$0")/lib.sh"
cd "$ROOT"
npm run build >&2
series=("${@:-3.14}")
[[ "${1:-}" == all ]] && series=("${SERIES_ALL[@]}")
mkdir -p dist
for s in "${series[@]}"; do
  check_series "$s"
  bazel build --config="gerrit-$s" --repo_env=NOTIFICATION_CENTER_VERSION="${NOTIFICATION_CENTER_VERSION:-}" //:notification_center_deploy.jar
  version="${NOTIFICATION_CENTER_VERSION:-$(plugin_version)}"
  out="dist/notification-center-$version-gerrit-$s.jar"
  cp bazel-bin/notification_center_deploy.jar "$out"
  python3 -I tools/scripts/check_jar.py "$out" "$version" "$(gerrit_release "$s")" >&2
  printf '%s\n' "$ROOT/$out"
done
