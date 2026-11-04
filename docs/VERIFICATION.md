# Fabric 1.20.4 vanilla command behavior verification - 2026-11-04

Artifact: `easyauth-fabric-mc1.20.4-4.0.0-multisource.12.jar`.

A focused clean build (`:1.20.3-fabric:clean :1.20.3-fabric:build :1.20.3-fabric:collectJars`) passed with **134 unit tests and 19 required Fabric GameTests**. See `build/vanilla-commands-final.log`. Artifact inspection confirms the four command compatibility mixins, both suggestion helpers and `MultiAuth.allocatedCommandName` are absent, while the authentication command restriction and identity/skin/display integrations remain present. SHA-256: `9d63516d3786aeb63fce4130062f8f6c2664a285e183d0bfe71c2940a21d075e`.

The revised command regressions first failed against the installed patches: the client teleport tree had been changed, and allocated bracket names were accepted without quotes (`build/vanilla-commands-red.log`). After removal they verify actual quoted MUA/offline teleports, quoted selector names, rejection of bare bracket names and unchanged profile names/UUIDs. Real command-tree packets are serialized and decoded; the client retains vanilla destination/targets branches, performs player-name suggestions locally without `ASK_SERVER`, emits raw names without automatic quotes, and again offers the crosshair entity UUID. Custom providers, command aliases, selector/coordinate/rotation/facing syntax and the server tree are also checked. The offline identity regression now uses the required vanilla quoted target.

Bracketed names remain real account names and must be quoted manually, for example `/tp "[MUA]Alice" "[OFF]Bob"`. Automatic quote completion, display-prefix search/tooltips, server completion forcing and teleport context adjustments are intentionally removed. No graphical-client session was run; runtime evidence covers vanilla parser execution and packet-decoded client trees/local suggestion providers. No account, authentication or world-data migration is introduced by this removal. `git diff --check` passed.

# Fabric 1.20.4 teleport completion verification - 2026-11-04

Artifact: `easyauth-fabric-mc1.20.4-4.0.0-multisource.11.jar`.

The focused `:1.20.3-fabric:build :1.20.3-fabric:collectJars` build passed with **134 unit tests and 19 required Fabric GameTests**. See `build/tp-completion-final.log`. Artifact inspection passed, including the new suggestion helper and mixin; `javap` confirms the packaged request hook targets intermediary `method_12059`. SHA-256: `5e704709b55c8895c0e4c93445a99d0e0d78979a45676c0628d9f6b14a9f903e`.

The previous network fixture completed suggestion requests synchronously and missed vanilla's single pending-future behavior. The updated fixture follows the cached 1.20.4 `ClientSuggestionProvider` bytecode: a new request cancels the old future, and actual serialized server replies are delivered only after all sibling providers have been called. Before the fix, `tp ` generated two requests and the merged result never completed (`build/tp-first-completion-red.log`). A separate failure exposed vanilla's ambiguous single-destination leaf for an incomplete second argument (`build/tp-second-completion-red.log`).

The final fixture verifies one request per completion, correctly quoted ordinary/MUA/offline candidates at both argument positions, blank input, opening quotes and partial prefixes, leading slash and `/teleport` alias forms. It also checks single-target executability, multiple-target selectors, coordinates, rotations, facing forms, unchanged custom providers, an untouched third-party tree with the same argument shape, and the original server execution tree. The server adjustment runs only while answering suggestion packets and leaves execution parsing unchanged. The client copy accepts a slightly broader completion shape: an invalid single-argument multi-target command can look complete locally, but the original server command still rejects it. No graphical-client session was run; the evidence combines real command/suggestion packet codecs and a bytecode-matched delayed client model.

# Fabric 1.20.4 offline real identity verification - 2026-11-04

Artifact: `easyauth-fabric-mc1.20.4-4.0.0-multisource.10.jar`.

The focused `:1.20.3-fabric:build :1.20.3-fabric:collectJars` build passed with **134 unit tests and 19 required Fabric GameTests**, with no failed/skipped unit cases. Runtime tests use Java 17 and the Gradle daemon uses Java 25. See `build/offline-profile-final.log`. The final artifact inspection and `git diff --check` passed. SHA-256: `1dbb61e7e1e0be8e9e40f02032362f10b84befe00b6db09f502d0e288d151565`.

Thirteen additional unit cases cover MUA-style ASCII/length constraints, offline/external namespace conflicts, schema 3 upgrades, one-time raw-profile prefix allocation, stable mappings after config changes/restarts, retained original account-name reservations, UUID/source/case collisions and transaction rollback/retry. The account adapter runtime test checks complete credential DTO preservation, alias reads/writes, unmodified caller objects and original collision snapshots. The real-profile test checks the effective forced UUID, original password verification, shared raw/local cache, 2FA/passkeys preservation, command lookup, no duplicate display prefix and connection-bound allocation evidence that never grants authentication.

The real login-listener regression exercises `handleHello`, `tick`, the serialized login-success packet, offline-mode and cached-password legacy routes, configured direct password login, and an actual RSA handshake with a local HTTP 204 fallback. It covers a config change between Hello and verification, original-name accounts with UNKNOWN/premium flags, direct launcher claims of allocated names, proof consumption, cross-connection rejection and login disconnect cleanup. The admission regression preserves the existing duplicate-login/IP policy when an authenticated old-name session and a new prefixed session share a UUID. Original-account reservation checks reject an unrelated authenticated UUID; the legacy route cannot promote a mapped password account to premium based on a claimed UUID.

Failing evidence is retained in `build/offline-profile-config-red.log`, `build/offline-profile-ledger-red.log` (new API compile failure), `build/offline-profile-adapter-red.log`, `build/offline-profile-runtime-red.log`, `build/offline-profile-admission-red.log` and `build/offline-profile-legacy-red.log`. Runtime failures specifically reproduced the unchanged raw GameProfile, lost Hello-time prefix snapshot, duplicate-login name mismatch and legacy premium promotion before the fixes passed.

The new offline prefix is a real local name, so it does not rely on the `.9` virtual-team display path. The original password database key remains reserved; this is not a same-name official/offline merge or a release of that key. UUID-based world data stays in place, but scoreboard and third-party data keyed by name need administrative adjustment. Schema 3 requires a pre-upgrade ledger backup to roll back to an older jar. The shared adapter was exercised with SQLite/in-memory backends, not live remote databases; no production configuration, world, real Mojang account or graphical client was used for verification. Existing external-authentication and `.9` display regressions still pass.

# Fabric 1.20.4 source-name display verification - 2026-11-04

Artifact: `easyauth-fabric-mc1.20.4-4.0.0-multisource.9.jar`.

The focused `:1.20.3-fabric:build :1.20.3-fabric:collectJars` build passed with **121 unit tests and 15 required Fabric GameTests** on Java 17 (Gradle daemon Java 25). See `build/offline-display-final.log`. The artifact inspection script (including the three new remapped nameplate mixins) and `git diff --check` also passed. SHA-256: `01b9d87a61a1ff894c07f945b6ad66087b917908d22e889ce8a40e072448e904`.

The new offline-name GameTest first failed because command suggestions did not carry the offline display prefix (`build/offline-names-red.log`). It now exercises actual offline account loading, chat/TAB prefixes, ordinary/Chinese/bracket names, empty and partial input, opening single/double quotes, searching by the configured display prefix, and clearing that prefix. It sends real command-suggestion request packets, serializes and decodes their responses (including the display-name tooltip), applies the returned replacement, and executes `/tp` to verify the intended target. Names and UUIDs remain unchanged; disconnected players disappear from suggestions. Existing external-name command-tree, selector, UUID and custom-provider tests still pass.

The nameplate regression first reproduced the missing overhead prefix (`build/nameplate-red.log`). Two new tests send real player-info and team packets through the wrapped server listener, serialize/decode them, and apply vanilla scoreboard membership semantics to a separate client scoreboard. They verify exactly one prefix in the rendered name, original rank colors/prefixes/suffixes/options, same-team/same-prefix grouping, team changes and removal, both initial packet orders, prefix reload/clear, player removal/re-add, and unchanged server teams and profile identities. Additional cases cover third-party client-only team packets, generated-name collisions with both client-only and real server teams, and no projection for profiles never advertised to that viewer. Strict duplicate-add/unknown-team/wrong-team-removal assertions pass. Login/respawn/disconnect mixins apply successfully; the full socket login and actual respawn sequence were not exercised by the new nameplate fixtures.

Source prefixes now cover overhead nameplates, chat, TAB and command suggestion tooltips, without renaming accounts. The protocol requires splitting client teams when members of one real team have different display prefixes; friendly invisibility, team-relative nameplate/collision rules and team-based TAB sorting have the compatibility limits described in the setup guide. Real server team membership and combat rules remain authoritative. No production server configuration or graphical client was available for verification. `offlineNamePrefix` must be configured in `config/EasyAuth/sources.json`; an empty prefix continues to mean no display prefix.

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
