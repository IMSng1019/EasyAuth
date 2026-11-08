### 4.0.0-multisource.13-prefix-refresh

- Refresh allocated UNION, skin-site and offline name prefixes on the next connection, including clearing and reapplying prefixes. Keep the local UUID, base name and original account data.
- Retain historical names as reserved aliases and reject conflicting or overlong replacements without changing the previous mapping.
- Upgrade the identity ledger to schema 4 with stable backend account keys. Back up the ledger before upgrading; older jars cannot read this schema.
- Keep offline identity protection after clearing a prefix and locate existing online sessions by UUID for account administration.

### Release notes:
#### Add
- IP limit controls [#200](https://github.com/NikitaCartes/EasyAuth/issues/200), thanks to @virgil698 [#252](https://github.com/NikitaCartes/EasyAuth/pull/252)
- New config options for IP limit in `extended.conf`:
    - `ipLimit.enabled`
    - `ipLimit.maxAccountsPerIp`
    - `ipLimit.blockExcessRegistration`
    - `ipLimit.notifyAdmins`
    - `ipLimit.exemptIps`
    - `ipLimit.cacheExpirySeconds`
    - `ipLimit.maxConcurrentSessionsPerIp`
    - `ipLimit.exemptOnlinePlayers`
- UUID management commands, thanks to @virgil698 [#249](https://github.com/NikitaCartes/EasyAuth/issues/249)
    - `/auth setUuid`
    - `/auth clearUuid`
    - `/auth getUuid`
- PostgreSQL support, thanks to @DanielTrejoBorjas [#226](https://github.com/NikitaCartes/EasyAuth/pull/226), [#258](https://github.com/NikitaCartes/EasyAuth/pull/258)
- New options in `extended.conf` for preventing OPs and registered players from joining without logging with `skipAllAuthChecks` enabled, thanks to @Fenix5fire [#184](https://github.com/NikitaCartes/EasyAuth/pull/184)
    - `skipAllAuthChecksNotForOperators`
    - `skipAllAuthChecksNotForRegisteredPlayers`
- Option `allowCustomPacketsForNonOp` to allow handling custom packets for non-OP players
- Option `allowedCustomPackets` to specify list of custom packets that always allowed

#### Fix
- `/auth update` to also refresh cached online player data [#244](https://github.com/NikitaCartes/EasyAuth/issues/244)
- Problem with loading configurations files [#225](https://github.com/NikitaCartes/EasyAuth/issues/225)
- Fail to freeze player before login [#261](https://github.com/NikitaCartes/EasyAuth/issues/261)

---

### Full Changelog:
https://github.com/NikitaCartes/EasyAuth/tree/HEAD/CHANGELOG.md
