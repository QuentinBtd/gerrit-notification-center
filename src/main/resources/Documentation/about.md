Persistent, per-account notifications for Gerrit, without modifying Gerrit Core.

## What users get

- A **Notifications** screen at `/x/@PLUGIN@/notifications` and a bell icon with an unread
  badge in the header (hidden by Gerrit on mobile widths; open the screen directly there).
- GitHub-style **Inbox**, **Saved** and **Done** views, read/unread, save, done/undo,
  mark-all-read, bulk selection and 25 rows per page.
- Notifications for: reviewer added, explicit mentions, relevant comments, new changes,
  new patchsets, merged and abandoned changes.
- Change owners and current reviewers follow their changes; mentions can target any
  registered user.
- Gerrit's native watched projects (`Settings → Notifications`) also deliver to the inbox,
  evaluated by Gerrit itself.
- **Subscriptions** (Notifications → Settings): follow every change matching a change search
  query, per event type, for example `project:^team/.* branch:main -is:wip`.
- **Delivery channels** (Notifications → Settings): email and/or browser notifications in
  addition to the inbox. Browser notifications use the Web Notification API and only work
  while a Gerrit tab is open.

Users only see notifications for changes they can still read; permissions are checked again
when listing, counting and updating notifications.

## Storage

Notifications are kept in an embedded H2 database under the Gerrit site. Do not share the
database file between Gerrit instances. Stop Gerrit before backing it up or moving it.

See [configuration](config.md) and the [REST API](rest-api-accounts.md).
