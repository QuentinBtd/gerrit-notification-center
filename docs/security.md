# Security boundaries and remaining gates

REST views live on native AccountResource, not a custom servlet. Both views compare the
injected authenticated CurrentUser account to the resolved resource; no admin cross-account
bypass. SQL lookup/update includes account_id. Every listed row/count/mutation checks fresh
ChangeNotes plus PermissionBackend.absentUser(account).change(notes).test(READ), fail closed
on missing Change or permission/storage lookup errors. Revoked private Changes disappear from
counts and items. Permission state can change after a request check; no cross-system atomic
permission lock is claimed. Read-all repeats checks per row.

Gerrit's normal REST layer provides authentication, response anti-XSSI handling and XSRF
protection for modifying requests; frontend uses the public plugin REST client, not raw fetch.
In-process authenticated REST behavior is tested; a real external HTTP session/XSRF negative
probe and packaged-plugin browser journey are still independent qualification gates.

Prepared statements bind user parameters. State/action/type are allowlisted; pagination is
bounded. Account identity never comes from JSON. Text is inserted with textContent, not HTML;
Change URLs are generated server-side and checked for a local /c/ prefix. Subpath deployment
links are built from the public plugin base URL. Polls do not overlap and async responses after
component detachment are discarded. No extra credentials or secrets are introduced.

H2 notifications are not an audit trail or source of authorization. The listener is best effort,
not atomic with Gerrit commits; no replay queue, durable transactional outbox or guaranteed
notification delivery. Inline-event correlation limits are described in architecture. Backups,
plugin logs and build artifacts must be protected by the operator. Full multi-instance and
untrusted database-file access are out of scope.

CI request/report jobs run only default-branch inline scripts and never execute PR code with a
write token. Exact trusted `/test` requires fresh collaborator write/maintain/admin permission,
open PR, immutable valid head SHA and existing head repository. Full request reruns require a
fresh comment; failed test job reruns report a fresh run-attempt status context. PR checkout jobs
use contents:read, persist-credentials:false, no explicit secrets. Check the tested SHA still
matches current head before merge. Local policy fixtures and actionlint are not live Actions
execution proof. No hosted tests were requested as part of candidate implementation.
