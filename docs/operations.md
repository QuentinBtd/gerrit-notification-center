# Storage, configuration and troubleshooting

One primary Gerrit process exclusively owns the configured local H2 file. No AUTO_SERVER,
TCP console, remote JDBC URL or PostgreSQL dependency. Multi-primary, shared filesystems,
read replicas sharing this DB, clustering and multi-site failover are unsupported.
Use site filesystem permissions to restrict the file to Gerrit's operating-system account;
this plugin does not encrypt database pages or create/manage filesystem credentials.

## Large-inbox qualification boundary

A page limit bounds returned rows, not work: mostly hidden rows can require a long scan, and
count polling repeats fresh Change READ checks over unread rows. Large-site performance is
not qualified. Before claiming that scope, measure request latency, permission-check counts
and storage batches for production-sized inboxes with mostly revoked access, repeated Changes
and overlapping count/list/read-all requests. Establish an explicit work/latency budget before
adding batching or caching, and test revocation against every optimization. Retention and
polling intervals are operational controls, not a demonstrated upper bound. Never remove
current READ guards or substitute an authorization-blind SQL count to improve these numbers.

## Backup and restore

1. Stop Gerrit (or unload/stop the plugin and verify its database connection has closed).
2. Copy the configured basename's `.mv.db` plus gerrit.config and the exact plugin artifact.
3. Keep backups under equivalent access controls; rows contain project, Change, actor/account
   IDs and timestamps, even though subjects/comments/emails are not persisted.
4. Restore while stopped, preserve ownership/permissions, then start with the matching plugin.
5. Never copy an actively written MVStore file or downgrade into a future schema.

Automatic idempotent migrations are recorded in schema_version (currently 1 and 2).
Back up before upgrading. Cleanup runs at fixed delay after startup (default one hour), deleting
notifications and inline correlation records older than retentionDays, in a transaction.
No VACUUM/SHRINK or destructive operator command runs automatically. Disk space may not shrink
immediately; schedule maintenance/compaction separately while stopped according to H2 guidance.

Plugin settings and valid ranges are in README. Type flags are independent; switching one off
affects future deliveries, not existing rows. Logs use java.util.logging (Gerrit's configured
logging bridge/handlers), with a fixed component/class name. Configure the server's normal
logging levels; there is no separate plugin log file or option that dumps event payloads.
Warnings omit exception messages, comment text, project names and emails. Correlate storage
health using server/OS diagnostics without enabling sensitive HTTP payload logging.

- Empty inbox: confirm signed-in account, reviewer/owner rules, event flags and Change visibility.
- Missing bell: check plugin frontend resource loading and footer; no right-header bell is promised.
- Load/save error: verify session, plugin enabled flag and server storage health. UI shows a generic
  error rather than backend payload. Bounded read polling resumes after a save failure; only an
  explicit user action can retry a mutation. Refresh remains available.
- H2 lock error: stop the second process; do not enable shared mode or delete lock/database files.
- Future schema / invalid config: fail startup, restore a compatible artifact/backup or correct config.
- Growing count latency: reduce retention or plan PostgreSQL plus permission-aware batching; do not
  replace live visibility checks with a raw SQL count.
