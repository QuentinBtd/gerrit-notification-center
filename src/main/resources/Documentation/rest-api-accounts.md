# REST API

All endpoints act on the calling account only (`self`). Use Gerrit's normal authenticated REST
client and XSRF protection. Cross-account access is `403`, even for administrators.

## List notifications

```text
GET /accounts/self/@PLUGIN@~notifications?state=unread&limit=50
```

- `state`: `all` (UI "Inbox", not done), `unread`, `saved` (UI "Saved"), `archived`
  (UI "Done").
- `type`: one of `REVIEWER_ADDED`, `MENTION`, `COMMENT`, `NEW_CHANGE`, `PATCHSET`, `MERGED`,
  `ABANDONED`.
- `limit`: 1–100.
- `before`: the `next` cursor of the previous page.

Returns `{"items": [...], "next": <cursor>}`; `next` is absent on the last page. Items use
snake_case fields (`created_at`, `read_at`, `archived_at`, `saved_at`, `account_id`), Unix
epoch milliseconds and numeric stable IDs.

## Count

```text
GET /accounts/self/@PLUGIN@~notifications?count=true
```

Returns `unread`, `latest` (newest visible unread ID, or `0`), `browser` and `pollSeconds`.

## Update notifications

```text
POST /accounts/self/@PLUGIN@~notifications
{"action": "read", "id": 123}
{"action": "archive", "ids": [123, 124]}
{"action": "read-all"}
```

`action` is `read`, `unread`, `archive`, `unarchive`, `save`, `unsave` or `read-all`. `ids`
(1–100) applies the action to each visible notification and skips hidden or unknown ones.
Returns `204`; `404` when nothing matched. Unknown and inaccessible IDs are
indistinguishable.

## Preferences

```text
GET /accounts/self/@PLUGIN@~preferences
PUT /accounts/self/@PLUGIN@~preferences
{"email": true, "browser": false,
 "subscriptions": [{"query": "project:^team/.*", "types": ["NEW_CHANGE", "MERGED"]}]}
```

Subscription `types` are `NEW_CHANGE`, `PATCHSET`, `COMMENT`, `MERGED`, `ABANDONED`. Queries
are validated with Gerrit's change query parser (`400` on error); at most 50 subscriptions of
2048 characters each. Index-only operators that cannot be evaluated on a single change never
match.

## Errors

Invalid action, filter or pagination is `400`. When the plugin is disabled, every endpoint
returns `409`.
