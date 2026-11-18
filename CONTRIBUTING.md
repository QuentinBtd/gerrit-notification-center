# Contributing

Use Java 21 and the pinned tools in `.mise.toml`. Install mise, then `mise trust`,
`mise install`, `mise run setup` and `mise run hooks:install`.
Run `mise run verify` before proposing a change. For a resource-bounded host, see
[development](docs/development.md); native qualification is explicitly not the Bazel gate.

Write one failing behavior test, execute RED, implement minimally, execute GREEN.
Retain evidence outside the source tree. Never use real account data or credentials in tests.
Use Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `ci:`); PR titles should be
Conventional Commits too. English code, docs, commit subjects and PR descriptions.

CI runs on every PR and push to `main` (formatting/lint, Conventional Commits, unit tests,
all-series build and acceptance), but every check first waits for a reviewer of the `ci`
GitHub environment to approve the run (**Review deployments → Approve**).
Publishing, merging and releasing require separate authorization.
