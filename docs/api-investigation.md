# Primary-source API investigation

Reference repository inspected before implementation: `QuentinBtd/gerrit-branch-review`,
including BUILD/MODULE, exact Gerrit release/checksum matrix, mise, hooks, scripts, workflows,
compatibility, release policy and native/acceptance test conventions. The reference and the
separate working branch-review checkout were not modified.

Primary pinned sources used:

- `https://gerrit.googlesource.com/gerrit/+/v3.14.4/Documentation/pg-plugin-endpoints.txt`
- `https://gerrit.googlesource.com/gerrit/+/v3.14.4/polygerrit-ui/app/api/plugin.ts`
- `https://gerrit.googlesource.com/gerrit/+/v3.14.4/polygerrit-ui/app/api/rest.ts`
- `https://gerrit.googlesource.com/gerrit/+/v3.14.4/polygerrit-ui/app/elements/core/gr-main-header/gr-main-header.ts`
- `https://gerrit.googlesource.com/gerrit/+/v3.14.4/polygerrit-ui/app/elements/shared/gr-js-api-interface/gr-plugin-rest-api.ts`
- `https://gerrit.googlesource.com/gerrit/+/v3.14.4/polygerrit-ui/app/utils/account-util.ts`
- `https://gerrit.googlesource.com/gerrit/+/v3.14.4/java/com/google/gerrit/server/util/AccountTemplateUtil.java`
- `https://gerrit.googlesource.com/gerrit/+/v3.14.4/java/com/google/gerrit/server/extensions/events/CommentAdded.java`
- `https://gerrit.googlesource.com/gerrit/+/v3.14.4/java/com/google/gerrit/json/OutputFormat.java`

The same pinned `pg-plugin-endpoints.txt` and `api/plugin.ts` were fetched for 3.12.11 and
3.13.10: both expose footer-left and screen(). Actual `javap` contracts and compilation
against each checksum-verified plugin API establish Java surface compatibility; native
acceptance tests establish tested behavior, not a blanket cross-series binary guarantee.

## Header result

The 3.14 source contains `header-top-right`, but the pinned public endpoint documentation
does not list it. A source-level implementation detail is not a documented stable plugin
contract. Public `header-title` decorates content within the home anchor, so an interactive
bell there would nest links. Use documented footer-left for the badge, native TopMenu for
header navigation and public screen() for the full page. No unofficial endpoint is used.

## REST and frontend

The public RestPluginApi prefix defaults to empty and delegates requests to Gerrit's normal
REST service, including its base URL/authentication/XSRF handling. The plugin only uses
getLoggedIn/get/post. plugin.url() provides the base URL for subpath-safe links. Gson's
standard output policy is LOWER_CASE_WITH_UNDERSCORES, accounted for in frontend types.
The local API declarations intentionally describe only used public methods, not internal APIs.
