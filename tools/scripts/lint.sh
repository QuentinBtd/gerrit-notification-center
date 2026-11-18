#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
cd "$ROOT"
files() { git ls-files -z --cached --others --exclude-standard -- "$@"; }
files '*.sh' | xargs -0 -r shellcheck -x
files '*.py' | xargs -0 -r ruff check
files ':(glob)**/BUILD.bazel' ':(glob)**/*.bzl' 'MODULE.bazel' | xargs -0 -r buildifier -mode=check -lint=warn -warnings=-module-docstring
actionlint
python3 -I -m unittest discover -s tools/tests -v
npx tsc --noEmit
