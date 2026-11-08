package xyz.nikitacartes.easyauth.storage.database;

import xyz.nikitacartes.easyauth.storage.PlayerEntryV1;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Resolves offline/external local names while preserving the account backend's original records. */
public final class OfflineAccountDatabase implements DbApi {
    private final DbApi delegate;
    private final UnaryOperator<String> accountNames;

    public OfflineAccountDatabase(DbApi delegate, UnaryOperator<String> accountNames) {
        this.delegate = Objects.requireNonNull(delegate);
        this.accountNames = Objects.requireNonNull(accountNames);
    }

    @Override public void connect() throws DBApiException { delegate.connect(); }
    @Override public void close() { delegate.close(); }
    @Override public boolean isClosed() { return delegate.isClosed(); }

    @Override public void registerUser(PlayerEntryV1 data) { delegate.registerUser(forStorage(data)); }
    @Override public PlayerEntryV1 getUserData(String username) { return delegate.getUserData(accountNames.apply(username)); }
    @Override public PlayerEntryV1 getUserDataOrCreate(String username) {
        return delegate.getUserDataOrCreate(accountNames.apply(username));
    }
    @Override public boolean deleteUserData(String username) { return delegate.deleteUserData(accountNames.apply(username)); }
    @Override public boolean updateUserData(PlayerEntryV1 data) { return delegate.updateUserData(forStorage(data)); }

    // Collision checks must still see every actual backend name and UUID, including names
    // retained for old offline accounts. Do not replace or merge keys in these snapshots.
    @Override public HashMap<String, PlayerEntryV1> getAllData() { return delegate.getAllData(); }
    @Override public HashMap<String, PlayerEntryV1> getAllDataStrict() { return delegate.getAllDataStrict(); }
    @Override public int countAccountsByIp(String ipAddress) { return delegate.countAccountsByIp(ipAddress); }
    @Override public List<String> getUsernamesByIp(String ipAddress) { return delegate.getUsernamesByIp(ipAddress); }
    @Override public List<String> getPremiumUsernames() { return delegate.getPremiumUsernames(); }
    @Override public String getUsernameByUuid(String uuid) { return delegate.getUsernameByUuid(uuid); }
    @Override public String backup() throws DBApiException { return delegate.backup(); }
    @Override public void migrateFromV4() { delegate.migrateFromV4(); }
    @Override public void migrateFromV9() { delegate.migrateFromV9(); }

    private PlayerEntryV1 forStorage(PlayerEntryV1 data) {
        String accountName = accountNames.apply(data.username);
        if (accountName.equals(data.username)) return data;
        // Never temporarily rename the shared DTO: update() may be queued concurrently.
        // The normal path already holds the original entry; only alias-named DTOs need copying.
        return new PlayerEntryV1(accountName, accountName.toLowerCase(Locale.ROOT),
                data.uuid == null ? null : data.uuid.toString(), data.toJson());
    }
}
