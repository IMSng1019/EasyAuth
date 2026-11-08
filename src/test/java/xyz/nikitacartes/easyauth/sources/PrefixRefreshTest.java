package xyz.nikitacartes.easyauth.sources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PrefixRefreshTest {
    @TempDir Path directory;
    private static final IdentityStore.LegacyGuard AVAILABLE = (identity, profile, existing) -> {};

    private AuthenticatedIdentity external(SourceType type, String name) {
        return new AuthenticatedIdentity("source", type, "subject",
                UUID.fromString("019c1111-2222-7333-8444-555555555555"), name, List.of());
    }

    @Test void externalPrefixRefreshPreservesIdentityAcrossRestartClearAndReapply() throws Exception {
        for (SourceType type : List.of(SourceType.UNION, SourceType.SKIN_SITE)) {
            Path file = directory.resolve(type + ".sqlite");
            IdentityStore.LocalProfile first;
            try (var store = new IdentityStore(file)) {
                first = store.resolve(external(type, "Alice"), "[OLD]", AVAILABLE);
            }
            try (var store = new IdentityStore(file)) {
                var renamed = store.resolve(external(type, "UpstreamRename"), "NEW_", AVAILABLE);
                assertEquals(new IdentityStore.LocalProfile(first.id(), "NEW_Alice", first.uuid(), "NEW_"), renamed);
                assertEquals("[OLD]Alice", store.accountName(renamed));
                assertEquals(renamed, store.findNameOwner("[old]ALICE"));
                assertNull(store.findByName("[OLD]Alice"));
                assertTrue(store.isProtectedName("[OLD]Alice"));
                assertEquals("source", store.reservedNameSource("[OLD]Other"));
                assertEquals(renamed, store.resolve(external(type, "AnotherName"), "NEW_", AVAILABLE));
                var cleared = store.resolve(external(type, "AnotherName"), "", AVAILABLE);
                assertEquals(new IdentityStore.LocalProfile(first.id(), "Alice", first.uuid(), ""), cleared);
                assertEquals(first, store.resolve(external(type, "AnotherName"), "[OLD]", AVAILABLE));
            }
        }
    }

    @Test void legacyGuardAllowsOnlyTheLedgerBoundAccountAndRejectsTargetEvenAtSameUuid() throws Exception {
        try (var store = new IdentityStore(directory.resolve("legacy.sqlite"))) {
            var identity = external(SourceType.UNION, "Alice");
            var first = store.resolve(identity, "[OLD]", AVAILABLE);
            var accounts = new java.util.ArrayList<LegacyIdentityPolicy.Account>();
            accounts.add(new LegacyIdentityPolicy.Account(first.name(), first.uuid(), null, false, true));
            var policy = new LegacyIdentityPolicy(() -> accounts, List.of(directory));
            IdentityStore.LegacyGuard guard = (proof, candidate, existing) ->
                    policy.check(proof, candidate, existing, store.accountName(candidate));
            var renamed = store.resolve(identity, "NEW_", guard);
            assertEquals("NEW_Alice", renamed.name());
            // A duplicate legacy record at the target cannot be silently merged, even at the same UUID.
            accounts.add(new LegacyIdentityPolicy.Account("OTHER_Alice", first.uuid(), null, false, false));
            assertThrows(IdentityConflictException.class, () -> store.resolve(identity, "OTHER_", guard));
            assertEquals(renamed, store.findByUuid(first.uuid()));
            accounts.remove(1);
            accounts.set(0, new LegacyIdentityPolicy.Account(first.name(), UUID.randomUUID(), null, false, true));
            assertThrows(IdentityConflictException.class, () -> store.resolve(identity, "OTHER_", guard));
        }
    }

    @Test void historicalNamesCannotBeClaimedByAnyOtherIdentity() throws Exception {
        try (var store = new IdentityStore(directory.resolve("history.sqlite"))) {
            var identity = external(SourceType.UNION, "Alice");
            var first = store.resolve(identity, "[OLD]", AVAILABLE);
            store.resolve(identity, "", AVAILABLE);
            var renamed = store.resolve(identity, "NEW_", AVAILABLE);
            assertEquals(first.id(), renamed.id());
            UUID official = UUID.randomUUID();
            assertThrows(IdentityConflictException.class, () -> store.resolve(new AuthenticatedIdentity(
                    "official", SourceType.OFFICIAL, official.toString(), official, "Alice", List.of()), AVAILABLE));
            assertThrows(IdentityConflictException.class, () -> store.reserveLegacy("Alice", UUID.randomUUID(), "OFFLINE"));
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("Alice", UUID.randomUUID(), "OFF_", profile -> {}));
            assertEquals(renamed, store.findNameOwner("Alice"));
            assertTrue(store.isProtectedName("Alice"));
        }
    }

    @Test void schemaThreeUpgradeRetainsExternalAndOfflineBackendKeys() throws Exception {
        Path file = directory.resolve("schema-three.sqlite");
        UUID externalUuid = UUID.randomUUID();
        UUID offlineUuid = UUID.randomUUID();
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE profile(id TEXT PRIMARY KEY, name TEXT UNIQUE, name_key TEXT UNIQUE, uuid TEXT UNIQUE, name_prefix TEXT NOT NULL DEFAULT '')");
            sql.execute("CREATE TABLE source_identity(source_id TEXT, subject_id TEXT, source_type TEXT, provider_uuid TEXT, provider_name TEXT, profile_id TEXT, PRIMARY KEY(source_id,subject_id))");
            sql.execute("INSERT INTO profile VALUES('external','[OLD]Alice','[old]alice','" + externalUuid + "','[OLD]')");
            sql.execute("INSERT INTO source_identity VALUES('source','subject','UNION','" + externalUuid + "','RenamedUpstream','external')");
            sql.execute("INSERT INTO profile VALUES('offline','[OFF]Bob','[off]bob','" + offlineUuid + "','[OFF]')");
            sql.execute("INSERT INTO source_identity VALUES('__easyauth_offline','" + offlineUuid + "','OFFLINE','" + offlineUuid + "','Bob','offline')");
            sql.execute("PRAGMA user_version = 3");
        }
        try (var store = new IdentityStore(file)) {
            var external = store.resolve(external(SourceType.UNION, "RenamedUpstream"), "NEW_", AVAILABLE);
            var offline = store.resolveOffline("Bob", offlineUuid, "LOCAL_", profile -> {});
            assertEquals("NEW_Alice", external.name());
            assertEquals(externalUuid, external.uuid());
            assertEquals("[OLD]Alice", store.accountName(external));
            assertEquals("LOCAL_Bob", offline.profile().name());
            assertEquals("Bob", store.accountName(offline.profile()));
        }
        try (var store = new IdentityStore(file)) {
            assertEquals("[OLD]Alice", store.accountName(store.findNameOwner("NEW_Alice")));
            assertEquals("Bob", store.accountName(store.findNameOwner("[OFF]Bob")));
        }
    }

    @Test void clearingOfflinePrefixRetainsManagedIdentityAndAliasesAfterRestart() throws Exception {
        Path file = directory.resolve("cleared.sqlite");
        UUID uuid = UUID.randomUUID();
        try (var store = new IdentityStore(file)) {
            store.resolveOffline("Alice", uuid, "[OFF]", candidate -> {});
            store.resolveOffline("Alice", uuid, "", candidate -> {});
        }
        try (var store = new IdentityStore(file)) {
            assertTrue(store.hasOfflineAliases(), "Clearing a prefix must not re-enable automatic UUID migration");
            var current = store.findOfflineByAccountName("Alice");
            assertEquals(current, store.findOfflineByLocalName("Alice"));
            assertEquals(current, store.findOfflineByLocalName("[OFF]Alice"));
            assertEquals("Alice", store.accountName(current.profile()));
        }
    }

    @Test void failedRefreshLeavesNamesAndIdentityAvailableForRetry() throws Exception {
        try (var store = new IdentityStore(directory.resolve("rollback.sqlite"))) {
            var identity = external(SourceType.UNION, "Alice");
            var original = store.resolve(identity, "[OLD]", AVAILABLE);
            assertThrows(IllegalStateException.class, () -> store.resolve(identity, "NEW_", (proof, candidate, existing) -> {
                assertTrue(existing);
                assertEquals("NEW_Alice", candidate.name());
                throw new IllegalStateException("backend unavailable");
            }));
            assertEquals(original, store.findByUuid(original.uuid()));
            assertNull(store.findByName("NEW_Alice"));
            assertNull(store.reservedNameSource("NEW_Other"));
            assertEquals("NEW_Alice", store.resolve(identity, "NEW_", AVAILABLE).name());
        }
    }

    @Test void newPrefixCannotClaimAnExistingNameOrAnotherSourcesNamespace() throws Exception {
        try (var store = new IdentityStore(directory.resolve("collision.sqlite"))) {
            var identity = external(SourceType.UNION, "Alice");
            var original = store.resolve(identity, "[OLD]", AVAILABLE);
            var occupied = store.reserveLegacy("NEW_Alice", UUID.randomUUID(), "OFFLINE");
            assertThrows(IdentityConflictException.class, () -> store.resolve(identity, "NEW_", AVAILABLE));
            assertEquals(occupied, store.findByName("NEW_Alice"));
            store.resolveOffline("Bob", UUID.randomUUID(), "OFF_", profile -> {});
            assertThrows(IdentityConflictException.class, () -> store.resolve(identity, "OFF_", AVAILABLE));
            assertThrows(IllegalArgumentException.class, () -> store.resolve(identity, "123456789012", AVAILABLE));
            assertEquals(original, store.findByUuid(original.uuid()));
        }
    }

    @Test void offlineRefreshChecksCollisionAndRollsBackBeforeChangingCredentialsAlias() throws Exception {
        try (var store = new IdentityStore(directory.resolve("offline.sqlite"))) {
            UUID uuid = UUID.randomUUID();
            var first = store.resolveOffline("Alice", uuid, "[OFF]", profile -> {});
            store.reserveLegacy("NEW_Alice", UUID.randomUUID(), "FLOODGATE");
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("Alice", uuid, "NEW_", profile -> {}));
            assertThrows(IllegalStateException.class, () -> store.resolveOffline("Alice", uuid, "OK_", profile -> {
                assertEquals("OK_Alice", profile.name());
                throw new IllegalStateException("backend unavailable");
            }));
            assertEquals(first, store.findOfflineByAccountName("Alice"));
            var updated = store.resolveOffline("Alice", uuid, "OK_", profile -> {});
            assertEquals("OK_Alice", updated.profile().name());
            assertEquals(first.profile().id(), updated.profile().id());
            assertEquals(uuid, updated.profile().uuid());
            assertEquals("Alice", updated.accountName());
            assertEquals(updated, store.findOfflineByLocalName("[OFF]Alice"));
            assertThrows(IdentityConflictException.class, () -> store.resolveOffline("[OFF]Alice", uuid, "OK_", profile -> {}));
        }
    }
}
