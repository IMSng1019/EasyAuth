package xyz.nikitacartes.easyauth.sources;

import xyz.nikitacartes.easyauth.storage.PlayerEntryV1;
import xyz.nikitacartes.easyauth.storage.database.DBReadException;
import xyz.nikitacartes.easyauth.storage.database.DbApi;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Reserves existing password accounts and world files without modifying either of them. */
public final class LegacyIdentityGuard implements IdentityStore.LegacyGuard {
    private final LegacyIdentityPolicy policy;

    public LegacyIdentityGuard(Supplier<DbApi> database, List<Path> worldDataDirectories) {
        policy = new LegacyIdentityPolicy(() -> {
            DbApi db = database.get();
            if (db == null || db.isClosed()) throw new DBReadException("Legacy database is unavailable", null);
            return db.getAllDataStrict().values().stream()
                    .map(entry -> new LegacyIdentityPolicy.Account(entry.username, entry.uuid, forcedUuid(entry),
                            entry.onlineAccount == PlayerEntryV1.OnlineAccount.TRUE, !entry.password.isEmpty()))
                    .toList();
        }, worldDataDirectories);
    }

    @Override
    public void check(AuthenticatedIdentity identity, IdentityStore.LocalProfile profile, boolean existingIdentity) {
        policy.check(identity, profile, existingIdentity);
    }

    private static UUID forcedUuid(PlayerEntryV1 entry) {
        if (entry.forcedUuid == null || entry.forcedUuid.isBlank()) return null;
        try {
            return UUID.fromString(entry.forcedUuid);
        } catch (IllegalArgumentException e) {
            throw new DBReadException("Invalid forced UUID in legacy identity data for " + entry.username, e);
        }
    }

}
