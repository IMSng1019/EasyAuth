# Fix same-name official and external identities

**Problem:** `namePrefix` currently changes chat/TAB only. `IdentityStore.resolve` still reserves `providerName`, so a verified Union identity collides with the existing official account before any displayed prefix is applied.

**Required behavior:** A first-time Union login as `LXW1019` with `namePrefix="[MUA] "` reserves `[MUA]LXW1019` as its distinct local GameProfile/database name. Its hasJoined query and provider metadata remain `LXW1019`, its local UUID remains persistent UUIDv7, and the existing official `LXW1019` account and world data stay unchanged. Prefixes cannot grant authentication or evade UUID collision checks.

## Implementation

1. Add a pure local-name policy: strip surrounding prefix whitespace, require printable ASCII with no spaces, limit generated local names to 16 UTF-16 characters, reject rather than truncate. Official/legacy raw names remain untouched.
2. Add an IdentityStore overload taking the configured prefix and an additive schema-v2 `profile.name_prefix` column recording the prefix applied at initial allocation. Apply namespacing only before first creation; existing identity names/UUIDs remain immutable across remaps and prefix changes. Preserve unprefixed schema-v1 profiles.
3. Carry persisted applied-prefix metadata through the authenticated connection proof into the player, preventing a duplicate display prefix. Existing unprefixed profiles retain display-only decoration. New namespaced profiles also show their actual names in head nametags and command targets.
4. Exempt exact persisted custom name+UUID pairs from only the ordinary username format regex; retain the separate connection proof admission check. Reject local/password creation in reserved source namespaces before writing a legacy placeholder. Keep provider names raw for session verification, and prohibit cross-source namespace claims.
5. Reproduce the reported existing-official collision in unit and Fabric handshake/admission tests; verify separate database/cache identities, raw official UUID unchanged, stable Union v7, packet name length, correct single prefix, fallback/spoof refusal, schema upgrade, and unchanged existing profiles.
6. Rebuild the Java 17-compatible Fabric 1.20.4 artifact, update deployment docs and prefix semantics, inspect the jar, and commit locally.

**Compatibility:** OFFICIAL, offline and Floodgate prefix settings remain presentation-only. For UNION/SKIN_SITE, `namePrefix` becomes the namespace for newly allocated identities. Changing it does not rename previously stored profiles or migrate playerdata. Same-name/same-offline-v3 clients remain indistinguishable before authentication; this fix must not claim to remove that protocol ambiguity.
