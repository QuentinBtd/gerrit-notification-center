# Architecture and notification rules

## Proof of concept before architecture

The first vertical slice persisted and deduplicated H2 data across close/reopen (StoreTest).
The frontend POC exercised public registration and rendered actual custom elements in a DOM
harness. The integrated POC then loaded Module in Gerrit's real lightweight daemon and
exercised reviewer-added → own-account REST → read → access revocation. These are executed
proofs, not screenshots or a claim of packaged browser integration.

## Boundaries

`GerritListeners` adapts public extension events and reads missing owner/reviewers/published
inline comments from fresh ChangeNotes. `RecipientResolver` resolves explicit mentions.
`EventRouter` applies recipient rules and event switches. `NotificationService` owns visibility
on read/mutation; `NotificationStore` is engine-independent; `H2Store` owns JDBC and schema.
`GetNotifications` / `PostNotifications` extend native account REST; `Settings` and `Retention`
handle Gerrit configuration and lifecycle. TypeScript uses public plugin REST/screen/component APIs.

## Recipients and precedence

| Event | Recipients |
| --- | --- |
| REVIEWER_ADDED | Newly added registered account IDs only, not all existing reviewers |
| COMMENT | Owner plus current REVIEWER accounts; explicit mentioned accounts get MENTION instead |
| NEW_CHANGE | Owner plus current REVIEWER accounts (patch set 1) |
| PATCHSET | Owner plus current REVIEWER accounts (patch set 2+) |
| MERGED / ABANDONED | Owner plus current REVIEWER accounts |

Watchers are added to every row except REVIEWER_ADDED: accounts whose native Gerrit project
watch matches (NEW_CHANGE→NEW_CHANGES, PATCHSET→NEW_PATCHSETS, COMMENT→ALL_COMMENTS,
MERGED→SUBMITTED_CHANGES, ABANDONED→ABANDONED_CHANGES, evaluated by Gerrit's ProjectWatch
without project notify sections), and accounts with a plugin subscription for that type whose
change query, parsed as that account, matches the Change. A watcher never overrides a more
specific reason (a mentioned watcher still gets MENTION).

The actor is excluded in every case. CCs, former reviewers, email-only reviewers, stars and
attention-set members do not independently subscribe in this MVP.
One notification per recipient/event; a mention overrides a generic comment for that same
review action, rather than adding a second notification. Reviewer addition and an actual
comment are distinct actions and can independently notify. Gerrit native NotifyHandling
email preferences are not plugin inbox preferences. Type switches apply after precedence:
disabling MENTION suppresses that recipient, without falling back to COMMENT.

Explicit mentions are Gerrit `@email` tokens resolved only when exactly one registered account
owns the email, plus Gerrit's `<GERRIT_ACCOUNT_N>` account templates. Names/ambiguous emails
are never guessed. No comment body or email is stored in an inbox row.

## Event contracts and limitations

The real public Java hooks are CommentAddedListener, ReviewerAddedListener,
RevisionCreatedListener (not a Java class named PatchsetCreatedListener),
ChangeMergedListener and ChangeAbandonedListener. CommentAdded exposes review summary text,
not inline comments. Each event supplies actor/when/ChangeInfo; fresh NoteDb fills missing
recipient data. Invalid/incomplete events are ignored; errors log event kind only and do not
break Gerrit's originating action. There is no event replay queue: notification delivery is
best effort, not guaranteed or atomic with the Gerrit Change transaction.

Gerrit persists inline-comment timestamps at second precision whereas extension events keep
milliseconds. The adapter matches author + second and records a durable comment-UUID → first
event association (schema 2), so later same-second reviews do not reuse already-associated
mentions. Repeated callbacks of that same event reuse its association. Normal inline mention
publication is proven by the native acceptance test. Out-of-order/concurrent first delivery
within a second can still group an as-yet-unassociated inline comment with the first observed
review; the event contract has no exact inline-ID list. This is a documented best-effort
correlation boundary, not an exactly-once or perfect causality claim. General review summary
comments may include Gerrit-generated text; vote-only filtering only suppresses empty summaries.

Deep links target a Change; an inline-mention action targets the lexicographically smallest
UUID among claimed inline comments whose own text resolves at least one explicit mention.
Unrelated inline comments never replace that link, including when only the summary mentions
someone. With multiple inline comments in one review, the aggregate action uses one matching
comment link, not a separate notification/link per mention. Thread-specific fanout is deferred.

## Identity, persistence and retention

IDs are H2 BIGINT identity values, stable across restart. A SHA-256 fingerprint of event kind,
project, Change, actor, full event timestamp and stable payload discriminant is unique per
recipient. Duplicate inserts cannot reset read/archive state. Deduplication lasts only as long
as retention; an explicitly replayed ancient event can be inserted after its tombstone expires.

Schema 4 adds per-account preferences (email/browser) and ordered subscriptions; schema 5 adds
`replace_gerrit_emails`. NativeEmailFilter, an OutgoingEmailValidationListener, removes those
accounts' preferred addresses in place from Gerrit's covered message classes (newchange,
newpatchset, merged, abandon; not comment, also used for ready-for-review and vote-only reviews).
When every HTML-pass recipient opted in, it leaves the email unchanged with HTML email on (a
rejection or empty list ends the whole send, plain-text pass included) and rejects it only
with `sendemail.html = false`, where no plain-text pass exists. It skips
Gerrit's plain-text-only pass (Message-ID `-PLAIN@`), whose recipient list validators cannot edit,
and fails open on storage errors. Every event
scans all subscriptions; index them by project if that grows. A newly inserted row (never a
duplicate) for an opted-in recipient is queued (bounded, 1000; overflow drops with a warning)
and emailed via Gerrit's OutgoingEmail under an internal request context, after re-checking the
email preference and a fresh READ check. The Message-ID is the event digest plus recipient. A failing channel never interrupts inbox inserts for the remaining recipients.
Browser notifications are client-side: the bell compares the newest unread ID with the last one
shown in this browser (localStorage; a missing key sets a starting point, possibly 0) and fetches
at most 10 new unread rows. The stored ID advances only after they were shown, so a failed fetch
is retried on the next poll.

Schema 1 creates inbox fields, unique recipient/event key and account/created/read/archive
indexes. Schema 2 adds durable inline-comment associations and a cleanup index. Idempotent DDL
and a version ledger allow recovery after H2's auto-committing DDL is interrupted; unsupported
future versions fail closed. Ordinary writes are atomic JDBC statements. Cleanup uses an
explicit transaction covering rows and associations. The synchronized store owns one connection;
close happens during lifecycle stop. There is no connection pool or external listener.

Keyset pagination uses the indexed account/identity range, not OFFSET. Visibility filtering
scans storage batches and emits only an authorized last-item cursor; hidden IDs do not become
page cursors. Unread counts intentionally check live permissions for every matching row;
this is linear in unread rows and may be expensive for large inboxes. A SQL count alone
would leak revoked Changes. Read-all visits visible non-archived unread rows only; it is
best effort across rows, not a single all-or-nothing inbox transaction.

H2 was chosen for a single-node, zero-service MVP. NotificationStore has no H2 types and can
be implemented using PostgreSQL later. Notifications are never stored in Git refs/NoteDb;
NoteDb is read only for source Change metadata. See operations for H2 backup and HA restrictions.
