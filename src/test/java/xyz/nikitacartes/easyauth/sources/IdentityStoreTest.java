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

    @Test void prefixedUnionIsIndependentFromSameNamedOfficialAccount() throws Exception {
        Path file = directory.resolve("identities.db");
        UUID officialUuid = UUID.randomUUID();
        UUID unionUuid = UUID.randomUUID();
        IdentityStore.LocalProfile local;
        try (IdentityStore store = new IdentityStore(file)) {
            var official = store.resolve(identity("official", SourceType.OFFICIAL, officialUuid.toString(), officialUuid, "LXW1019"), "[IGNORED]", AVAILABLE);
            var guard = new LegacyIdentityPolicy(() -> List.of(
                    new LegacyIdentityPolicy.Account("LXW1019", officialUuid, null, true, false)), List.of(directory));
            local = store.resolve(identity("union", SourceType.UNION, "42", unionUuid, "LXW1019"), "[MUA] ", guard);
            assertEquals("LXW1019", official.name());
            assertEquals(officialUuid, official.uuid());
            assertEquals("", official.namePrefix());
            assertEquals("[MUA]LXW1019", local.name());
            assertEquals("[MUA]", local.namePrefix());
            assertEquals(7, local.uuid().version());
            assertNotEquals(official.uuid(), local.uuid());
            assertNotEquals(official.id(), local.id());
            assertEquals(official, store.findByName("LXW1019"));
            assertEquals(local, store.findByName("[MUA]LXW1019"));
        }
        try (IdentityStore store = new IdentityStore(file)) {
            var updated = store.resolve(identity("union", SourceType.UNION, "42", UUID.randomUUID(), "Renamed"), "[NEW]", AVAILABLE);
            assertEquals(new IdentityStore.LocalProfile(local.id(), "[NEW]LXW1019", local.uuid(), "[NEW]"), updated);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT provider_name, name_prefix FROM source_identity JOIN profile ON profile.id = profile_id WHERE source_id = 'union'")) {
            assertTrue(rows.next());
            assertEquals("Renamed", rows.getString("provider_name"), "Provider metadata must retain the raw upstream name");
            assertEquals("[NEW]", rows.getString("name_prefix"));
        }
    }

    @Test void skinPrefixChangesNameWithoutChangingItsNativeUuid() throws Exception {
        UUID nativeUuid = UuidV7.generate();
        try (IdentityStore store = new IdentityStore(directory.resolve("identities.db"))) {
            var local = store.resolve(identity("skin", SourceType.SKIN_SITE, "stable", nativeUuid, "Alice"), "[SKIN]", AVAILABLE);
            assertEquals("[SKIN]Alice", local.name());
            assertEquals(nativeUuid, local.uuid());
        }
    }

    @Test void overlongOrInvalidPrefixedNameCannotCreateAnyIdentity() throws Exception {
        try (IdentityStore store = new IdentityStore(directory.resolve("identities.db"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> store.resolve(union("long", "abcdefghijklmnop"), "[MUA]", AVAILABLE));
            assertThrows(IllegalArgumentException.class,
                    () -> store.resolve(union("invalid", "Alice"), "[高校]", AVAILABLE));
            assertFalse(store.hasCustomIdentities());
            assertNull(store.findByName("abcdefghijklmnop"));
            assertNull(store.findByName("Alice"));
        }
    }

    @Test void localPrefixDoesNotPermitOfficialUuidImpersonation() throws Exception {
        UUID officialUuid = UUID.randomUUID();
        try (IdentityStore store = new IdentityStore(directory.resolve("identities.db"))) {
            store.resolve(identity("official", SourceType.OFFICIAL, officialUuid.toString(), officialUuid, "LXW1019"), AVAILABLE);
            var conflict = assertThrows(IdentityConflictException.class,
                    () -> store.resolve(identity("union", SourceType.UNION, "evil", officialUuid, "LXW1019"), "[MUA]", AVAILABLE));
            assertEquals("OFFICIAL_UUID_COLLISION", conflict.code());
            assertNull(store.findByName("[MUA]LXW1019"));
        }
    }

    @Test void allocatedCustomNamespaceProtectsOtherNamesAfterReopen() throws Exception {
        Path file = directory.resolve("identities.db");
        try (IdentityStore store = new IdentityStore(file)) {
            store.resolve(union("first", "Alice"), "[MUA]", AVAILABLE);
        }
        try (IdentityStore store = new IdentityStore(file)) {
            assertEquals("union", store.reservedNameSource("[mua]NotRegistered"));
            assertTrue(store.isReservedCustomName("[MUA]Bob"));
            assertNull(store.reservedNameSource("Not[MUA]Bob"));
            assertThrows(IdentityConflictException.class,
                    () -> store.assertOfflineAllowed("[MUA]Bob", UUID.randomUUID()));
            assertThrows(IdentityConflictException.class,
                    () -> store.reserveLegacy("[MUA]Bob", UUID.randomUUID(), "OFFLINE"));
        }
    }

    @Test void anotherSourceCannotClaimPersistedNamespaceOrOverlappingPrefix() throws Exception {
        try (IdentityStore store = new IdentityStore(directory.resolve("identities.db"))) {
            store.resolve(union("first", "Alice"), "[MUA]", AVAILABLE);
            assertThrows(IdentityConflictException.class, () -> store.resolve(
                    identity("other", SourceType.SKIN_SITE, "one", UuidV7.generate(), "[MUA]Bob"), "", AVAILABLE));
            assertThrows(IdentityConflictException.class, () -> store.resolve(
                    identity("other", SourceType.SKIN_SITE, "two", UuidV7.generate(), "Bob"), "[mua]", AVAILABLE));
            assertThrows(IdentityConflictException.class, () -> store.resolve(
                    identity("other", SourceType.SKIN_SITE, "three", UuidV7.generate(), "Bob"), "[M", AVAILABLE));
            assertEquals("[MUA]Bob", store.resolve(union("second", "Bob"), "[MUA]", AVAILABLE).name());
        }
    }

    @Test void verifiedOfficialIdentityIsNotBlockedByNamespaceAlone() throws Exception {
        UUID officialUuid = UUID.randomUUID();
        try (IdentityStore store = new IdentityStore(directory.resolve("identities.db"))) {
            store.resolve(union("first", "Alice"), "M_", AVAILABLE);
            var official = store.resolve(identity("official", SourceType.OFFICIAL, officialUuid.toString(), officialUuid, "M_Bob"), AVAILABLE);
            assertEquals("M_Bob", official.name());
            assertEquals(officialUuid, official.uuid());
        }
    }

    @Test void schemaOneUpgradePreservesOldUnprefixedIdentity() throws Exception {
        Path file = directory.resolve("old-identities.db");
        UUID oldUuid = UuidV7.generate();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE profile(id TEXT PRIMARY KEY NOT NULL,name TEXT UNIQUE NOT NULL,name_key TEXT UNIQUE NOT NULL,uuid TEXT UNIQUE NOT NULL)");
            statement.execute("CREATE TABLE source_identity(source_id TEXT NOT NULL,subject_id TEXT NOT NULL,source_type TEXT NOT NULL,provider_uuid TEXT NOT NULL,provider_name TEXT NOT NULL,profile_id TEXT NOT NULL REFERENCES profile(id),PRIMARY KEY(source_id,subject_id))");
            statement.execute("INSERT INTO profile VALUES ('old-profile','Alice','alice','" + oldUuid + "')");
            statement.execute("INSERT INTO source_identity VALUES ('union','legacy-42','UNION','" + UUID.randomUUID() + "','Alice','old-profile')");
            statement.execute("PRAGMA user_version = 1");
        }
        try (IdentityStore store = new IdentityStore(file)) {
            var existing = store.resolve(union("legacy-42", "AChangedName"), "[MUA]", AVAILABLE);
            assertEquals(new IdentityStore.LocalProfile("old-profile", "Alice", oldUuid), existing);
            assertEquals("", existing.namePrefix());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(rows.next());
            assertEquals(4, rows.getInt(1));
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

    @Test void schemaTwoOfflineAccountGainsRealPrefixWithoutChangingItsIdentity() throws Exception {
        Path file = directory.resolve("offline-upgrade.db");
        UUID uuid = UUID.randomUUID();
        IdentityStore.LocalProfile raw;
        try (IdentityStore store = new IdentityStore(file)) {
            raw = store.reserveLegacy("Alice", uuid, "OFFLINE");
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 2");
        }
        try (IdentityStore store = new IdentityStore(file)) {
            List<IdentityStore.LocalProfile> checked = new ArrayList<>();
            var mapped = store.resolveOffline("Alice", uuid, "[OFF] ", checked::add);
            assertEquals(raw.id(), mapped.profile().id());
            assertEquals(uuid, mapped.profile().uuid());
            assertEquals("[OFF]Alice", mapped.profile().name());
            assertEquals("[OFF]", mapped.profile().namePrefix());
            assertEquals("Alice", mapped.accountName());
            assertEquals(List.of(mapped.profile()), checked);
            assertEquals(mapped, store.findOfflineByAccountName("aLiCe"));
            assertEquals(mapped, store.findOfflineByLocalName("[off]ALICE"));
            assertNull(store.findByName("Alice"));
            assertTrue(store.hasOfflineAliases());
            assertFalse(store.hasCustomIdentities());
            assertFalse(store.isProtectedName(mapped.profile().name()));
            assertFalse(store.isProtectedUuid(uuid));
            store.assertOfflineAllowed("Alice", uuid);
            assertThrows(IdentityConflictException.class, () -> store.assertOfflineAllowed(mapped.profile().name(), uuid));
            assertThrows(IdentityConflictException.class, () -> store.reserveLegacy("Alice", uuid, "OFFLINE"));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(rows.next());
                assertEquals(4, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT provider_name, provider_uuid FROM source_identity WHERE source_type = 'OFFLINE'")) {
                assertTrue(rows.next());
                assertEquals("Alice", rows.getString("provider_name"));
                assertEquals(uuid.toString(), rows.getString("provider_uuid"));
                assertFalse(rows.next());
            }
        }
    }

    @Test void offlinePrefixIsUpdatedAcrossReopenAndConfigurationChanges() throws Exception {
        Path file = directory.resolve("offline-stable.db");
        UUID uuid = UUID.randomUUID();
        IdentityStore.OfflineProfile original;
        try (IdentityStore store = new IdentityStore(file)) {
            original = store.resolveOffline("Alice", uuid, "[OFF]", profile -> {});
        }
        try (IdentityStore store = new IdentityStore(file)) {
            var visits = new ArrayList<IdentityStore.LocalProfile>();
            var updated = store.resolveOffline("Alice", uuid, "[NEW]", visits::add);
            assertEquals(new IdentityStore.LocalProfile(original.profile().id(), "[NEW]Alice", uuid, "[NEW]"), updated.profile());
            var cleared = store.resolveOffline("Alice", uuid, "", visits::add);
            assertEquals(new IdentityStore.LocalProfile(original.profile().id(), "Alice", uuid, ""), cleared.profile());
            assertEquals(List.of(updated.profile(), cleared.profile()), visits);
            assertEquals(IdentityStore.OFFLINE_SOURCE_ID, store.reservedNameSource("[off]Unallocated"));
            assertTrue(store.isReservedOfflineName("[OFF]Unallocated"));
            assertFalse(store.isReservedCustomName("[OFF]Unallocated"));
            assertFalse(store.isReservedOfflineName("Other[OFF]Alice"));
            assertEquals(cleared, store.findOfflineByLocalName("[NEW]Alice"));
        }
    }

    @Test void unprefixedOfflineProfilesRemainRawUntilPrefixIsRequested() throws Exception {
        UUID uuid = UUID.randomUUID();
        try (IdentityStore store = new IdentityStore(directory.resolve("offline-raw.db"))) {
            var raw = store.resolveOffline("Alice", uuid, "", profile -> {});
            assertEquals("Alice", raw.profile().name());
            assertEquals("", raw.profile().namePrefix());
            assertEquals(raw, store.findOfflineByAccountName("Alice"));
            assertNull(store.findOfflineByLocalName("Alice"));
            assertFalse(store.hasOfflineAliases());
            assertEquals(raw.profile(), store.reserveLegacy("Alice", uuid, "OFFLINE"));
            var mapped = store.resolveOffline("Alice", uuid, "[OFF]", profile -> {});
            assertEquals(raw.profile().id(), mapped.profile().id());
            assertEquals("[OFF]Alice", mapped.profile().name());
        }
    }

    @Test void offlineNamespacesCannotBeClaimedByExternalOrDirectPasswordNames() throws Exception {
        try (IdentityStore store = new IdentityStore(directory.resolve("offline-namespace.db"))) {
            var first = store.resolveOffline("Alice", UUID.randomUUID(), "[OFF]", profile -> {});
            assertThrows(IdentityConflictException.class, () -> store.resolve(union("evil-one", "Bob"), "[off]", AVAILABLE));
            assertThrows(IdentityConflictException.class, () -> store.resolve(union("evil-two", "Bob"), "[O", AVAILABLE));
            assertThrows(IdentityConflictException.class, () -> store.resolve(union("evil-three", "[OFF]Bob"), "", AVAILABLE));
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("[OFF]Alice", first.profile().uuid(), "[OFF]", profile -> {}));
            assertThrows(IdentityConflictException.class, () -> store.reserveLegacy("[OFF]Bob", UUID.randomUUID(), "OFFLINE"));
            assertThrows(IdentityConflictException.class, () -> store.reserveLegacy("[OFF]Bob", UUID.randomUUID(), "FLOODGATE"));
            assertEquals("[OFF]Bob", store.resolveOffline("Bob", UUID.randomUUID(), "[OFF]", profile -> {}).profile().name());
        }
        try (IdentityStore store = new IdentityStore(directory.resolve("external-namespace.db"))) {
            store.resolve(union("first", "Alice"), "[MUA]", AVAILABLE);
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("Bob", UUID.randomUUID(), "[mua]", profile -> {}));
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("Bob", UUID.randomUUID(), "[M", profile -> {}));
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("[MUA]Bob", UUID.randomUUID(), "[OFF]", profile -> {}));
            assertNull(store.findOfflineByAccountName("Bob"));
            assertFalse(store.hasOfflineAliases());
        }
    }

    @Test void offlineAccountCaseAndUuidCannotSilentlyChangeOrMerge() throws Exception {
        UUID uuid = UUID.randomUUID();
        try (IdentityStore store = new IdentityStore(directory.resolve("offline-collisions.db"))) {
            var original = store.resolveOffline("Alice", uuid, "[OFF]", profile -> {});
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("alice", uuid, "[OFF]", profile -> {}));
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("ALICE", UUID.randomUUID(), "[OFF]", profile -> {}));
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("Alice", UUID.randomUUID(), "[OFF]", profile -> {}));
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("Bob", uuid, "[OFF]", profile -> {}));
            assertThrows(IdentityConflictException.class, () -> store.assertOfflineAllowed("Other", uuid));
            assertEquals(original, store.findOfflineByAccountName("ALICE"));
            assertNull(store.findOfflineByAccountName("Bob"));
            // The unchanged password database key remains reserved even though the local
            // GameProfile is now namespaced; another identity may not take over that key.
            assertThrows(IdentityConflictException.class, () -> store.resolve(union("raw-key", "Alice"), AVAILABLE));
            assertThrows(IdentityConflictException.class, () -> store.reserveLegacy("Alice", UUID.randomUUID(), "OFFLINE"));
        }
    }

    @Test void officialAndFloodgateIdentitiesCannotBeReusedByOfflineMapping() throws Exception {
        UUID officialUuid = UUID.randomUUID();
        UUID floodgateUuid = UUID.randomUUID();
        try (IdentityStore store = new IdentityStore(directory.resolve("offline-official.db"))) {
            store.resolve(identity("official", SourceType.OFFICIAL, officialUuid.toString(), officialUuid, "Alice"), AVAILABLE);
            store.reserveLegacy("Bedrock", floodgateUuid, "FLOODGATE");
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("Alice", UUID.randomUUID(), "[OFF]", profile -> {}));
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("Bob", officialUuid, "[OFF]", profile -> {}));
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("Bedrock", floodgateUuid, "[OFF]", profile -> {}));
            assertFalse(store.hasOfflineAliases());
        }
        try (IdentityStore store = new IdentityStore(directory.resolve("offline-target-collision.db"))) {
            store.resolve(identity("official", SourceType.OFFICIAL, officialUuid.toString(), officialUuid, "[OFF]Bob"), AVAILABLE);
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("Bob", UUID.randomUUID(), "[OFF]", profile -> {}));
            assertNull(store.findOfflineByAccountName("Bob"));
        }
    }

    @Test void offlineGuardFailureRollsBackCreationAndUpgradeAndRunsOnEveryReturn() throws Exception {
        UUID uuid = UUID.randomUUID();
        try (IdentityStore store = new IdentityStore(directory.resolve("offline-guard.db"))) {
            java.util.function.Consumer<IdentityStore.LocalProfile> reject = profile -> {
                throw new IdentityConflictException("BACKEND_COLLISION", "Legacy account changed");
            };
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("Alice", uuid, "[OFF]", reject));
            assertNull(store.findOfflineByAccountName("Alice"));
            assertNull(store.findByUuid(uuid));
            var raw = store.reserveLegacy("Alice", uuid, "OFFLINE");
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("Alice", uuid, "[OFF]", reject));
            assertEquals(raw, store.findByName("Alice"));
            assertNull(store.findByName("[OFF]Alice"));
            var mapped = store.resolveOffline("Alice", uuid, "[OFF]", profile -> {});
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("Alice", uuid, "[NEW]", reject));
            assertEquals(mapped, store.findOfflineByAccountName("Alice"));
        }
    }

    @Test void failedOfflineRenameKeepsOriginalLedgerRowsAndCanRetry() throws Exception {
        Path file = directory.resolve("offline-write-failure.db");
        UUID uuid = UUID.randomUUID();
        try (IdentityStore store = new IdentityStore(file)) {
            var raw = store.reserveLegacy("Alice", uuid, "OFFLINE");
            try (var other = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = other.createStatement()) {
                statement.execute("CREATE TRIGGER fail_rename BEFORE UPDATE ON profile BEGIN SELECT RAISE(ABORT, 'test write failure'); END");
            }
            assertThrows(java.sql.SQLException.class, () -> store.resolveOffline("Alice", uuid, "[OFF]", profile -> {}));
            assertEquals(raw, store.findByName("Alice"));
            assertNull(store.findByName("[OFF]Alice"));
            assertFalse(store.hasOfflineAliases());
            try (var other = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = other.createStatement()) {
                statement.execute("DROP TRIGGER fail_rename");
            }
            assertEquals(raw.id(), store.resolveOffline("Alice", uuid, "[OFF]", profile -> {}).profile().id());
        }
    }

    @Test void invalidOfflineNamesCannotCreateOrRenameProfiles() throws Exception {
        UUID uuid = UUID.randomUUID();
        try (IdentityStore store = new IdentityStore(directory.resolve("offline-invalid.db"))) {
            assertThrows(IllegalArgumentException.class, () -> store.resolveOffline("Alice", uuid, "[离线]", profile -> {}));
            assertThrows(IllegalArgumentException.class, () -> store.resolveOffline("abcdefghijklmnop", uuid, "[OFF]", profile -> {}));
            assertNull(store.findByUuid(uuid));
            var raw = store.reserveLegacy("abcdefghijklmnop", uuid, "OFFLINE");
            assertThrows(IllegalArgumentException.class, () -> store.resolveOffline("abcdefghijklmnop", uuid, "[OFF]", profile -> {}));
            assertEquals(raw, store.findByUuid(uuid));
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
