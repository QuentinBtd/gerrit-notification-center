# Renovate

Self-hosted Renovate runs from `.github/workflows/renovate.yml` (weekly, or manually from
**Actions → Renovate → Run workflow** on `main`). Repository policy is `.renovaterc.jsonc`;
bot-only settings (repository allowlist, allowed post-upgrade command) are in
`.github/renovate/bot-config.json5`. The only allowed post-upgrade command is
`python3 -I tools/scripts/pin_gerrit.py`. Fork PRs never get the bot credentials.

## Setup (once, by a maintainer)

1. Create a fine-grained personal access token restricted to
   `QuentinBtd/gerrit-notification-center`, with an expiration. Repository permissions:
   Contents, Pull requests, Issues, Commit statuses and Workflows read/write; Dependabot
   alerts read-only.
2. Save it as the repository Actions secret `RENOVATE_TOKEN`
   (**Settings → Secrets and variables → Actions**). Never commit or print it.
3. Run the workflow on `main` with **dry-run** first, then without it.

The built-in `GITHUB_TOKEN` cannot replace this token: Renovate updates workflow files, and PRs
it opens must trigger CI. Without the secret, the workflow fails fast with an explicit error.

## Local runs (Docker required)

```sh
mise run renovate:validate                    # offline, strict
RENOVATE_TOKEN=… mise run renovate:dry-run    # lookup only, writes nothing
RENOVATE_TOKEN=… mise run renovate:run        # real run, same configuration as the workflow
```
