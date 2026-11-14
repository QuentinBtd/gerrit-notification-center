# Security policy

This is an unreleased MVP candidate, not a production security certification.
Report vulnerabilities privately to the repository owner rather than posting account or
Change data in a public issue. Include the exact Gerrit patch release and plugin revision.

The plugin requires authenticated own-account REST access and checks current Change READ
permissions on listing, counts and mutations. Gerrit's native REST handling enforces its
normal XSRF rules. No extra servlet, raw fetch bypass, SQL expression from user input,
NoteDb notification storage or cross-account administrator bypass is provided.

See [security and limitations](docs/security.md). No multi-primary or shared H2 deployment.
