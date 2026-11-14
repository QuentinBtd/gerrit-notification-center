# Development

Canonical toolchain follows the reference: Java Temurin 21.0.12+8, Bazelisk 1.29.0,
Bazel 8.8.1, mise 2026.9.18; Node 24.22.1. Exact Gerrit inputs and hashes live in
`tools/gerrit-releases.json`; H2 2.3.232 is pinned in MODULE and tools/dependencies.json.
Frontend TypeScript, esbuild and DOM-test dependencies are pinned in package-lock.json.

```sh
mise trust && mise install
mise run setup
mise run hooks:install
mise run fmt
mise run lint
npm test
mise run test
mise run test:integration
mise run verify
```

Tasks delegate to tools/scripts so local and CI share entry points. Activate mise before
using Git hooks. Full verification includes formatting, static checks, frontend tests/build,
release-manifest consistency, Java unit/acceptance matrix and packaged Bazel matrix.
The build script prepares `build/frontend/static/notification-center.js` before Bazel;
direct Bazel invocations require `npm run build` first. Generated frontend is never committed.

The standalone Bazel graph adapts the reference's neverlink API and per-series flag. It adds
bundled H2 and a frontend resource library. This candidate intentionally uses lockfile update
mode until its changed graph can be resolved on a suitably provisioned build host; do not
copy the reference's stale module-extension lock. A validated generated MODULE.bazel.lock and
mise.lock are still release gates. No container/whole Gerrit source checkout is needed for
native qualification.

## Bounded native fallback

Linux, Bash, Python 3, Java 21, Node/npm and flock are required. Resolve tools individually on
limited machines; do not let a blanket toolchain installation exhaust disk.

```sh
npm ci && npm test && npm run build
export JAVA_HOME=/path/to/java21
# Optional directory with gerrit-plugin-api.jar, gerrit-acceptance-framework.jar and bc*.jar:
export GERRIT_DEPENDENCY_CACHE=/path/to/read-only/verified-cache
# Optional shared-host lock; always use it when another JVM build shares the machine:
export NOTIFICATION_BUILD_LOCK=/path/to/local-heavy-build.lock
bash tools/scripts/native.sh unit 3.14
bash tools/scripts/native.sh acceptance 3.14
bash tools/scripts/native.sh build 3.14
python3 -I tools/scripts/check_jar.py dist/notification-center-0.0.0-gerrit-3.14.jar 0.0.0 3.14.4
```

Native input fetching checks every SHA before symlinking a cached artifact or using a download.
Only project-owned build outputs are replaced. Override NOTIFICATION_DEPS for an existing
verified dependency directory; it must contain or admit the pinned api.jar/test.jar/H2/BC files.
Repeat per series with the corresponding cache directory. Native JAR construction is
deterministic and verifies frontend/H2/manifest membership, but is not the canonical Bazel
release path. Unit tests and in-process Gerrit acceptance are actual executions, not mocks
of Gerrit REST. Browser DOM tests use happy-dom and mocked REST boundaries and do not replace
real PolyGerrit qualification.
