# Compatibility qualification

| Gerrit exact release | Java | Canonical Bazel JAR | Canonical unit cases | Canonical in-process cases | Packaged server / real PolyGerrit |
| --- | --- | --- | --- | --- | --- |
| 3.14.4 | 21 | passed | 10 passed | 4 passed | focused HTTP/browser/restart passed |
| 3.13.10 | 21 | passed | 10 passed | 4 passed | pending |
| 3.12.11 | 21 | passed | 10 passed | 4 passed | pending |

3.14 is the priority/reference series. Use one series-specific JAR, not the 3.14 JAR on
an earlier server. Exact release/source/artifact checksums come from the inspected reference
matrix (`tools/gerrit-releases.json`), not guessed versions. Passing these exact releases does
not prove every patch of a series or production support. No Core patch is required.

The public frontend contracts (header-top-right, screen, REST get/fetch/getLoggedIn) were checked
against pinned Gerrit sources; TypeScript and DOM tests pass. header-top-right exists in the
source of all three pinned releases but is not in the documented endpoint contract; it is the
one source-only endpoint used, for the header bell.

## Remaining release gates

- Complete tool locking: the actual generated MODULE.bazel.lock passed error-mode verification
  across the canonical matrix; mise.lock generation is blocked by mise's untrusted-project
  configuration policy. No trust bypass or global installation was performed.
- Load the 3.12/3.13 packaged JARs into their exact servers and exercise real browser journeys
  only after a complete resource envelope is admitted. Their cached images alone are not runtime
  evidence. The focused 3.14 canonical-JAR journey includes external HTTP auth/XSRF, account
  isolation, permission revocation, real read/archive controls and exact state across a full
  server restart; the byte-identical frontend also has real polling/footer/read-all proof.
- Full theme/mobile/accessibility, failure/concurrency and production-sized Inbox measurements;
  broader packaged migration/reload qualification and tool-cache integrity remain unclaimed.
- Full distribution-script metadata/tooling qualification; direct canonical target builds are
  not a claim that package.sh or the hosted release workflow ran.
- Hosted CI is approval-gated (`ci` environment); Release Please runs only with authorization.

No hosted CI success, release, merge or production readiness is claimed. See test-evidence
for exact executed scope, retained RED failures and canonical/native artifact hashes.
