#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
cd "$ROOT"
bash tools/scripts/fmt.sh --check
bash tools/scripts/lint.sh
prek run --all-files --show-diff-on-failure
npm test
npm run build
python3 -I tools/scripts/check_release_manifest.py
bash tools/scripts/test.sh matrix
bash tools/scripts/build.sh all
