# Authentication source name prefixes

**Goal:** Add configurable prefixes identifying each player's login channel in Minecraft 1.20.4 chat and the TAB player list, without changing the stored account name, UUID or playerdata.

**Design:** Treat prefixes as presentation. Each source has an optional `namePrefix`; the root configuration has `offlineNamePrefix` and `floodgateNamePrefix`. All default to empty. Prefix settings never change the choice between legacy and managed authentication. A verified connection passes its exact source id/type to the player; offline and Floodgate use their established login classification. Reload changes presentation for connected players; respawn retains channel metadata.

Literal prefixes support Unicode and spaces, limited to 64 code points with control characters and invalid surrogate sequences rejected. Rendering prepends a component to the existing display component, preserving name styling and team formatting. No GameProfile/DB names, UUID mappings, scoreboard teams, or skin properties are rewritten. Head nametags and command target names remain unchanged; extending them requires a separate identity/team design.

## Implementation

1. `SourcesConfig` + config tests: parse defaults and strict prefix validation, per-id lookup including disabled sources, default official lookup, and prove presentation does not alter authentication routing.
2. `ConnectionProofs`, `PlayerAuth`, player/login runtime: transfer immutable source metadata only from actual session proof, preserve it across respawn, decorate server-side chat display and TAB names, refresh TAB after successful source/global reload.
3. Extend proof/unit tests and the real Minecraft login GameTest to verify per-source text, hot reload, empty-prefix restoration, UUID/real-name/DB identity preservation and no duplicated prefix or lost team formatting.
4. Build Java 17-compatible Fabric 1.20.4 artifact, inspect remapping/dependencies, update Chinese deployment guide and example, then commit the completed change locally.
