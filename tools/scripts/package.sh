#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
cd "$ROOT"
version="${NOTIFICATION_CENTER_VERSION:-$(plugin_version)}"
npm ci
bash tools/scripts/build.sh all
python3 -I - "$version" <<'PY'
import json, pathlib, subprocess, sys
v=sys.argv[1]
p=pathlib.Path('dist')
r=json.load(open('tools/gerrit-releases.json'))
info={'plugin':'notification-center','version':v,'sourceRevision':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'gerrit':r['series'],'h2':json.load(open('tools/dependencies.json'))['h2'],'builder':'Bazel '+pathlib.Path('.bazelversion').read_text().strip()}
(p/f'notification-center-{v}-build-info.json').write_text(json.dumps(info,indent=2)+'\n')
(p/f'notification-center-{v}-THIRD-PARTY.txt').write_text('Notification Center: Apache-2.0. Bundled H2 2.3.232: MPL-2.0 or EPL-1.0; upstream H2 LICENSE.txt retained in JAR. Gerrit API is neverlink and is not redistributed.\n')
PY
(
  cd dist
  sha256sum notification-center-"$version"-gerrit-*.jar notification-center-"$version"-build-info.json notification-center-"$version"-THIRD-PARTY.txt >"notification-center-$version.sha256"
)
