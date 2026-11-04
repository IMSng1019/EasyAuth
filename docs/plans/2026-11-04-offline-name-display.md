# Offline name display implementation plan

**Goal:** Show configured source prefixes in chat, TAB, command suggestions and overhead player names on Fabric 1.20.4.

**Architecture:** Keep the account name, UUID, authentication records and real server scoreboard unchanged. Chat/TAB already use `SourceNameDisplay`; commands now search those prefixes and insert valid account names. For overhead names, project received scoreboard teams into client-only teams, grouping players by their original team and display prefix. Each unteamed player gets an independent group.

**Alternatives:** Renaming GameProfiles would change account lookups and is limited to 16 characters; it cannot support the existing 64-character Unicode display prefixes. Custom entity names do not replace vanilla player nameplates. A client mod could preserve every team relationship but would require installation on each client. Client-only teams provide vanilla compatibility, with a documented limitation for mixed-prefix members of one real team: their client team relationships differ, affecting friendly invisibility and team-relative display/collision rules.

**Tech stack:** Existing Java 17, Fabric 1.20.4, MixinExtras, GameTests and Brigadier.

## 1. Reproduce overhead omission

- Add `src/gametest/java/xyz/nikitacartes/easyauth/sources/NameplateRuntimeTest.java` and register it in `src/gametest/resources/fabric.mod.json`.
- Send real player-info and scoreboard packets through the server listener; deserialize team packets into a separate client scoreboard.
- Assert formatted nameplate prefixes, unchanged account/server scoreboard, preserved rank style, joins, team changes, reload/clear and removal/re-add.
- Run `:1.20.3-fabric:runGameTest` and record the expected missing-nameplate failure.

## 2. Project client teams

- Add `integrations/NameplateDisplay.java`, `mixin/NameplatePacketMixin.java` and a packet constructor invoker.
- Track only profiles advertised to each connection and its original team packets. Filter projected members from original membership packets to prevent invalid client removals.
- Emit only necessary virtual-team changes. Preserve original prefix/suffix/color/options, avoid name collisions and group same-team/same-prefix players together.
- Refresh after join and respawn; existing display-name broadcasts refresh reloads. Remove connection state at disconnect.
- Run the GameTests, fix failures and obtain independent review.

## 3. Deliver

- Update the `.9` documentation to describe all display locations and protocol limits.
- Run the focused build, unit tests, GameTests, artifact inspection and `git diff --check`.
- Deliver the new jar; do not change production configs, accounts or world data.
