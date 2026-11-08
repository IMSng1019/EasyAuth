# Login-time prefix refresh implementation plan

**Goal:** On the next connection, replace an allocated UNION, SKIN_SITE or offline account's old prefix with the configured prefix while retaining its identity and account data.

**Architecture:** Update the identity ledger transactionally, preserving the profile ID, UUID and original backend account key. Persist historical local names as reserved aliases so ongoing sessions and queued writes still reach the same record. Preserve unprefixed legacy external identities and upstream-name stability; clearing a previously allocated prefix removes it. Official/Floodgate prefixes remain display settings.

**Tech stack:** Java 17-compatible shared code, SQLite identity ledger, existing database adapter, JUnit and Fabric GameTests.

1. Update/add ledger regression tests for source-specific prefix replacement, clearing/reapplying prefixes, upstream rename independence, native UUIDs, restart persistence, name/namespace/legacy collisions, length limits and rollback. Run the focused source tests and confirm the old behavior fails.
2. Upgrade the ledger to schema 4 with immutable backend account keys and reserved historical names. Resolve current and historical aliases, preserve source/UUID checks and update only name fields inside the transaction.
3. Route database/cache and administrative lookups through the retained account keys. Check both the candidate local name and the original backend account ownership before committing. Check current configured namespaces for renamed profiles too.
4. Extend real offline/external login fixtures to verify GameProfile names, stable UUIDs, credentials, cache sharing, connection proofs and rejection of impersonation/collisions. Run source tests and the focused Fabric 1.20.4 build/GameTests.
5. Update setup and verification documentation. Report the build artifact and remaining limits: UUID-based world data is unchanged; third-party data keyed by player name is outside this automatic mapping.

Implementation is performed in the current clean workspace as requested. No production database or world is modified.
