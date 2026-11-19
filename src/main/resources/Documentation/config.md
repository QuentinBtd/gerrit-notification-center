# Configuration

Settings go in `$GERRIT_SITE/etc/gerrit.config`, in the `plugin.@PLUGIN@` section. They are
read when the plugin starts: restart or reload the plugin after a change.

```ini
[plugin "@PLUGIN@"]
    enabled = true
    storagePath = data/@PLUGIN@/inbox
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

`plugin.@PLUGIN@.enabled`
:   When `false`, no event is delivered and the REST API answers `409 Conflict`. Existing data
    is kept and cleanup still runs. Default: `true`.

`plugin.@PLUGIN@.storagePath`
:   H2 database file basename (H2 appends `.mv.db`), relative to the Gerrit site or absolute.
    Not a JDBC URL; semicolons are rejected. Default: `data/@PLUGIN@/inbox`.

`plugin.@PLUGIN@.retentionDays`
:   Days after which notifications are deleted. Saved notifications are kept. Minimum `1`.
    Default: `90`.

`plugin.@PLUGIN@.cleanupSeconds`
:   Interval between retention cleanups. Minimum `60`. Default: `3600`.

`plugin.@PLUGIN@.pollSeconds`
:   How often the web UI polls the unread count, `30` to `3600`. Default: `45`.

`plugin.@PLUGIN@.event<TYPE>`
:   Enables or disables one event type: `REVIEWER_ADDED`, `MENTION`, `COMMENT`, `NEW_CHANGE`,
    `PATCHSET`, `MERGED`, `ABANDONED`. Default: `true` for all.

## Email

The plugin sends notification emails through its own SMTP settings, so Gerrit's own emails
can be switched off entirely.

```ini
[sendemail]
    enable = false
[plugin "@PLUGIN@"]
    email = true
    smtpServer = smtp.example.com
    smtpServerPort = 587
    smtpEncryption = tls
    smtpUser = gerrit
    from = Gerrit Code Review <gerrit@example.com>
```

`plugin.@PLUGIN@.email`
:   Enables plugin emails. Without `smtpServer` no email is sent. Default: `true`.

`plugin.@PLUGIN@.smtpServer`
:   SMTP host. TLS certificates must be valid for this name.

`plugin.@PLUGIN@.smtpServerPort`
:   SMTP port. Default: `25`, `465` with `ssl`, `587` with `tls`.

`plugin.@PLUGIN@.smtpEncryption`
:   `none`, `ssl` or `tls` (STARTTLS). Default: `none`.

`plugin.@PLUGIN@.smtpUser`
:   SMTP user. Omit it for an unauthenticated relay. The password goes in
    `etc/secure.config` as `plugin.@PLUGIN@.smtpPass`.

`plugin.@PLUGIN@.from`
:   Sender address. Default: Gerrit's server identity.

Users receive emails for their inbox notifications by default and can turn them off in
Notifications → Settings. Users whose Gerrit email strategy is *Disabled* get none. Emails are
plain text, sent to the preferred address, only while the user can still read the change.
Site `OutgoingEmailValidationListener`s still apply (message class `@PLUGIN@`).

Sending is asynchronous with a bounded queue of 1000 emails: when SMTP is too slow, extra
emails are dropped with a warning; inbox rows are never affected.

With `sendemail.enable = false`, Gerrit's other emails stop too: email address verification,
account/key/token notices, attention-set, restore/revert and CC-only emails. Keep `sendemail`
on if you need those.
