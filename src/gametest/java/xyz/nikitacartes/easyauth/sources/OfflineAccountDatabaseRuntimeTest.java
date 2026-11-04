package xyz.nikitacartes.easyauth.sources;

//? if >= 1.21.5 {
import net.fabricmc.fabric.api.gametest.v1.GameTest;
//?} else {
/*import net.minecraft.gametest.framework.GameTest;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
*///?}
import net.minecraft.gametest.framework.GameTestHelper;

//? if >= 1.20.3 && < 1.20.5 {
/*import xyz.nikitacartes.easyauth.storage.PlayerEntryV1;
import xyz.nikitacartes.easyauth.storage.database.DbApi;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.UnaryOperator;
*///?}

//? if >= 1.21.5 {
public class OfflineAccountDatabaseRuntimeTest {
    @GameTest
//?} else {
/*public class OfflineAccountDatabaseRuntimeTest implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE)
*///?}
    public void localNamesKeepTheOriginalAccountAndCredentials(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*MemoryDatabase original = new MemoryDatabase();
        UnaryOperator<String> names = name -> {
            if (name.equalsIgnoreCase("[OFF]Steve")) return "Steve";
            if (name.equalsIgnoreCase("[OFF]New")) return "New";
            return name;
        };
        DbApi database;
        try {
            database = (DbApi) Class.forName("xyz.nikitacartes.easyauth.storage.database.OfflineAccountDatabase")
                    .getConstructor(DbApi.class, UnaryOperator.class).newInstance(original, names);
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("Local offline names need a database adapter that preserves the original account", missing);
        }

        PlayerEntryV1 legacy = new PlayerEntryV1("Steve", UUID.randomUUID());
        legacy.password = "existing-password-hash";
        legacy.onlineAccount = PlayerEntryV1.OnlineAccount.FALSE;
        legacy.otpSecret = "JBSWY3DPEHPK3PXP";
        legacy.otpEnabled = true;
        legacy.passkeys.add("existing-passkey");
        legacy.forcedUuid = UUID.randomUUID().toString();
        legacy.lastIp = "127.0.0.1";
        legacy.registeredWithCode = "existing-registration-code";
        legacy.sessionTimeout = 120;
        legacy.showLoginDialog = false;
        legacy.registrationDate = ZonedDateTime.now().minusDays(3);
        String token = legacy.issueSessionToken(3600);
        original.registerUser(legacy);
        String originalJson = legacy.toJson();

        PlayerEntryV1 loaded = database.getUserData("[OFF]Steve");
        check(loaded != null && loaded.username.equals("Steve") && loaded.uuid.equals(legacy.uuid),
                "Local-name lookup did not return the original stored identity");
        check(loaded.toJson().equals(originalJson) && loaded.verifySessionToken(token),
                "Local-name lookup lost password, OTP, passkeys or session data");
        original.registerUser(new PlayerEntryV1("Unknown"));
        check(database.getUserData("[OFF]Unknown") == null, "Adapter guessed an unallocated prefix mapping");
        original.deleteUserData("Unknown");
        check(database.getUserData("Steve").toJson().equals(originalJson), "Original-name lookup changed");
        check(database.getUserDataOrCreate("[OFF]Steve").username.equals("Steve"),
                "Local-name get-or-create introduced a second account");

        loaded.password = "updated-password-hash";
        check(database.updateUserData(loaded), "Updating the loaded original account failed");
        check(original.getUserData("Steve").password.equals(loaded.password), "Password update missed the original database key");

        // Some callers construct a fresh DTO from a command target. Translate it without
        // mutating that shared object, because PlayerEntryV1.update() can run asynchronously.
        PlayerEntryV1 aliasDto = new PlayerEntryV1("[OFF]Steve", "[off]steve", loaded.uuid.toString(), loaded.toJson());
        aliasDto.password = "alias-password-hash";
        check(database.updateUserData(aliasDto), "Updating through the allocated local name failed");
        check(aliasDto.username.equals("[OFF]Steve") && aliasDto.usernameLowerCase.equals("[off]steve"),
                "Adapter mutated the caller's shared account DTO");
        PlayerEntryV1 stored = original.getUserData("Steve");
        check(stored.username.equals("Steve") && stored.uuid.equals(legacy.uuid)
                        && stored.toJson().equals(aliasDto.toJson()) && stored.verifySessionToken(token),
                "Alias update failed to preserve the complete original account");
        check(original.getUserData("[OFF]Steve") == null && original.getAllDataStrict().size() == 1,
                "Alias update created a duplicate password record");
        check(database.getAllDataStrict().values().iterator().next().username.equals("Steve")
                        && database.getAllData().values().iterator().next().username.equals("Steve"),
                "Collision snapshots concealed the original database name");

        PlayerEntryV1 created = database.getUserDataOrCreate("[OFF]New");
        check(created.username.equals("New"), "New account DTO used its local alias as the storage key");
        created.password = "new-password-hash";
        database.registerUser(new PlayerEntryV1("[OFF]New", "[off]new", null, created.toJson()));
        check(original.getUserData("New") != null && original.getUserData("[OFF]New") == null,
                "Registering through the allocated local name created the wrong database record");
        check(database.deleteUserData("[OFF]New") && original.getUserData("New") == null,
                "Alias deletion did not address the original account");
        check(database.getUsernameByUuid(legacy.uuid.toString()).equals("Steve")
                        && database.getUsernamesByIp("127.0.0.1").equals(List.of("Steve")),
                "Database identity queries lost the raw owner needed for collision checks");
        *///?}
        context.succeed();
    }

    //? if >= 1.20.3 && < 1.20.5 {
    /*private static final class MemoryDatabase implements DbApi {
        private final HashMap<String, PlayerEntryV1> entries = new HashMap<>();
        private boolean closed;
        @Override public void connect() { closed = false; }
        @Override public void close() { closed = true; }
        @Override public boolean isClosed() { return closed; }
        @Override public void registerUser(PlayerEntryV1 data) { entries.putIfAbsent(key(data.username), copy(data)); }
        @Override public PlayerEntryV1 getUserData(String name) {
            PlayerEntryV1 entry = entries.get(key(name));
            return entry == null ? null : copy(entry);
        }
        @Override public PlayerEntryV1 getUserDataOrCreate(String name) {
            PlayerEntryV1 entry = getUserData(name);
            return entry == null ? new PlayerEntryV1(name) : entry;
        }
        @Override public boolean deleteUserData(String name) { return entries.remove(key(name)) != null; }
        @Override public boolean updateUserData(PlayerEntryV1 data) {
            return entries.replace(key(data.username), copy(data)) != null;
        }
        @Override public HashMap<String, PlayerEntryV1> getAllData() { return getAllDataStrict(); }
        @Override public HashMap<String, PlayerEntryV1> getAllDataStrict() {
            HashMap<String, PlayerEntryV1> snapshot = new HashMap<>();
            entries.forEach((name, entry) -> snapshot.put(name, copy(entry)));
            return snapshot;
        }
        @Override public int countAccountsByIp(String ip) { return getUsernamesByIp(ip).size(); }
        @Override public List<String> getUsernamesByIp(String ip) {
            return entries.values().stream().filter(entry -> entry.lastIp.equals(ip)).map(entry -> entry.username).toList();
        }
        @Override public List<String> getPremiumUsernames() {
            return entries.values().stream().filter(entry -> entry.onlineAccount == PlayerEntryV1.OnlineAccount.TRUE)
                    .map(entry -> key(entry.username)).toList();
        }
        @Override public String getUsernameByUuid(String uuid) {
            return entries.values().stream().filter(entry -> entry.uuid != null && entry.uuid.toString().equals(uuid))
                    .map(entry -> entry.username).findFirst().orElse(null);
        }
        @Override public void migrateFromV4() {}
        @Override public void migrateFromV9() {}
        private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
        private static PlayerEntryV1 copy(PlayerEntryV1 data) {
            return new PlayerEntryV1(data.username, data.usernameLowerCase,
                    data.uuid == null ? null : data.uuid.toString(), data.toJson());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    *///?}
}
