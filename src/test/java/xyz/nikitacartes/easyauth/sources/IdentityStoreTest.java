package xyz.nikitacartes.easyauth.sources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class IdentityStoreTest {
    @TempDir Path directory;
    private static final IdentityStore.LegacyGuard AVAILABLE = (identity, profile, existing) -> {};

    private static AuthenticatedIdentity identity(String source, SourceType type, String subject, UUID uuid, String name) {
        return new AuthenticatedIdentity(source, type, subject, uuid, name, List.of());
    }

    private static AuthenticatedIdentity union(String subject, String name) {
        return identity("union", SourceType.UNION, subject, UUID.randomUUID(), name);
    }

    @Test void unionRemapAndRenameKeepLocalUuidAndNameAfterReopen() throws Exception {
        Path file = directory.resolve("identities.db");
        IdentityStore.LocalProfile original;
        try (IdentityStore store = new IdentityStore(file)) {
            original = store.resolve(union("stable-internal-id", "Alice"), AVAILABLE);
            assertEquals(7, original.uuid().version());
            assertEquals(2, original.uuid().variant());
        }
        try (IdentityStore store = new IdentityStore(file)) {
            var remapped = store.resolve(union("stable-internal-id", "Renamed"), AVAILABLE);
            assertEquals(original, remapped);
            assertTrue(store.isProtectedName("aLiCe"));
            assertTrue(store.isProtectedUuid(original.uuid()));
        }
    }

    @Test void officialUuidRemainsRaw() throws Exception {
        UUID raw = UUID.randomUUID();
        try (IdentityStore store = new IdentityStore(directory.resolve("identities.db"))) {
            assertEquals(raw, store.resolve(identity("official", SourceType.OFFICIAL, raw.toString(), raw, "Official"), AVAILABLE).uuid());
        }
    }

    @Test void nativeSkinV7RemainsRawAndOtherVersionsAreMapped() throws Exception {
        UUID nativeUuid = UUID.fromString("01900000-0000-7000-8000-000000000001");
        UUID v4 = UUID.randomUUID();
        try (IdentityStore store = new IdentityStore(directory.resolve("identities.db"))) {
            assertEquals(nativeUuid, store.resolve(identity("skin", SourceType.SKIN_SITE, "native", nativeUuid, "Native"), AVAILABLE).uuid());
            UUID mapped = store.resolve(identity("skin", SourceType.SKIN_SITE, "mapped", v4, "Mapped"), AVAILABLE).uuid();
            assertEquals(7, mapped.version());
            assertNotEquals(v4, mapped);
        }
    }

    @Test void sameNameNeverMergesDifferentSourcesOrSubjects() throws Exception {
        try (IdentityStore store = new IdentityStore(directory.resolve("identities.db"))) {
            store.resolve(union("first", "Alice"), AVAILABLE);
            assertThrows(IdentityConflictException.class, () -> store.resolve(union("second", "ALICE"), AVAILABLE));
            assertThrows(IdentityConflictException.class, () -> store.resolve(identity("other", SourceType.UNION, "first", UUID.randomUUID(), "Alice"), AVAILABLE));
        }
    }

    @Test void duplicateNativeUuidNeverMergesDifferentProfiles() throws Exception {
        UUID nativeUuid = UUID.fromString("01900000-0000-7000-8000-000000000001");
        try (IdentityStore store = new IdentityStore(directory.resolve("identities.db"))) {
            store.resolve(identity("skin", SourceType.SKIN_SITE, "first", nativeUuid, "Alice"), AVAILABLE);
            assertThrows(IdentityConflictException.class, () -> store.resolve(identity("skin2", SourceType.SKIN_SITE, "second", nativeUuid, "Bob"), AVAILABLE));
        }
    }

    @Test void changedNativeSkinUuidIsRejectedOnReturnWithoutMovingProfile() throws Exception {
        UUID first = UUID.fromString("01900000-0000-7000-8000-000000000001");
        UUID second = UUID.fromString("01900000-0000-7000-8000-000000000002");
        try (IdentityStore store = new IdentityStore(directory.resolve("identities.db"))) {
            var original = store.resolve(identity("skin", SourceType.SKIN_SITE, "stable", first, "Alice"), AVAILABLE);
            store.resolve(identity("skin", SourceType.SKIN_SITE, "other", second, "Bob"), AVAILABLE);
            assertThrows(IdentityConflictException.class,
                    () -> store.resolve(identity("skin", SourceType.SKIN_SITE, "stable", second, "Alice"), AVAILABLE));
            assertEquals(original, store.findByName("Alice"));
        }
    }

    @Test void mappedSkinIdentityCannotSilentlySwitchToNewNativeV7() throws Exception {
        UUID nativeUuid = UUID.fromString("01900000-0000-7000-8000-000000000001");
        try (IdentityStore store = new IdentityStore(directory.resolve("identities.db"))) {
            var original = store.resolve(identity("skin", SourceType.SKIN_SITE, "stable", UUID.randomUUID(), "Alice"), AVAILABLE);
            assertThrows(IdentityConflictException.class,
                    () -> store.resolve(identity("skin", SourceType.SKIN_SITE, "stable", nativeUuid, "Alice"), AVAILABLE));
            assertEquals(original, store.findByName("Alice"));
        }
    }

    @Test void customProviderCannotReturnAnOfficialUuidEvenWhenMappedToV7() throws Exception {
        UUID official = UUID.randomUUID();
        try (IdentityStore store = new IdentityStore(directory.resolve("identities.db"))) {
            store.resolve(identity("official", SourceType.OFFICIAL, official.toString(), official, "Official"), AVAILABLE);
            IdentityConflictException rejected = assertThrows(IdentityConflictException.class,
                    () -> store.resolve(identity("union", SourceType.UNION, "bad", official, "Other"), AVAILABLE));
            assertEquals("OFFICIAL_UUID_COLLISION", rejected.code());
        }
    }

    @Test void changingSourceTypeCannotReusePersistedIdentity() throws Exception {
        UUID raw = UUID.randomUUID();
        try (IdentityStore store = new IdentityStore(directory.resolve("identities.db"))) {
            store.resolve(identity("provider", SourceType.UNION, "same", raw, "Alice"), AVAILABLE);
            assertThrows(IdentityConflictException.class,
                    () -> store.resolve(identity("provider", SourceType.SKIN_SITE, "same", raw, "Alice"), AVAILABLE));
        }
    }

    @Test void legacyGuardRunsBeforePersistingAndAlsoOnReturnVisit() throws Exception {
        AuthenticatedIdentity identity = union("one", "Alice");
        try (IdentityStore store = new IdentityStore(directory.resolve("identities.db"))) {
            assertThrows(IdentityConflictException.class, () -> store.resolve(identity, (id, profile, existing) -> {
                assertFalse(existing);
                throw new IdentityConflictException("LEGACY_COLLISION", "Legacy account owns this name");
            }));
            assertNull(store.findByName("Alice"));
            store.resolve(identity, AVAILABLE);
            assertThrows(IdentityConflictException.class, () -> store.resolve(identity, (id, profile, existing) -> {
                assertTrue(existing);
                throw new IdentityConflictException("LEGACY_COLLISION", "Legacy state was changed");
            }));
        }
    }

    @Test void failedIdentityInsertRollsBackProfileAndDoesNotInventNewUuidOnRetry() throws Exception {
        Path file = directory.resolve("identities.db");
        try (IdentityStore store = new IdentityStore(file)) {
            try (var external = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = external.createStatement()) {
                statement.execute("CREATE TRIGGER fail_identity BEFORE INSERT ON source_identity BEGIN SELECT RAISE(ABORT, 'test disk failure'); END");
            }
            var identity = union("stable", "Alice");
            assertThrows(java.sql.SQLException.class, () -> store.resolve(identity, AVAILABLE));
            assertNull(store.findByName("Alice"));
            try (var external = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = external.createStatement()) {
                statement.execute("DROP TRIGGER fail_identity");
            }
            var created = store.resolve(identity, AVAILABLE);
            assertEquals(created, store.resolve(identity, AVAILABLE));
        }
    }

    @Test void concurrentFirstLoginReturnsExactlyOnePersistedIdentity() throws Exception {
        AuthenticatedIdentity identity = union("stable", "Alice");
        try (IdentityStore store = new IdentityStore(directory.resolve("identities.db"))) {
            var pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<IdentityStore.LocalProfile>> calls = new ArrayList<>();
                for (int i = 0; i < 32; i++) calls.add(() -> store.resolve(identity, AVAILABLE));
                var results = pool.invokeAll(calls);
                var expected = results.get(0).get();
                for (var result : results) assertEquals(expected, result.get());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test void rawLegacyUuidIsReservedWithoutChangingIt() throws Exception {
        UUID raw = UUID.nameUUIDFromBytes("OfflinePlayer:Alice".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (IdentityStore store = new IdentityStore(directory.resolve("identities.db"))) {
            var reserved = store.reserveLegacy("Alice", raw, "OFFLINE");
            assertEquals(raw, reserved.uuid());
            assertEquals(reserved, store.reserveLegacy("Alice", raw, "OFFLINE"));
            store.assertOfflineAllowed("Alice", raw);
            assertFalse(store.hasCustomIdentities());
            assertThrows(IdentityConflictException.class, () -> store.resolve(union("evil", "Alice"), AVAILABLE));
            assertThrows(IdentityConflictException.class, () -> store.reserveLegacy("Alice", raw, "FLOODGATE"));
        }
    }

    @Test void persistedCustomIdentitiesBlockPasswordFallbackEvenAfterReopen() throws Exception {
        Path file = directory.resolve("identities.db");
        UUID local;
        try (IdentityStore store = new IdentityStore(file)) {
            local = store.resolve(union("stable", "Alice"), AVAILABLE).uuid();
        }
        try (IdentityStore store = new IdentityStore(file)) {
            assertTrue(store.hasCustomIdentities());
            assertThrows(IdentityConflictException.class, () -> store.assertOfflineAllowed("Alice", UUID.randomUUID()));
            assertThrows(IdentityConflictException.class, () -> store.assertOfflineAllowed("Other", local));
            assertThrows(IdentityConflictException.class, () -> store.reserveLegacy("Alice", local, "OFFLINE"));
        }
    }

    @Test void uuidV7ContainsCurrentTimestampAndHasRfcVariant() {
        long before = System.currentTimeMillis();
        UUID uuid = UuidV7.generate();
        long after = System.currentTimeMillis();
        assertEquals(7, uuid.version());
        assertEquals(2, uuid.variant());
        long timestamp = uuid.getMostSignificantBits() >>> 16;
        assertTrue(timestamp >= before && timestamp <= after);
        assertNotEquals(uuid, UuidV7.generate());
    }
}
