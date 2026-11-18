# Versioning and release

Adapted from gerrit-branch-review: Semantic Versioning, Conventional Commits, Release Please
simple release type, version.txt plus .release-please-manifest.json, vX.Y.Z tags, generated
CHANGELOG and draft GitHub Releases. New project starts at unreleased 0.0.0; initial stable
version is 0.1.0. No reference-project release history was copied.

Main-push Release Please maintenance uses built-in GITHUB_TOKEN; no GitHub App or extra secret
is assumed. It maintains a release PR then creates a draft/tag. That same workflow calls
release.yml using the exact returned source SHA because token-created events do not start a
second workflow. Publishing requires contents:write only in the publisher job. Branch admission,
version/manifest/changelog/tag consistency, clean source, local gates and the acceptance matrix
run before building and publishing immutable per-series JARs, checksums, provenance and third-party
inventory. Native fallback artifacts are development evidence, never silently release assets.

```sh
mise run release:plan
mise run release:verify -- 0.1.0 HEAD
mise run release:build -- 0.1.0 HEAD
# Explicitly authorized publication only:
mise run release:publish -- 0.1.0
```

The publisher refuses to overwrite a same-named asset with different bytes. Stable releases must
already exist (Release Please); explicit X.Y.Z-rc.N uses the reference's separate prerelease
script path and does not move Release Please state. No release/push/merge was performed for this
candidate. Do not publish before independent QA closes the known qualification gates.

## Gate selection and cache boundary

Local `verify.sh` and `release.sh verify` run the complete unit/acceptance matrix once,
plus all-series build validation. Hosted release verification sets the historical
`RELEASE_SKIP_MATRIX=1` flag: it defers **only acceptance** to the required exact-ref
integration job, retaining all-series unit tests and build validation in verify. Asset
building depends on both jobs. Do not use that flag as a standalone complete release gate.
The subsequent asset build is required release packaging, not a redundant test run.

`bazel-cache: false` disables the shared Bazel disk cache only. The setup action still enables
mise's tool-installation cache; the release toolchain is not entirely cache-free. Pinned
versions alone do not independently authenticate restored tool binaries. Tool-cache integrity
and canonical tool/module locks remain release qualifications; no hosted cache restoration
or cache-poisoning exploit has been demonstrated here. Do not describe checksum-pinned
plugin dependency downloads as proof of the entire toolchain's integrity.

Default test policy is local-first; `/test` on a PR is an explicit hosted request, not automatic
push/PR CI. Workflows first need to exist on the default branch. Dynamic status names include
run ID/attempt and are not a fixed branch-protection check. A Release Please bot PR may need a
maintainer action/comment because built-in token events do not automatically trigger other
workflows. No protections, repository settings or credentials are changed by implementation.
