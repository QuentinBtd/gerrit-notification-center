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
- Reviewer added, explicit mentions, relevant comments, new changes, new patchsets, merged and abandoned.
- Owner/current reviewers are followed accounts; mentions can target other registered users.
- Gerrit's native watched projects (`Settings → Notifications`) also deliver to the inbox, evaluated
  by Gerrit itself (filters, All-Projects inheritance). They are not replaced or modified.
- **Subscriptions**: follow any change matching a change search query (`project:^team/.*
  branch:main -is:wip`), per event type, from the Notifications **Settings** tab.
- **Delivery channels** per user (Settings tab): email and/or browser notifications, in addition
  to the inbox. Email reuses Gerrit's `[sendemail]` SMTP; browser notifications use the Web
  Notification API while a Gerrit tab is open (no push service, nothing when every tab is closed).
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
    email = true
    eventREVIEWER_ADDED = true
    eventMENTION = true
    eventCOMMENT = true
    eventNEW_CHANGE = true
    eventPATCHSET = true
    eventMERGED = true
    eventABANDONED = true
```

`email = false` disables plugin emails site-wide whatever users choose. Emails need no plugin
SMTP section: they are sent through Gerrit's own outgoing-email pipeline, so `[sendemail]`
(SMTP, `from`, `allowrcpt`/`denyrcpt`), site OutgoingEmailValidationListeners and each user's
email strategy apply as for Gerrit's emails. They go only to the recipient's preferred email,
only while they can still read the Change. Users opt in; by default plugin emails are sent in
addition to Gerrit's own emails.

To avoid duplicates, a user can also tick **Replace Gerrit's own emails** (Settings tab, needs
Email): the plugin then removes that user from Gerrit's emails of the covered kinds — review
request/new change (`newchange`), `newpatchset`, `merged`, `abandon` — through an outgoing-email
validator, and the plugin's email takes their place. Comment emails are never replaced (Gerrit
also uses them for ready-for-review and vote-only reviews, which the plugin does not deliver), so
comments can still arrive twice. Other Gerrit emails (attention set, restore, revert,
reviewer/vote removal, account and security mail) are never touched; other recipients are
unaffected. A kind is only replaced while its plugin event types are enabled and `email = true`.

Limits:

- Changes where the user is only CC are not covered by the plugin, so they get no email for them.
- With HTML email (`sendemail.html`, the default), when every HTML recipient of a Gerrit email has
  opted in (typically a sole reviewer), Gerrit's email is kept: rejecting it would also cancel
  Gerrit's separate plain-text copy to other users. Those users get both emails. With
  `sendemail.html = false` this case is replaced as well.
- Users whose Gerrit email format is *Plain text* on a site with HTML email cannot enable it
  (Gerrit's plain-text pass cannot be filtered). Sending is asynchronous on one worker with a bounded
queue (1000); when SMTP is too slow to keep up, extra emails are dropped with a warning and
inbox rows are never affected.

Email therefore only needs Gerrit's usual mail setup, for example:

```ini
[sendemail]
    enable = true
    smtpServer = smtp.example.com
    smtpServerPort = 587
    smtpEncryption = tls
    smtpUser = gerrit
    from = Gerrit Code Review <gerrit@example.com>
[plugin "notification-center"]
    email = true
```

`smtpPass` belongs in `etc/secure.config`. With `sendemail.enable = false` no plugin email is sent
either, and users whose Gerrit email strategy is *Disabled* (`Settings → Preferences`) never get
plugin emails even if they opt in. Subjects look like `[Gerrit] New change: project #123`.

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
GET  /accounts/self/notification-center~preferences
PUT  /accounts/self/notification-center~preferences
     {"email":true,"browser":false,"replace_gerrit_emails":false,"subscriptions":[{"query":"project:^team/.*","types":["NEW_CHANGE","MERGED"]}]}
```

Subscription `types` are `NEW_CHANGE`, `PATCHSET`, `COMMENT`, `MERGED`, `ABANDONED`. Queries are
validated with Gerrit's change query parser (400 on error); at most 50 subscriptions of 2048
characters. Index-only operators that cannot be evaluated on a single Change never match.

List returns `{items: [...], next?: <cursor>}`; pass `before=<next>` for the next page.
`state`: `all` (UI "Inbox": not done), `unread`, `saved` (UI "Saved"), `archived` (UI "Done"); `type` is one of the uppercase event
names above. `limit` is 1–100. Items use Gerrit's snake_case JSON fields (`created_at`,
`read_at`, `archived_at`, `saved_at`, `account_id`), Unix epoch milliseconds and numeric stable IDs.
Count returns `unread`, `latest` (newest visible unread ID, or 0), `browser` and explicit `pollSeconds`. Successful POST returns 204.
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
CI runs on every PR and push to `main` (formatting/lint, Conventional Commits, unit tests,
all-series build and acceptance). Runs start automatically for maintainers; PRs from forks or
first-time contributors wait for **Approve workflows to run** (GitHub's default policy).
Integration also runs weekly and on demand; Renovate runs weekly (see [Renovate](docs/renovate.md)).

## Roadmap

- Earlier-series packaged-server/browser qualification and remaining tool/release gates.
- PostgreSQL implementation of NotificationStore for multi-instance sites.
- Durable event queue/replay, large-Inbox permission-count batching and metrics.
- Quick panel, Web Push (notifications with every tab closed), SSE later.
