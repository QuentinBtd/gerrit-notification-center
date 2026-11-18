# Gerrit Notification Center

Persistent, per-account notifications for Gerrit, without modifying Gerrit Core.
Java 21 backend, embedded H2, TypeScript PolyGerrit UI. Apache-2.0; bundled H2 is
MPL-2.0/EPL-1.0 (license included).

**Unreleased MVP candidate.** Canonical Bazel JAR builds, **10 unit + 4 in-process
acceptance cases per version**, pass against Gerrit **3.14.4**, **3.13.10**, **3.12.11**.
The packaged **3.14.4** plugin also passes focused real-server HTTP, account/XSRF/permission,
PolyGerrit read/archive/navigation and full-server persistence checks. Earlier-series
packaged-server/browser and remaining release gates are still open; this is not production
support or a published release. See [compatibility](docs/compatibility.md).

## Features

- GitHub-style Inbox / Saved / Done, unread count, read/unread, save, done/undo and
  mark-all-read. Saved notifications are kept by retention.
- Reviewer added, explicit mentions, relevant comments, new patchsets, merged and abandoned.
- Owner/current reviewers are followed accounts; mentions can target other registered users.
- Keyset pagination, state/type filters, recipient-scoped deduplication and retention.
- Authenticated own-account REST API. Live Change READ permission checks also protect counts
  and mutations after access is revoked.
- Full Notifications screen under `/x/notification-center/notifications`, header bell
  icon with unread badge (between search and settings). Configurable HTTP polling (45 seconds by default).

The bell uses the `header-top-right` endpoint. It exists in the Gerrit source of every supported
series (3.12–3.14) but is not in the documented endpoint list, so re-check it on each Gerrit upgrade.
Gerrit hides it on mobile widths; use `/x/notification-center/notifications` directly there.
No shadow-root traversal, MutationObserver or Core DOM manipulation. The quick popup is deferred.
Real 3.14.4 browser captures are retained with the qualification receipts; no screenshot
assets have been published in this repository.

## Install

Build the artifact matching the server's exact tested series. No release is published yet:

```sh
mise trust && mise install
mise run setup
mise run build -- 3.14
cp dist/notification-center-0.0.0-gerrit-3.14.jar "$GERRIT_SITE/plugins/notification-center.jar"
```

Use the canonical `notification-center` filename/name; the JAR manifest fixes plugin identity.
The JAR bundles frontend and H2, never Gerrit API or test classes. A reload restarts the plugin
and closes its database; stop Gerrit before backup or moving data. Use a staging site first
until the remaining qualification gates are complete. Do not share an H2 file between instances.

## Configuration

Add to `$GERRIT_SITE/etc/gerrit.config` and restart/reload the plugin:

```ini
[plugin "notification-center"]
    enabled = true
    storagePath = data/notification-center/inbox
    retentionDays = 90
    cleanupSeconds = 3600
    pollSeconds = 45
    eventREVIEWER_ADDED = true
    eventMENTION = true
    eventCOMMENT = true
    eventPATCHSET = true
    eventMERGED = true
    eventABANDONED = true
```

Storage paths resolve against the Gerrit site; absolute local paths are allowed. The path is
an H2 file basename (the database appends `.mv.db`), not a JDBC URL. Semicolons are rejected.
Intervals must satisfy retentionDays ≥ 1, cleanupSeconds ≥ 60, pollSeconds 30–3600.
Configuration is loaded at plugin startup; no automatic configuration hot reload.
Disabling stops event delivery and returns conflict on REST access; existing data is retained.
Cleanup still runs for retained data. See [operations](docs/operations.md).

## REST

Use Gerrit's normal authenticated REST client and XSRF protection:

```text
GET  /accounts/self/notification-center~notifications?state=unread&limit=50
GET  /accounts/self/notification-center~notifications?count=true
POST /accounts/self/notification-center~notifications {"action":"read","id":123}
POST /accounts/self/notification-center~notifications {"action":"unread","id":123}
POST /accounts/self/notification-center~notifications {"action":"archive","id":123}
POST /accounts/self/notification-center~notifications {"action":"unarchive","id":123}
POST /accounts/self/notification-center~notifications {"action":"save","id":123}
POST /accounts/self/notification-center~notifications {"action":"unsave","id":123}
POST /accounts/self/notification-center~notifications {"action":"read-all"}
```

List returns `{items: [...], next?: <cursor>}`; pass `before=<next>` for the next page.
`state`: `all` (UI "Inbox": not done), `unread`, `saved` (UI "Saved"), `archived` (UI "Done"); `type` is one of the uppercase event
names above. `limit` is 1–100. Items use Gerrit's snake_case JSON fields (`created_at`,
`read_at`, `archived_at`, `saved_at`, `account_id`), Unix epoch milliseconds and numeric stable IDs.
Count returns `unread` and explicit `pollSeconds`. Successful POST returns 204.
Cross-account access is 403 even for administrators. Unknown or inaccessible notification IDs
are indistinguishable (404). Invalid action/filter/pagination is 400. Anonymous self access
is denied by Gerrit's account resource. No account ID is accepted in a mutation payload.

## Development and releases

[Development](docs/development.md) covers canonical Bazel and bounded native builds/tests.
[Architecture](docs/architecture.md) defines recipients, deduplication and event limitations.
[Security](docs/security.md), [operations](docs/operations.md),
[releases](docs/releasing.md), [test evidence](docs/test-evidence.md).

Conventions are adapted from `QuentinBtd/gerrit-branch-review`: Bazel 8.8.1, pinned mise,
prek, format/lint scripts, Renovate policy, Conventional Commits and Release Please.
Tests default to local; hosted matrix is explicit maintainer `/test` only. No PR/push test
matrix or workflow_dispatch test trigger. Release Please retains main-push maintenance.

## Roadmap

- Earlier-series packaged-server/browser qualification and remaining tool/release gates.
- PostgreSQL implementation of NotificationStore for multi-instance sites.
- Durable event queue/replay, large-Inbox permission-count batching and metrics.
- Quick panel, preferences, SSE later.
