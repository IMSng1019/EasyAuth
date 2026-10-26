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

/**
 * Single-server identity ledger, independent of the legacy password database backend.
 * Every assignment is durable before it is returned. Names and UUIDs reserve identities;
 * they never prove that two authentication sources belong to the same person.
 */
public final class IdentityStore implements AutoCloseable {
    public record LocalProfile(String id, String name, UUID uuid) {}

    @FunctionalInterface
    public interface LegacyGuard {
        /** Runs inside the ledger transaction, including on an existing identity's return visit. */
        void check(AuthenticatedIdentity identity, LocalProfile profile, boolean existingIdentity);
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
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                if (version.next() && version.getInt(1) > 1) {
                    throw new SQLException("Identity ledger was created by a newer EasyAuth version");
                }
            }
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS profile (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT UNIQUE NOT NULL,
                        name_key TEXT UNIQUE NOT NULL,
                        uuid TEXT UNIQUE NOT NULL
                    )
                    """);
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
            statement.execute("PRAGMA user_version = 1");
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    public synchronized LocalProfile resolve(AuthenticatedIdentity identity, LegacyGuard guard) throws SQLException {
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
                // In particular, a Union remap or upstream rename must not move local world data.
                guard.check(identity, existing, true);
                updateProvider(identity);
                return existing;
            }

            UUID localUuid = switch (identity.type()) {
                case OFFICIAL -> identity.providerUuid();
                case SKIN_SITE -> identity.providerUuid().version() == 7 && identity.providerUuid().variant() == 2
                        ? identity.providerUuid() : UuidV7.generate();
                case UNION -> UuidV7.generate();
            };
            LocalProfile candidate = new LocalProfile(UUID.randomUUID().toString(), identity.providerName(), localUuid);
            assertVacant(candidate.name(), candidate.uuid());
            guard.check(identity, candidate, false);
            insertProfile(candidate);
            insertIdentity(identity.sourceId(), identity.subjectId(), identity.type().name(),
                    identity.providerUuid(), identity.providerName(), candidate.id());
            return candidate;
        });
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
        LocalProfile byName = findByName(name);
        LocalProfile byUuid = findByUuid(uuid);
        if (byName != null && (!byName.uuid().equals(uuid) || !isRawOfflineProfile(byName.id()))) {
            throw conflict("SOURCE_AUTHENTICATION_REQUIRED", "This player name belongs to another identity; log in with its original authentication source");
        }
        if (byUuid != null && (!byUuid.name().equals(name) || !isRawOfflineProfile(byUuid.id()))) {
            throw conflict("UUID_COLLISION", "This UUID is reserved by another identity");
        }
    }

    public synchronized LocalProfile findByName(String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, name, uuid FROM profile WHERE name_key = ?")) {
            statement.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? profile(rows) : null;
            }
        }
    }

    public synchronized boolean isProtectedName(String name) throws SQLException {
        return customExists("p.name_key = ?", name.toLowerCase(Locale.ROOT));
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
                SELECT p.id, p.name, p.uuid FROM profile p JOIN source_identity i ON i.profile_id = p.id
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
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, name, uuid FROM profile WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? profile(rows) : null;
            }
        }
    }

    private void assertVacant(String name, UUID uuid) throws SQLException {
        if (findByName(name) != null) {
            throw conflict("NAME_COLLISION", "Player name already belongs to another profile; automatic identity merging is disabled");
        }
        if (findByUuid(uuid) != null) {
            throw conflict("UUID_COLLISION", "UUID already belongs to another profile; automatic identity merging is disabled");
        }
    }

    private void insertProfile(LocalProfile profile) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO profile(id,name,name_key,uuid) VALUES (?,?,?,?)")) {
            statement.setString(1, profile.id());
            statement.setString(2, profile.name());
            statement.setString(3, profile.name().toLowerCase(Locale.ROOT));
            statement.setString(4, profile.uuid().toString());
            statement.executeUpdate();
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
            return new LocalProfile(rows.getString("id"), rows.getString("name"), UUID.fromString(rows.getString("uuid")));
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
