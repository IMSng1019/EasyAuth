# Offline real identity implementation plan

**Goal:** Use MUA-style persisted, prefixed GameProfile names for offline players while retaining their original effective UUID and password records.

**Architecture:** Upgrade an existing raw OFFLINE profile in the identity ledger once, preserving its profile ID/UUID and provider_name as the original account key. Route exact local aliases to that original password record with a DbApi decorator; share cache entries. Bind allocation evidence to the connection independently of external authentication, so /login, registration and 2FA remain required. Store the creation prefix and suppress cosmetic duplication. Current and persisted namespaces reject direct launcher claims, collisions and identity-edit bypasses.

**Constraints:** Same printable ASCII/no-space/16-character final-name constraints as MUA. Existing raw password keys stay reserved, so an existing offline account still cannot be merged with a same-name official account. UUID-based world data stays in place; name-based scoreboard and third-party integrations may require administrative updates. Prefix changes affect first allocations only, and never silently rename a previously mapped identity.

1. Add failing configuration, schema-upgrade/ledger, backend-alias and runtime credential/admission tests.
2. Implement schema 3 offline mappings, namespace checks and stable first allocation. Add exact-name DbApi/cache/admin/IP adapters without mutating stored credential objects.
3. Cover legacy and configured-source password routes, update both login method argument and authenticatedProfile field, require a connection-bound allocation proof and keep authentication flags separate.
4. Adapt admission format/case checks, command parsing, registration guards and UUID-migration protection. Preserve MUA's existing path.
5. Update examples/docs/version, run focused unit/GameTests, inspect the final jar and obtain independent review. No production database or world is modified by the implementation work.
