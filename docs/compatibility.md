# Compatibility qualification

| Gerrit exact release | Java | Native compile / JAR | Unit | In-process acceptance | Canonical Bazel | Packaged server / browser |
| --- | --- | --- | --- | --- | --- | --- |
| 3.14.4 | 21 | passed | 10 passed | 2 passed | pending | pending |
| 3.13.10 | 21 | passed | 10 passed | 2 passed | pending | pending |
| 3.12.11 | 21 | passed | 10 passed | 2 passed | pending | pending |

3.14 is the priority/reference series. Use one series-specific JAR, not the 3.14 JAR on
an earlier server. Exact release/source/artifact checksums come from the inspected reference
matrix (`tools/gerrit-releases.json`), not guessed versions. Passing these exact releases does
not prove every patch of a series or production support. No Core patch is required.

The public frontend contracts (footer-left, screen, REST get/post/getLoggedIn) were checked
against pinned Gerrit sources; TypeScript and DOM tests pass. Public header-title is unsuitable
for a nested interactive bell; header-top-right exists in source but is not in the documented
endpoint contract. No source-only endpoint is used.

## Remaining release gates

- Execute the canonical changed Bazel graph on an admitted build host and commit its validated
  module/tool lockfiles. The limited coordinator had roughly 1.2 GiB free after bounded tests;
  no whole dependency graph or Docker image was downloaded. The authorized remote's warm-cache
  assessment belongs to independent QA, not an invented blocker or support claim.
- Load each packaged JAR into the corresponding real Gerrit server and check reload/resource
  lifetime, external HTTP auth/XSRF and real PolyGerrit screen/navigation/polling/actions.
- Browser theme/mobile/keyboard inspection, screenshots and exact-SHA independent review.
- Activate/test default-branch comment workflows and Release Please only with authorization.

No hosted CI success, release, merge or production readiness is claimed. See test-evidence
for exact executed scope, retained RED failures and native artifact hashes.
