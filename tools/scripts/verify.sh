#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
cd "$ROOT"
# Hosted releases defer acceptance only to their required integration job.
mode="${1:-full}"
[[ "$mode" == full || "$mode" == --defer-acceptance ]] || die 'expected full or --defer-acceptance'
bash tools/scripts/fmt.sh --check
bash tools/scripts/lint.sh
prek run --all-files --show-diff-on-failure
npm test
npm run build
python3 -I tools/scripts/check_release_manifest.py
if [[ "$mode" == --defer-acceptance ]]; then
  for series in "${SERIES_ALL[@]}"; do
    bash tools/scripts/test.sh unit "$series"
  done
else
  bash tools/scripts/test.sh matrix
fi
bash tools/scripts/build.sh all
