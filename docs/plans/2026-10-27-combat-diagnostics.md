# Mapped-player combat diagnostics implementation plan

**Goal:** Obtain the missing server-side evidence for the reported stationary MUA player combat failure without changing gameplay.

**Architecture:** A JVM property (`easyauth.debugCombat`, false by default) enables bounded, server-side observation at attack-packet handling and player damage return. The Minecraft 1.20.4 mixins call the original operations exactly as before. Output includes identity, position, tick/cooldown/authentication state and damage outcome, never credentials or addresses. Stop after 1000 diagnostic records per server process.

**Tech stack:** Existing Fabric/MixinExtras, Java 17 runtime, Stonecutter 1.20.3-fabric node (Minecraft 1.20.4), SLF4J/Log4j and Fabric GameTests.

## Evidence and scope

- Client latest.log at 19:24:27 confirms the unquoted bracket-name command failure; `.4` already fixes the server's vanilla entity-target parser.
- At 19:24:57 the official client explicitly rejects the MUA texture domain. Server-side UUID mapping cannot change client trust.
- Both client instances share the game directory and their logs overlap. Bobby reports corrupt region cache data. This does not establish a combat cause.
- No supplied log reports individual attack-packet delivery or server damage decisions. Existing stationary-damage tests pass with vanilla tick entry points.

## Implementation and verification

1. Add an independent GameTest that observes EasyAuth diagnostic records while sending an actual attack packet to a registered player entity, and exercises accepted and rejected damage. Require no diagnostic output with the property absent. Run before implementing the observation hooks and confirm the missing-record failure.
2. Add a helper and version-gated mixins observing main-thread ATTACK packet handling and `ServerPlayer.hurt` before/after. Preserve selectors, authentication, movement, damage and cooldowns; include a process-wide output cap.
3. Run the GameTest and existing regression suite. Independently review disabled behavior, bounded output, main-thread access and exactly-once original calls.
4. Build an explicitly named diagnostic release, inspect remapping/Java 17/packaging, and document the exact startup property and interpretation of logs. Keep the `.4` normal artifact available.

No live server/client configuration or account/world files are modified. Reproduction with both clients is still required to identify the reported combat failure.
