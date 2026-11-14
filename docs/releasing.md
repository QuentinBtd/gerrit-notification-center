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

Default test policy is local-first; `/test` on a PR is an explicit hosted request, not automatic
push/PR CI. Workflows first need to exist on the default branch. Dynamic status names include
run ID/attempt and are not a fixed branch-protection check. A Release Please bot PR may need a
maintainer action/comment because built-in token events do not automatically trigger other
workflows. No protections, repository settings or credentials are changed by implementation.
