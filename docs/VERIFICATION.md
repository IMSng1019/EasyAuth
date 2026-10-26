# 1.20.4 release verification — 2026-10-26

Artifact: `easyauth-fabric-mc1.20.4-4.0.0-multisource.1.jar`.

Build command (build JVM Java 25, test/runtime JVM Java 17):

```powershell
.\gradlew.bat '-PeasyauthTarget=1.20.3-fabric' :1.20.3-fabric:build :1.20.3-fabric:runGameTest :1.20.3-fabric:collectJars --configure-on-demand --offline
```

Result: **BUILD SUCCESSFUL**. All 49 unit tests and all 3 required Fabric GameTests passed. The original standalone proxy protocol test additionally passed all 9 checks on Java 17.

Unit coverage: 15 identity persistence tests, 7 legacy reservation policy tests, 13 HTTP authentication tests, 9 configuration tests, and 5 connection-proof tests. Includes database reopen/concurrent creation/rollback, raw and native-v7 UUIDs, remap stability, conflicts, partial successful HTTP responses, deadlines, priority, saturated custom worker isolation and configuration validation.

Fabric coverage: existing mixin audit and TOTP tests, plus the new authentication integration test. The new test invokes Minecraft 1.20.4's actual hello/key handlers, rejects an invalid RSA challenge before contacting HTTP, checks AES cipher setup and a negative signed server digest, authenticates a first-time Union user whose UUID equals the native offline UUIDv3, creates a local v7 profile, transfers proof into an actual ServerPlayer and checks immediate authenticated state. It also checks Union remapping, rejection of proof reuse by another connection, disabled-source protection, and legacy raw/forced UUID preservation. Only packet transport and remote authentication services are test fixtures; this is not a real remote client login.

Artifact inspection:

```powershell
python tools/verify-1.20.4-artifact.py build/libs/easyauth-fabric-mc1.20.4-4.0.0-multisource.1.jar
```

Passed: release metadata, Java 17 class versions, remapped login mixin, identity/runtime classes, bundled SQLite driver, companion entrypoint, MIT license, and no duplicate ZIP entries. Shadow's input-duplicate warnings do not represent duplicate entries in the final remapped jar. `git diff --check` passed.

Limits: no production MUA/skin-site credentials or private site contract were supplied. Live provider authentication, remote MySQL/PostgreSQL/MongoDB deployments, a running Floodgate/LuckPerms installation and graphical companion/skin rendering were not exercised. The original backends compile, SQLite is exercised, and existing integration hooks are retained. No cross-source binding command is included; collisions deny rather than merge. See the setup guide for the unavoidable 1.20.4 new offline-v3 versus external-v3 routing choice.
