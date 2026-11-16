# Executed evidence

Implementation qualification on 2026-11-14, Linux, Java Temurin 21.0.12+8, Node 24.22.1.
Reference inspected read-only at `42f35fd2b6e6a8edffc2325d089c525f86de5788`.
Raw evidence directory (not committed):
`/root/.hermes/cache/gerrit-notification-center/`.

## TDD receipts

| Vertical slice | RED receipt | GREEN receipt |
| --- | --- | --- |
| Persistent H2 + duplicate/restart | 01-store-red.log (missing-interface compilation) | 02-store-green.log |
| Read/archive/filter/retention/future schema | 03-state-red.log (missing methods) | 04-state-green.log |
| Own-account visibility, count and pagination | 05-service-red.log (missing service) | 06-service-green.log |
| Recipient rules, mentions and fanout | 07-events-red.log (missing event interfaces) | 08-events-green.log |
| Real Gerrit plugin → event → REST → revoke | 09-plugin-red.log (missing REST class) | 12-plugin-it.log |
| PolyGerrit registration/full Inbox | 13/14-frontend-*-red.log (missing bundle source) | 16-frontend-green.log |
| Native packaging membership/determinism | 17-package-red.log (missing packager) | tools/tests/test_package.py executed successfully |
| Explicit /test authorization workflow | 18-ci-red.log (missing workflow) | 26-lint.log includes both policy cases |
| Inline mentions through real Gerrit | 20-expanded-it.log (executed behavior: zero mentions) | 24-inline-green.log (2 cases) |
| Durable comment-ID association | 23-inline-identity-red.log (missing method) | 30-series-unit.log campaigns |

Missing-interface compile REDs are explicitly not described as executed behavioral failures.
Retained harness corrections: 11-plugin-it.log expected 200 but endpoint correctly returned
204; the fixture now asserts 204. 15-frontend-green.log exposed cross-realm deep equality in
happy-dom; serialized payload equality replaced that harness comparison without weakening
its expected action/id. 21/22 diagnostic logs confirmed inline timestamps round to seconds
while event timestamps contain milliseconds; temporary diagnostic source was removed.
The v1→v2 migration fixture is supplementary qualification, not retroactive test-first proof.

## Verified scope

- `native.sh unit SERIES`: **10 Java unit cases** per series, final receipts
  `30-3.12-unit.log`, `30-3.13-unit.log`, `30-3.14-unit.log`.
- `native.sh acceptance SERIES`: **2 real lightweight Gerrit daemon cases** per series:
  `25-3.12-acceptance.log`, `25-3.13-acceptance.log`, `24-inline-green.log` (3.14).
  Covers reviewer event, top-level/inline mention, patchset, merge, abandon, REST read/archive,
  invalid input, anonymous/cross-account denial, current private-Change visibility revocation.
- `native.sh build SERIES`: final `30-series-build.log`; JAR manifest/membership verified with
  check_jar.py. H2 license membership explicitly inspected. Frontend/H2 included, APIs excluded.
- `npm test`: 2 DOM cases passed, final `28-ui-tests.log`; `npm run build`: strict TypeScript
  compilation and esbuild bundle passed.
- `node --test tools/ci/*.test.mjs`: 2 authorization/static-policy cases passed (26-lint.log).
- `python3 -I -m unittest discover -s tools/tests -v`: 1 deterministic packaging fixture passed.
- `fmt.sh --check`, shellcheck, ruff, buildifier, actionlint and release manifest checks passed;
  exact pinned prek 0.5.5 hooks passed (27-hooks.log). No live GitHub Actions run was requested.

Native acceptance emits upstream framework log4j/no-doc-index/temporary-site warnings; these
are retained, not represented as warning-free logs. No Docker/SSH/browser test is hidden in
these totals. Repeated runs are not additional unique cases.

## Review correction follow-up (2026-11-16)

Fresh correction receipts are under `review-fixes/` in the same external evidence directory;
the initial candidate and independent-review packet are unchanged. The original results above
remain historical, not a fresh compatibility claim for this correction.

- N1: `N1-red-isolated.log` executes the original listener and fails the summary-mention /
  unrelated-inline URL assertion (3 cases, 1 failure); `N1-green.log` passes the identical
  regression. The first `N1-red.log` also exposed shared-daemon inbox fixture contamination;
  the regression now hides its Change in finally. A mixed-inline/smallest-UUID case is
  supplementary qualification, not retroactive test-first proof.
- N2: `N2-red.log` fails at the missing rearmed timer after a rejected save. `N2-green.log`
  passes 3 DOM cases, including a fired bounded read poll, no mutation replay and detach cleanup.
- N3: `N3-red.log` accepts an otherwise valid synthetic JAR lacking the H2 notice;
  `N3-green.log` passes 2 packaging/guard fixtures after making the notice mandatory.
- N4: `N4-red.log` demonstrates duplicate local matrices and non-deferred hosted acceptance;
  `N4-green.log` passes 3 orchestration fixtures. These execute real release/verify scripts
  with recording gate boundaries, not Bazel or hosted CI. Required test/build failure
  propagation and the required exact-ref integration dependency are retained.
- `final-3.14-unit.log`, `final-3.14-acceptance.log`, `final-3.14-build.log`: fresh pinned
  native compilation, **10 unit + 4 in-process acceptance cases** and development JAR build,
  all exit 0 under the shared heavy-build lock with checksum-verified existing dependencies.
- `lint.log`: shell/Python/Bazel/workflow static checks, 2 CI-policy + 5 Python fixture cases,
  and TypeScript checking pass. Python fixtures are now included in the regular lint gate.
- `jar-guard.log` / `jar-readback.json`: the actual native JAR passes the guard; bundled H2
  notice and frontend bytes match source/build, expected manifest identity matches, and
  Gerrit/JUnit/Guice API classes are excluded. This is not canonical Bazel artifact proof.

No fresh older-series, packaged-server, real PolyGerrit, hosted-CI or release qualification
is inferred. Large-inbox performance and tool-cache integrity remain explicitly unqualified.

## Native artifacts (development evidence, not published releases)

| JAR suffix | SHA-256 |
| --- | --- |
| gerrit-3.12.jar | `87ce5e6c7c0b5e912ff823d85cb987d89a6c41e5b6c49f1313daa8d1b45ca8c5` |
| gerrit-3.13.jar | `49ffb52f1b66a2231753cda3f2b8cc49079601151126a9326962dc21a5f5d50b` |
| gerrit-3.14.jar | `fa902953e8c567979f46722b755cbaca7e134c818721717bf4468063c0e32cc2` |

Full names are `notification-center-0.0.0-<suffix>`; artifacts and native-builds.json are copied
to the evidence directory's artifacts/ subdirectory. No source/runtime caches from the reference
were modified. The canonical Bazel/packaged-server/browser/hosted-CI gates remain pending;
see compatibility.md. Independent QA owns the frozen-candidate campaign and publication decision.
