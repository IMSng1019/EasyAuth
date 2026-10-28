# Fabric 1.20.4 release verification - 2026-10-28

Artifact: `easyauth-fabric-mc1.20.4-4.0.0-multisource.8.jar`.

## Build and tests

```powershell
.\gradlew.bat '-PeasyauthTarget=1.20.3-fabric' :1.20.3-fabric:build :1.20.3-fabric:collectJars --configure-on-demand --offline
```

**BUILD SUCCESSFUL**. The final build ran **121 unit tests and 12 required Fabric GameTests**, all passing on Java 17 (build JVM Java 25). See `build/skin-bridge-final.log` and the XML results under `versions/1.20.3-fabric/build/test-results/test`.

Unit coverage includes the previous identity isolation, authentication, configuration and connection-proof suite, two new profile-endpoint configuration cases, and 44 skin cases: 22 MineSkin HTTP, 11 cache/engine, 4 bridge configuration, 4 texture descriptors and 3 profile lookups. These check queue state transitions, short result IDs, 429 delays and time units, no duplicate submission after acceptance, whole-response deadlines and size limits, redirects disabled, credential handling, request deduplication, caller-future isolation, atomic bounded caching, failure backoff, stale-request races, and verified provider-UUID lookup.

Skin failing-test evidence is retained in `build/skin-http-red.log`, `build/skin-core-red.log`, `build/skin-runtime-red.log`, `build/skin-review-red.log`, `build/skin-result-red.log` and `build/skin-capacity-red.log`. In particular, review regressions caught late old requests replacing a newer identity skin, validation during cache construction, a key-length mismatch, valid short result IDs being refused, and unbounded login waiters behind one deduplicated generation.

The `.8` patch creates a missing empty `mineskin.key` after successful configuration validation, including reloads. Existing credentials are preserved byte-for-byte, and environment keys are never written to the placeholder. Updated configuration tests first reproduced the missing file (`build/skin-key-file-red.log`) and passed in the final build. Placeholder creation failures are logged without invalidating the configuration, preserving environment-key use in read-only directories.

## Runtime evidence

The six existing runtime tests continue to check mixin application, TOTP, source handshake/admission and isolation, namespaced command execution/completion, and combat observation. Command completion includes real command-tree and suggestion-packet serialization, stock-parser acceptance of quoted names, retained custom providers and an unchanged server dispatcher.

Six new skin runtime tests check:

- Official texture properties pass through without entering MineSkin work; UNION and SKIN_SITE use the same conversion path and retain local UUID/name and other properties.
- Actual login-success and ADD_PLAYER serialization preserve the converted original value/signature and mapped identity.
- Actual authlib accepts a trusted signed fixture and refuses unsigned, tampered and blocked-domain textures. The trust key is generated for the test, not a forged Mojang key or production account fixture.
- Login timeout does not mutate an already-returned profile; background results become available on later login.
- Waiting-login capacity remains bounded even when generation requests are deduplicated; saturation does not block official textures and completion releases capacity.
- Real selected-source HTTP authentication and the identity ledger feed the bridge; login coordinator tasks return while skin work waits. Connection proofs cannot be reused by another connection.
- A real RSA login disconnected during conversion does not publish a late profile and clears that connection's proof.

No player respawn, movement or in-session entity replacement is used. Identity allocation and authentication finish before cosmetic decoration; skin failures do not downgrade authentication. A separate bounded preparation lane and generator queue isolate the added network work. The skin deadline limits additional waiting relative to the authentication start; it does not shorten an independently configured provider-authentication timeout.

## Artifact inspection

```powershell
python tools/verify-1.20.4-artifact.py build/libs/easyauth-fabric-mc1.20.4-4.0.0-multisource.8.jar
```

Checks passed for release metadata, Java 17 classes, remapped login/command/diagnostic mixins, skin bridge classes and authlib validation references, SQLite bundling, companion entrypoint, MIT license and duplicate ZIP entries. `git diff --check` passed.

## Deployment and limits

See [server skin setup](SERVER_SKINS.zh-CN.md) and [example configuration](skin-bridge.example.json). Configure `EASYAUTH_MINESKIN_API_KEY` or the server-local `config/EasyAuth/mineskin.key`; it is not embedded in code, examples, logs or artifacts. Missing keys allow existing cached conversions but cannot generate new skins. `/auth skins reload` reloads the bridge configuration and key file; appearance changes apply on login.

No real MineSkin API key was supplied, so no real generation request or double-client graphical acceptance was performed. Tests use local HTTP, controlled conversion results and a fixture signature trust root; they do not prove that a particular production image has already rendered. Standard public classic/slim skins are supported; arbitrary third-party capes or private images are not promised. First generation, failure or stale-cache refresh can require a later reconnect.

The user reports combat is now normal, consistent with the supplied `.5` diagnostic log. The original intermittent cause is not established. The earlier client log directly confirmed MUA texture-domain rejection; `.7` addresses it by supplying genuinely trusted converted properties when configured, rather than changing client trust. The optional CustomSkinLoader support package remains an alternative, not a requirement for server bridging.

Other retained boundaries: entity arguments request server completion and no longer include the client-only crosshair entity UUID candidate; typed UUIDs still work. Manually typed unquoted bracket names can still appear red. Third-party vanish visibility, the full production modpack, remote database backends and actual client graphics were not exercised. No production server configuration, accounts, UUIDs or world data were changed during implementation.
