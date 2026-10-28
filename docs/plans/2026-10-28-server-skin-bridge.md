# Server-side skin bridge implementation plan

**Goal:** Mojang, MUA Union and configured skin-site identities display their skins to Minecraft 1.20.4 clients without a client skin mod, while retaining authenticated identity and player data.

**Architecture:** Only the selected verified provider identity is decorated. Official textures pass through. Missing textures may be fetched from a validated provider-profile endpoint using the provider UUID. External textures are parsed into a public image URL/model, converted through MineSkin v2, validated with Mojang authlib, and cached atomically. The bridge has its own bounded executor; login continuations wait within a small budget without occupying the authentication coordinator lane. All profile replacement completes before the login success packet; late work only updates the cache. No player respawn or in-session entity mutation.

**Tech stack:** Java 17, existing Gson/SQLite dependencies, Java HTTP, Fabric GameTests, Mojang authlib and MineSkin v2 queue API.

## Contracts

Package `xyz.nikitacartes.easyauth.skins`:

- `TextureProperty(String value, String signature)`: immutable `textures` value/signature; signature may be absent for an input texture, never for a generated accepted output.
- `SkinDescriptor(URI url, String variant)`: `classic` or `slim`, bounded public HTTP(S) image URL. `from(TextureProperty)` parses the Base64 JSON, `cacheKey()` hashes URL/model/protocol version.
- `SkinGenerator.generate(SkinDescriptor)` returns `TextureProperty`, throws on failure. Called only by the bridge executor.
- `SkinBridgeConfig(boolean enabled, int loginWaitMillis, int requestTimeoutMillis, int generationTimeoutMillis, int refreshAfterHours, int maxQueuedSkins, int failureRetrySeconds)`: strict `skin-bridge.json`, defaults enabled/8000/5000/60000/24/64/60. API key is read separately from `EASYAUTH_MINESKIN_API_KEY` or `config/EasyAuth/mineskin.key`; it is never logged or embedded in generated examples.
- `SkinBridgeEngine(config, cacheFile, generator, validator)`: `Selection select(String identityKey, SkinDescriptor input)` returns `Optional<TextureProperty> cached` and a `CompletableFuture<TextureProperty> refresh`; `lastKnown(identityKey)` and `close()`. Cached and generated entries must pass the validator. Deduplicate equal image/model requests, bound queue/cache, apply failure backoff, retain last good identity skin, and write persistent data atomically. Futures are shared only inside the engine; per-login timeouts must not complete/cancel a shared generation future.

## Tasks

1. Write and run pure configuration/descriptor, HTTP queue-state-machine, cache/dedup/failure tests before implementing their respective behavior. No real generation request or key is needed for fixtures.
2. Implement MineSkin queue submission (`visibility=unlisted`, no player name), polling, embedded/result-fetch responses, deadlines, bounded bodies, redirects disabled, and conservative 429 handling. Requests stay on the fixed API host.
3. Add source-profile lookup only when textures are missing. Preserve official endpoint rules; derive standard profile URLs only from standard session paths or accept a validated explicit `profileUrl` template. Reject UUID-mismatched cosmetic responses without changing authentication.
4. Add runtime installation/lifecycle and asynchronous login continuation. Cap combined authentication/skin waiting; official profiles never wait for MineSkin. Only replace `textures`, keeping UUID, name, other properties and the connection proof unchanged. Failed/disconnected logins cannot publish late results.
5. Verify real login-success and ADD_PLAYER packet serialization preserves mapped identity plus converted original signed properties. Cover official pass-through, both external source types, cached/late/failed conversion, queue saturation, and no mutation of already-returned profiles.
6. Build `.7`, inspect remapped Java 17 artifact, document server-local API-key setup, cache/reload behavior and first-login timeout. Keep client skin support optional. Production end-to-end conversion requires a configured API key; never describe fixture results as a live graphical skin test.

## Limits

The bridge handles standard/slim skin images. Arbitrary third-party capes and non-public image URLs cannot be promised to render on stock clients. Source skin changes are picked up on later logins (with bounded cache refresh), not by force-respawning online players. External service failure must affect appearance only, never account authentication or UUID allocation.
