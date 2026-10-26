# 1.20.4 release verification — 2026-10-27

Artifact: `easyauth-fabric-mc1.20.4-4.0.0-multisource.3.jar`.

Build command (build JVM Java 25, test/runtime JVM Java 17):

```powershell
.\gradlew.bat '-PeasyauthTarget=1.20.3-fabric' :1.20.3-fabric:build :1.20.3-fabric:runGameTest :1.20.3-fabric:collectJars --configure-on-demand --offline
```

Result: **BUILD SUCCESSFUL**. All 75 unit tests and all 4 required Fabric GameTests passed on Java 17. The unchanged standalone proxy protocol test also passed all 9 checks during the initial release verification.

Unit coverage: 23 identity persistence tests, 3 local-name policy tests, 7 legacy reservation policy tests, 13 HTTP authentication tests, 22 configuration tests, and 7 connection-proof tests. Includes schema-v1 to v2 upgrade without renaming identities, independent official/Union names, persistent creation prefixes, namespace ownership, 16-character limits, database reopen/concurrent creation/rollback, raw and native-v7 UUIDs, remap stability, conflicts, partial successful HTTP responses, deadlines, priority, saturated custom worker isolation and exact per-source provenance.

Fabric coverage: existing mixin audit and TOTP tests, plus two authentication integration tests. The tests invoke Minecraft 1.20.4's actual hello/key handlers, reject an invalid RSA challenge before contacting HTTP, check AES cipher setup and a negative signed server digest, authenticate a first-time Union user whose UUID equals the native offline UUIDv3, create a local v7 profile, transfer proof into an actual ServerPlayer and check immediate authenticated state. They also check Union remapping, rejection of proof reuse by another connection, disabled-source protection, and legacy raw/forced UUID preservation. Only packet transport and remote authentication services are test fixtures; this is not a real remote client login.

The same-name regression first reproduced the user's exact existing-account collision. It now seeds an official `LXW1019...` account and playerdata marker, completes an actual Union handshake using the raw provider name, allocates a separate `[MUA]LXW1019...` local v7 account, and verifies both players can coexist. It checks that the official UUID/password/playerdata remain intact, bracket names pass only the exact allocated-profile format exemption, and creation-time prefixes are not displayed twice. A disabled-source legacy-path test and direct legacy cache registration test reject namespace preclaims before creating database placeholders. Existing offline UUID routing is preserved; a distinct source UUID can enter verification instead of being forced into a same-name password account.

Existing unprefixed-profile compatibility and display integration checks still exercise real Minecraft display methods and outgoing player-info packets: unchanged real name/UUID/database identity, retained styles, live reload/null restoration, and team join/leave/prefix changes/removal. Namespacing applies only at initial allocation; persisted names remain immutable. The strict configuration tests cover namespace overlap, disabled-source reservations and compatibility of official/offline/Floodgate display-only prefixes.

Artifact inspection:

```powershell
python tools/verify-1.20.4-artifact.py build/libs/easyauth-fabric-mc1.20.4-4.0.0-multisource.3.jar
```

Passed: release metadata, Java 17 class versions, remapped login mixin, display/scoreboard mixin registration, identity/runtime classes, bundled SQLite driver, companion entrypoint, MIT license, and no duplicate ZIP entries. Shadow's input-duplicate warnings do not represent duplicate entries in the final remapped jar. `git diff --check` passed.

Limits: no production MUA/skin-site credentials or private site contract were supplied. Live provider authentication, remote MySQL/PostgreSQL/MongoDB deployments, a running Floodgate/LuckPerms installation and graphical companion/skin rendering were not exercised. The original backends compile, SQLite is exercised, and existing integration hooks are retained. No cross-source binding command is included; collisions deny rather than merge. See the setup guide for the unavoidable 1.20.4 new offline-v3 versus external-v3 routing choice.
