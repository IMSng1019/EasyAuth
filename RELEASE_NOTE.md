### Release notes:
#### Add
- NeoForge support for Minecraft 1.21 - 26.3 (no extra dependencies required, everything is bundled)
- Minecraft 26.2 and 26.3 support on Fabric

#### Fix
- Various bugs with `hide-player-coords` [#289](https://github.com/NikitaCartes/EasyAuth/issues/289):
    - moved the player and the vehicle to the world spawn after a server stop or a crash before login
    - teleported the player to an old location and spawned a copy of the vehicle after `/logout`, `/account unregister` or `/auth remove`
    - teleported the player to the death place after a respawn before login (1.21.9+)
    - replaced the bed spawn point with the world spawn after a respawn before login (below 1.21)
---

### Full Changelog:
https://github.com/NikitaCartes/EasyAuth/tree/HEAD/CHANGELOG.md