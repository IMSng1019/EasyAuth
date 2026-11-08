package xyz.nikitacartes.easyauth.sources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Single-server identity ledger, independent of the legacy password database backend.
 * Every assignment is durable before it is returned. Names and UUIDs reserve identities;
 * they never prove that two authentication sources belong to the same person.
 */
public final class IdentityStore implements AutoCloseable {
    public static final String OFFLINE_SOURCE_ID = "__easyauth_offline";

    public record LocalProfile(String id, String name, UUID uuid, String namePrefix) {
        public LocalProfile(String id, String name, UUID uuid) { this(id, name, uuid, ""); }
    }

    /** The local game identity and its unchanged legacy password-database account key. */
    public record OfflineProfile(LocalProfile profile, String accountName) {}

    @FunctionalInterface
    public interface LegacyGuard {
        /** Runs inside the ledger transaction, including on an existing identity's return visit. */
        void check(AuthenticatedIdentity identity, LocalProfile profile, boolean existingIdentity) throws SQLException;
    }

    private final Connection connection;

    public IdentityStore(Path file) throws IOException, SQLException {
        Path absolute = file.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        // Fabric loads bundled JDBC jars in its own class loader; service discovery alone is
        // insufficient before the legacy SQLite backend has initialized the driver.
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Bundled SQLite identity driver is unavailable", e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
        boolean schemaTransaction = false;
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
            statement.execute("BEGIN IMMEDIATE");
            schemaTransaction = true;
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                if (version.next() && version.getInt(1) > 4) {
                    throw new SQLException("Identity ledger was created by a newer EasyAuth version");
                }
            }
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS profile (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT UNIQUE NOT NULL,
                        name_key TEXT UNIQUE NOT NULL,
                        uuid TEXT UNIQUE NOT NULL,
                        name_prefix TEXT NOT NULL DEFAULT ''
                    )
                    """);
            boolean hasNamePrefix = false;
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(profile)")) {
                while (columns.next()) {
                    if ("name_prefix".equals(columns.getString("name"))) hasNamePrefix = true;
                }
            }
            if (!hasNamePrefix) {
                // Version 1 profiles keep their exact original local name and UUID. This records
                // only how future profiles were first named; it never renames an existing row.
                statement.execute("ALTER TABLE profile ADD COLUMN name_prefix TEXT NOT NULL DEFAULT ''");
            }
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS source_identity (
                        source_id TEXT NOT NULL,
                        subject_id TEXT NOT NULL,
                        source_type TEXT NOT NULL,
                        provider_uuid TEXT NOT NULL,
                        provider_name TEXT NOT NULL,
                        profile_id TEXT NOT NULL REFERENCES profile(id),
                        PRIMARY KEY (source_id, subject_id)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS identity_profile ON source_identity(profile_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS identity_provider_uuid ON source_identity(provider_uuid)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS offline_account_name ON source_identity(lower(provider_name)) WHERE source_type = 'OFFLINE'");
            boolean hasAccountName = false;
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(profile)")) {
                while (columns.next()) {
                    if ("account_name".equals(columns.getString("name"))) hasAccountName = true;
                }
            }
            if (!hasAccountName) {
                statement.execute("ALTER TABLE profile ADD COLUMN account_name TEXT NOT NULL DEFAULT ''");
                statement.execute("""
                        UPDATE profile SET account_name = COALESCE(
                            (SELECT provider_name FROM source_identity WHERE profile_id = profile.id AND source_type = 'OFFLINE'), name)
                        """);
            }
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS profile_account_name ON profile(lower(account_name))");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS profile_name_history (
                        name_key TEXT PRIMARY KEY NOT NULL,
                        profile_id TEXT NOT NULL REFERENCES profile(id),
                        name_prefix TEXT NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS history_profile ON profile_name_history(profile_id)");
            // Older jars cannot resolve renamed external accounts to their retained backend keys.
            statement.execute("PRAGMA user_version = 4");
            statement.execute("COMMIT");
            schemaTransaction = false;
        } catch (SQLException | RuntimeException e) {
            if (schemaTransaction) {
                try (Statement rollback = connection.createStatement()) {
                    rollback.execute("ROLLBACK");
                } catch (SQLException failure) {
                    e.addSuppressed(failure);
                }
            }
            connection.close();
            throw e;
        }
    }

    public synchronized LocalProfile resolve(AuthenticatedIdentity identity, LegacyGuard guard) throws SQLException {
        return resolve(identity, "", guard);
    }

    /** Refresh an allocated prefix on return visits without changing the identity or backend key. */
    public synchronized LocalProfile resolve(AuthenticatedIdentity identity, String configuredPrefix, LegacyGuard guard) throws SQLException {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(guard, "guard");
        requireText(identity.sourceId(), "source id", 128);
        requireText(identity.subjectId(), "subject id", 1024);
        requireText(identity.providerName(), "provider name", 64);
        Objects.requireNonNull(identity.providerUuid(), "provider UUID");
        Objects.requireNonNull(identity.type(), "source type");
        if (identity.sourceId().startsWith("__easyauth_")) {
            throw new IllegalArgumentException("Reserved internal source id");
        }
        return transaction(() -> {
            assertSourceType(identity.sourceId(), identity.type().name());
            if (identity.type() != SourceType.OFFICIAL && hasOfficialUuid(identity.providerUuid())) {
                throw conflict("OFFICIAL_UUID_COLLISION", "Custom source returned a UUID reserved by official authentication");
            }

            LocalProfile existing = findIdentity(identity.sourceId(), identity.subjectId());
            if (existing != null) {
                if (identity.type() == SourceType.SKIN_SITE && identity.providerUuid().version() == 7
                        && !existing.uuid().equals(identity.providerUuid())) {
                    throw conflict("NATIVE_UUID_CHANGED", "Skin-site native UUID changed for an existing identity; refusing to move or reuse player data");
                }
                LocalProfile candidate = existing;
                if (identity.type() != SourceType.OFFICIAL
                        && (!existing.namePrefix().isEmpty() || hasNameHistory(existing.id()))) {
                    String prefix = ExternalProfileNames.normalizePrefix(configuredPrefix);
                    if (!prefix.equals(existing.namePrefix())) {
                        String baseName = existing.name().substring(existing.namePrefix().length());
                        String name = ExternalProfileNames.localName(prefix, baseName);
                        assertNamespaceAvailable(identity.sourceId(), prefix, name);
                        assertNameAvailable(name, existing.id());
                        candidate = new LocalProfile(existing.id(), name, existing.uuid(), prefix);
                    }
                }
                // Upstream renames/remaps never alter the stable local base name or UUID.
                guard.check(identity, candidate, true);
                if (!candidate.equals(existing)) renameProfile(existing, candidate);
                updateProvider(identity);
                return candidate;
            }

            UUID localUuid = switch (identity.type()) {
                case OFFICIAL -> identity.providerUuid();
                case SKIN_SITE -> identity.providerUuid().version() == 7 && identity.providerUuid().variant() == 2
                        ? identity.providerUuid() : UuidV7.generate();
                case UNION -> UuidV7.generate();
            };
            String namePrefix = identity.type() == SourceType.OFFICIAL ? "" : ExternalProfileNames.normalizePrefix(configuredPrefix);
            String localName = identity.type() == SourceType.OFFICIAL ? identity.providerName()
                    : ExternalProfileNames.localName(namePrefix, identity.providerName());
            if (identity.type() != SourceType.OFFICIAL) assertNamespaceAvailable(identity.sourceId(), namePrefix, localName);
            LocalProfile candidate = new LocalProfile(UUID.randomUUID().toString(), localName, localUuid, namePrefix);
            assertVacant(candidate.name(), candidate.uuid());
            guard.check(identity, candidate, false);
            insertProfile(candidate);
            insertIdentity(identity.sourceId(), identity.subjectId(), identity.type().name(),
                    identity.providerUuid(), identity.providerName(), candidate.id());
            return candidate;
        });
    }

    public synchronized String reservedNameSource(String name) throws SQLException {
        String nameKey = name.toLowerCase(Locale.ROOT);
        String owner = null;
        try (PreparedStatement statement = allocatedNamespaces(); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                if (!nameKey.startsWith(rows.getString("name_prefix").toLowerCase(Locale.ROOT))) continue;
                String sourceId = rows.getString("source_id");
                if (owner != null && !owner.equals(sourceId)) {
                    throw conflict("NAMESPACE_CONFLICT", "Multiple persisted sources reserve this local name namespace");
                }
                owner = sourceId;
            }
        }
        return owner;
    }

    public synchronized boolean isReservedCustomName(String name) throws SQLException {
        String owner = reservedNameSource(name);
        return owner != null && !OFFLINE_SOURCE_ID.equals(owner);
    }

    public synchronized boolean isReservedOfflineName(String name) throws SQLException {
        return OFFLINE_SOURCE_ID.equals(reservedNameSource(name));
    }

    /** Allocate a real local name while keeping the effective UUID and password account key. */
    public synchronized OfflineProfile resolveOffline(String accountName, UUID uuid, String configuredPrefix,
                                                       Consumer<LocalProfile> guard) throws SQLException {
        requireText(accountName, "offline account name", 64);
        Objects.requireNonNull(uuid, "offline UUID");
        Objects.requireNonNull(guard, "guard");
        return transaction(() -> {
            assertSourceType(OFFLINE_SOURCE_ID, "OFFLINE");
            OfflineProfile existing = findOfflineByAccountName(accountName);
            if (existing != null && (!existing.accountName().equals(accountName) || !existing.profile().uuid().equals(uuid))) {
                throw conflict("LEGACY_IDENTITY_CHANGED", "The offline account name, case or UUID differs from its reserved identity");
            }
            assertOfflineAllowed(accountName, uuid);
            String prefix = ExternalProfileNames.normalizePrefix(configuredPrefix);
            if (existing != null && existing.profile().namePrefix().equals(prefix)) {
                guard.accept(existing.profile());
                return existing;
            }

            String localName = ExternalProfileNames.localName(prefix, accountName);
            assertNamespaceAvailable(OFFLINE_SOURCE_ID, prefix, localName);
            LocalProfile candidate = new LocalProfile(existing == null ? UUID.randomUUID().toString() : existing.profile().id(),
                    localName, uuid, prefix);
            assertNameAvailable(localName, existing == null ? null : existing.profile().id());
            if (existing == null && findByUuid(uuid) != null) {
                throw conflict("UUID_COLLISION", "UUID already belongs to another profile; automatic identity merging is disabled");
            }
            guard.accept(candidate);
            if (existing == null) {
                insertProfile(candidate, accountName);
                insertIdentity(OFFLINE_SOURCE_ID, uuid.toString(), "OFFLINE", uuid, accountName, candidate.id());
            } else if (!candidate.equals(existing.profile())) {
                renameProfile(existing.profile(), candidate);
            }
            return new OfflineProfile(candidate, accountName);
        });
    }

    /** Allocated identities remain managed after clearing their prefix; plain records are not aliases. */
    public synchronized OfflineProfile findOfflineByLocalName(String localName) throws SQLException {
        String key = localName.toLowerCase(Locale.ROOT);
        OfflineProfile current = findOffline("""
                p.name_key = ? AND (p.name_prefix <> '' OR EXISTS
                    (SELECT 1 FROM profile_name_history h WHERE h.profile_id = p.id))
                """, key);
        return current != null ? current : findOffline("""
                EXISTS (SELECT 1 FROM profile_name_history h WHERE h.profile_id = p.id
                    AND h.name_key = ? AND h.name_prefix <> '')
                """, key);
    }

    public synchronized OfflineProfile findOfflineByAccountName(String accountName) throws SQLException {
        return findOffline("lower(i.provider_name) = ?", accountName.toLowerCase(Locale.ROOT));
    }

    private OfflineProfile findOffline(String condition, String value) throws SQLException {
        // The query fragment is supplied only by the two constant callers above.
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT p.id, p.name, p.uuid, p.name_prefix, i.provider_name
                FROM profile p JOIN source_identity i ON i.profile_id = p.id
                WHERE i.source_id = ? AND i.source_type = 'OFFLINE' AND
                """ + condition)) {
            statement.setString(1, OFFLINE_SOURCE_ID);
            statement.setString(2, value);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? new OfflineProfile(profile(rows), rows.getString("provider_name")) : null;
            }
        }
    }

    public synchronized boolean hasOfflineAliases() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM profile p JOIN source_identity i ON i.profile_id = p.id
                WHERE i.source_id = ? AND i.source_type = 'OFFLINE' AND (p.name_prefix <> '' OR EXISTS
                    (SELECT 1 FROM profile_name_history h WHERE h.profile_id = p.id)) LIMIT 1
                """)) {
            statement.setString(1, OFFLINE_SOURCE_ID);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** Reserve the already-selected raw legacy UUID; this method never generates a replacement. */
    public synchronized LocalProfile reserveLegacy(String name, UUID uuid, String source) throws SQLException {
        requireText(name, "legacy name", 64);
        Objects.requireNonNull(uuid, "legacy UUID");
        if (!"OFFLINE".equals(source) && !"FLOODGATE".equals(source)) {
            throw new IllegalArgumentException("Legacy source must be OFFLINE or FLOODGATE");
        }
        String sourceId = "__easyauth_" + source.toLowerCase(Locale.ROOT);
        String subject = uuid.toString();
        return transaction(() -> {
            assertOutsideAllocatedNamespace(name);
            LocalProfile existing = findIdentity(sourceId, subject);
            if (existing != null) {
                if (!existing.name().equals(name) || !existing.uuid().equals(uuid)) {
                    throw conflict("LEGACY_IDENTITY_CHANGED", "Existing legacy profile name or UUID differs; explicit administration is required");
                }
                return existing;
            }
            assertVacant(name, uuid);
            LocalProfile profile = new LocalProfile(UUID.randomUUID().toString(), name, uuid);
            insertProfile(profile);
            insertIdentity(sourceId, subject, source, uuid, name, profile.id());
            return profile;
        });
    }

    /** Prevent a failed or disabled external provider from turning its profile into a password account. */
    public synchronized void assertOfflineAllowed(String name, UUID uuid) throws SQLException {
        OfflineProfile mapped = findOfflineByAccountName(name);
        if (mapped != null && !mapped.profile().namePrefix().isEmpty()) {
            if (!mapped.accountName().equals(name) || !mapped.profile().uuid().equals(uuid)
                    || !isRawOfflineProfile(mapped.profile().id())) {
                throw conflict("LEGACY_IDENTITY_CHANGED", "The offline account name, case or UUID differs from its reserved identity");
            }
            return;
        }
        assertOutsideAllocatedNamespace(name);
        LocalProfile byName = findNameOwner(name);
        LocalProfile byUuid = findByUuid(uuid);
        if (byName != null && (!byName.uuid().equals(uuid) || !isRawOfflineProfile(byName.id()))) {
            throw conflict("SOURCE_AUTHENTICATION_REQUIRED", "This player name belongs to another identity; log in with its original authentication source");
        }
        if (byUuid != null && (!byUuid.name().equals(name) || !isRawOfflineProfile(byUuid.id()))) {
            throw conflict("UUID_COLLISION", "This UUID is reserved by another identity");
        }
    }

    public synchronized LocalProfile findByName(String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, name, uuid, name_prefix FROM profile WHERE name_key = ?")) {
            statement.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? profile(rows) : null;
            }
        }
    }

    /** Includes retained backend keys and old local names, which must never be reassigned. */
    public synchronized LocalProfile findNameOwner(String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT p.id, p.name, p.uuid, p.name_prefix FROM profile p
                WHERE p.name_key = ? OR lower(p.account_name) = ? OR EXISTS
                    (SELECT 1 FROM profile_name_history h WHERE h.profile_id = p.id AND h.name_key = ?)
                """)) {
            String key = name.toLowerCase(Locale.ROOT);
            for (int i = 1; i <= 3; i++) statement.setString(i, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? profile(rows) : null;
            }
        }
    }

    public synchronized String accountName(LocalProfile profile) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT account_name FROM profile WHERE id = ?")) {
            statement.setString(1, profile.id());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : profile.name();
            }
        }
    }

    public synchronized boolean isProtectedName(String name) throws SQLException {
        LocalProfile owner = findNameOwner(name);
        return owner != null && customExists("p.id = ?", owner.id());
    }

    public synchronized boolean isProtectedUuid(UUID uuid) throws SQLException {
        return customExists("p.uuid = ?", uuid.toString());
    }

    public synchronized boolean hasCustomIdentities() throws SQLException {
        return customExists("1 = ?", "1");
    }

    private boolean customExists(String condition, String value) throws SQLException {
        // condition is always a constant supplied by this class, never config or provider data.
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM source_identity i JOIN profile p ON p.id = i.profile_id
                WHERE i.source_type IN ('UNION', 'SKIN_SITE') AND
                """ + condition + " LIMIT 1")) {
            if ("1 = ?".equals(condition)) statement.setInt(1, 1);
            else statement.setString(1, value);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private PreparedStatement allocatedNamespaces() throws SQLException {
        return connection.prepareStatement("""
                SELECT DISTINCT i.source_id, p.name_prefix FROM source_identity i JOIN profile p ON p.id = i.profile_id
                WHERE i.source_type IN ('UNION', 'SKIN_SITE', 'OFFLINE') AND p.name_prefix <> ''
                UNION
                SELECT DISTINCT i.source_id, h.name_prefix FROM source_identity i JOIN profile_name_history h ON h.profile_id = i.profile_id
                WHERE i.source_type IN ('UNION', 'SKIN_SITE', 'OFFLINE') AND h.name_prefix <> ''
                """);
    }

    private void assertOutsideAllocatedNamespace(String name) throws SQLException {
        if (reservedNameSource(name) != null) {
            throw conflict("RESERVED_LOCAL_NAMESPACE", "This local name prefix is server allocated; a raw password account cannot claim it");
        }
    }

    private void assertNamespaceAvailable(String sourceId, String prefix, String localName) throws SQLException {
        String prefixKey = prefix.toLowerCase(Locale.ROOT);
        String nameKey = localName.toLowerCase(Locale.ROOT);
        try (PreparedStatement statement = allocatedNamespaces(); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                if (sourceId.equals(rows.getString("source_id"))) continue;
                String reserved = rows.getString("name_prefix").toLowerCase(Locale.ROOT);
                if (nameKey.startsWith(reserved)
                        || (!prefixKey.isEmpty() && (prefixKey.startsWith(reserved) || reserved.startsWith(prefixKey)))) {
                    throw conflict("NAMESPACE_CONFLICT", "Another authentication source reserves this local name prefix");
                }
            }
        }
    }

    private boolean hasOfficialUuid(UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM source_identity i JOIN profile p ON p.id = i.profile_id
                WHERE i.source_type = 'OFFICIAL' AND (i.provider_uuid = ? OR p.uuid = ?) LIMIT 1
                """)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, uuid.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private boolean isRawOfflineProfile(String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT source_type FROM source_identity WHERE profile_id = ?")) {
            statement.setString(1, profileId);
            boolean found = false;
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found = true;
                    if (!"OFFLINE".equals(rows.getString(1))) return false;
                }
            }
            return found;
        }
    }

    private void assertSourceType(String sourceId, String type) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM source_identity WHERE source_id = ? AND source_type <> ? LIMIT 1")) {
            statement.setString(1, sourceId);
            statement.setString(2, type);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) throw conflict("SOURCE_TYPE_CHANGED", "A persisted source id cannot change authentication type");
            }
        }
    }

    private LocalProfile findIdentity(String sourceId, String subjectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT p.id, p.name, p.uuid, p.name_prefix FROM profile p JOIN source_identity i ON i.profile_id = p.id
                WHERE i.source_id = ? AND i.subject_id = ?
                """)) {
            statement.setString(1, sourceId);
            statement.setString(2, subjectId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? profile(rows) : null;
            }
        }
    }

    public synchronized LocalProfile findByUuid(UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, name, uuid, name_prefix FROM profile WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? profile(rows) : null;
            }
        }
    }

    private void assertVacant(String name, UUID uuid) throws SQLException {
        assertNameAvailable(name, null);
        if (findByUuid(uuid) != null) {
            throw conflict("UUID_COLLISION", "UUID already belongs to another profile; automatic identity merging is disabled");
        }
    }

    private void assertNameAvailable(String name, String permittedProfileId) throws SQLException {
        LocalProfile local = findNameOwner(name);
        OfflineProfile account = findOfflineByAccountName(name);
        if ((local != null && !local.id().equals(permittedProfileId))
                || (account != null && !account.profile().id().equals(permittedProfileId))) {
            throw conflict("NAME_COLLISION", "Player name or password account key already belongs to another profile; automatic identity merging is disabled");
        }
    }

    private void insertProfile(LocalProfile profile) throws SQLException {
        insertProfile(profile, profile.name());
    }

    private void insertProfile(LocalProfile profile, String accountName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO profile(id,name,name_key,uuid,name_prefix,account_name) VALUES (?,?,?,?,?,?)")) {
            statement.setString(1, profile.id());
            statement.setString(2, profile.name());
            statement.setString(3, profile.name().toLowerCase(Locale.ROOT));
            statement.setString(4, profile.uuid().toString());
            statement.setString(5, profile.namePrefix());
            statement.setString(6, accountName);
            statement.executeUpdate();
        }
    }

    private boolean hasNameHistory(String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM profile_name_history WHERE profile_id = ? LIMIT 1")) {
            statement.setString(1, profileId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private void renameProfile(LocalProfile previous, LocalProfile candidate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO profile_name_history(name_key, profile_id, name_prefix) VALUES (?,?,?)
                ON CONFLICT(name_key) DO NOTHING
                """)) {
            statement.setString(1, previous.name().toLowerCase(Locale.ROOT));
            statement.setString(2, previous.id());
            statement.setString(3, previous.namePrefix());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE profile SET name = ?, name_key = ?, name_prefix = ? WHERE id = ?")) {
            statement.setString(1, candidate.name());
            statement.setString(2, candidate.name().toLowerCase(Locale.ROOT));
            statement.setString(3, candidate.namePrefix());
            statement.setString(4, candidate.id());
            if (statement.executeUpdate() != 1) throw new SQLException("Profile disappeared during prefix refresh");
        }
    }

    private void insertIdentity(String sourceId, String subjectId, String sourceType, UUID uuid, String name, String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO source_identity(source_id,subject_id,source_type,provider_uuid,provider_name,profile_id)
                VALUES (?,?,?,?,?,?)
                """)) {
            statement.setString(1, sourceId);
            statement.setString(2, subjectId);
            statement.setString(3, sourceType);
            statement.setString(4, uuid.toString());
            statement.setString(5, name);
            statement.setString(6, profileId);
            statement.executeUpdate();
        }
    }

    private void updateProvider(AuthenticatedIdentity identity) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE source_identity SET provider_uuid = ?, provider_name = ? WHERE source_id = ? AND subject_id = ?
                """)) {
            statement.setString(1, identity.providerUuid().toString());
            statement.setString(2, identity.providerName());
            statement.setString(3, identity.sourceId());
            statement.setString(4, identity.subjectId());
            if (statement.executeUpdate() != 1) throw new SQLException("Identity disappeared during update");
        }
    }

    private static LocalProfile profile(ResultSet rows) throws SQLException {
        try {
            return new LocalProfile(rows.getString("id"), rows.getString("name"), UUID.fromString(rows.getString("uuid")), rows.getString("name_prefix"));
        } catch (IllegalArgumentException e) {
            throw new SQLException("Identity ledger contains an invalid UUID", e);
        }
    }

    @FunctionalInterface private interface SqlAction<T> { T execute() throws SQLException; }

    private <T> T transaction(SqlAction<T> action) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                T value = action.execute();
                statement.execute("COMMIT");
                return value;
            } catch (SQLException | RuntimeException e) {
                try {
                    statement.execute("ROLLBACK");
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
        }
    }

    private static void requireText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    private static IdentityConflictException conflict(String code, String message) {
        return new IdentityConflictException(code, message);
    }

    @Override public synchronized void close() throws SQLException {
        connection.close();
    }
}
