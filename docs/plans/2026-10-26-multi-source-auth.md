# Multi-source authentication implementation plan

**Goal:** Build a Fabric Minecraft 1.20.4 EasyAuth jar supporting explicit opt-in official, MUA Union and multiple Yggdrasil skin-site authentication without changing existing player UUIDs or migrating world data.

**Architecture:** Retain vanilla challenge/encryption and its exact signed server digest. Default configuration runs the existing EasyAuth path unchanged. With custom sources enabled, resolve bounded provider requests in priority order, reserve an identity in a separate transactional SQLite ledger, and transfer verified authentication on the connection to the player. Existing password databases remain in their configured backend.

**Tech stack:** Existing Java/Stonecutter/Fabric build, Gson, Java HTTP client, bundled SQLite JDBC; focused unit/integration tests and existing Fabric GameTests.

## Design decisions

- Prefer an additive ledger over rewriting all four legacy schemas: `profile(id,name UNIQUE,name_key UNIQUE,uuid UNIQUE)` and `source_identity(source_id,subject_id,provider_uuid,provider_name,profile_id,UNIQUE(source_id,subject_id))`. Ledger is local single-server runtime data and must be backed up with the legacy database and world.
- OFFICIAL remains raw and highest trust. OFFLINE retains the existing effective UUID. Union is always mapped once to UUIDv7 using `internal_id`. Skin sites retain native v7, otherwise persist a generated v7 under a configured stable subject or provider UUID. Never join accounts by name/UUID. Reject conflicts, including legacy forced UUIDs and world data.
- Default sources.json only enables official authentication. Additional providers require explicit enabling. Validation must reject duplicate ids/priorities, malformed URLs and incomplete Union mapping configuration. Reload validates a new snapshot before replacement.
- Custom successful authentication is connection-scoped, never persisted as Mojang premium status. Preserve Floodgate and companion hooks. Disable implicit playerdata migration whenever multi-source mode is active or persisted external identities exist.
- A stock offline 1.20.4 client cannot necessarily answer an online challenge: keep known local offline routing, and use hello UUID only as a routing hint. External clients need a launcher capable of their provider's join. Server-only code cannot force stock clients to trust external texture signatures/domains.
- Union official documentation: https://docs.mualliance.cn/zh/dev/union/auth ; mapped by UUID endpoint https://skin.mualliance.ltd/api/union/profile/mapped/byuuid/{uuid}. Cross-check mapped UUID against the authenticated hasJoined result.
- Global chat-key bypass is inappropriate. An optional bypass applies only to connections successfully authenticated by custom providers; require enforce-secure-profile=false for such clients.

## Work packages

1. Protocol/config (`sources/SourcesConfig`, `SourceAuthenticator`): write HTTP fixture tests for query encoding, negative server digests, priorities, timeout/retry, malformed responses and stable subject extraction; implement validated immutable snapshots and bounded requests.
2. Identity ledger (`sources/IdentityStore`, UUIDv7, strict legacy reservation adapter): write persistence/concurrency/collision tests first; implement atomic creation and stable remap updates without automatic binding.
3. Login integration (login/player/chat mixins, AuthEventHandler, EasyAuth lifecycle, source runtime): preserve vanilla cryptography, route direct offline clients, validate/reserve before loading name-keyed legacy data, transfer proof on actual Connection, block unsafe fallback, and integrate reload.
4. Build/test tooling: establish the 1.20.3-fabric node (actual minecraft_version=1.20.4), run unit tests, existing GameTests/mixin audit, remap/build jar, and inspect packaged metadata/classes.
5. Review all trust boundaries and default-disabled compatibility. Document sources.json examples, backup/restore, launcher requirements and commands; deliver jar with exact validation evidence and remaining live-service limitations.

No live service credentials or skin-site URLs were supplied; production login verification must be distinguished from local protocol fixtures and server startup tests. No upstream push or release publication is part of this task.
