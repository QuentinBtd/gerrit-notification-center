#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
cd "$ROOT"
npm run build
mode="${1:-unit}"
series="${2:-3.14}"
if [[ "$mode" == matrix ]]; then
  for s in "${SERIES_ALL[@]}"; do
    bash tools/scripts/test.sh unit "$s"
    bash tools/scripts/test.sh acceptance "$s"
  done
else
  check_series "$series"
  [[ "$mode" == integration ]] && mode=acceptance
  [[ "$mode" == unit || "$mode" == acceptance ]] || die 'expected unit, acceptance or matrix'
  bazel test --config="gerrit-$series" --test_tag_filters="$mode" //src/test/...
fi
