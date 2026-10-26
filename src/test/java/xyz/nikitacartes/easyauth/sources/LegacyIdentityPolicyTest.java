package xyz.nikitacartes.easyauth.sources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LegacyIdentityPolicyTest {
    @TempDir Path directory;

    private static AuthenticatedIdentity custom(String name, UUID providerUuid) {
        return new AuthenticatedIdentity("skin", SourceType.SKIN_SITE, "stable", providerUuid, name, List.of());
    }

    private static IdentityStore.LocalProfile profile(String name, UUID uuid) {
        return new IdentityStore.LocalProfile("test-profile", name, uuid);
    }

    private LegacyIdentityPolicy policy(LegacyIdentityPolicy.Account... accounts) {
        return new LegacyIdentityPolicy(() -> List.of(accounts), List.of(directory));
    }

    @Test void existingPasswordAccountReservesCaseInsensitiveNameWithoutMutation() {
        UUID legacyUuid = UUID.nameUUIDFromBytes("OfflinePlayer:Alice".getBytes(StandardCharsets.UTF_8));
        UUID nativeUuid = UuidV7.generate();
        var account = new LegacyIdentityPolicy.Account("Alice", legacyUuid, null, false, true);
        assertThrows(IdentityConflictException.class,
                () -> policy(account).check(custom("ALICE", nativeUuid), profile("ALICE", nativeUuid), false));
        assertEquals(legacyUuid, account.uuid());
    }

    @Test void anotherAccountsForcedUuidIsReserved() {
        UUID nativeUuid = UuidV7.generate();
        var account = new LegacyIdentityPolicy.Account("Existing", UUID.randomUUID(), nativeUuid, false, true);
        assertThrows(IdentityConflictException.class,
                () -> policy(account).check(custom("New", nativeUuid), profile("New", nativeUuid), false));
    }

    @Test void customProviderCannotReturnLegacyOfficialUuidEvenIfLocalUuidWouldDiffer() {
        UUID official = UUID.randomUUID();
        var account = new LegacyIdentityPolicy.Account("Official", official, null, true, false);
        IdentityConflictException rejection = assertThrows(IdentityConflictException.class,
                () -> policy(account).check(custom("New", official), profile("New", UuidV7.generate()), false));
        assertEquals("OFFICIAL_UUID_COLLISION", rejection.code());
    }

    @Test void verifiedOfficialIdentityCanReuseMatchingPremiumRowButNotOfflineRow() {
        UUID official = UUID.randomUUID();
        var proof = new AuthenticatedIdentity("official", SourceType.OFFICIAL, official.toString(), official, "Alice", List.of());
        policy(new LegacyIdentityPolicy.Account("Alice", official, null, true, false)).check(proof, profile("Alice", official), false);
        assertThrows(IdentityConflictException.class,
                () -> policy(new LegacyIdentityPolicy.Account("Alice", official, null, false, true)).check(proof, profile("Alice", official), false));
    }

    @Test void existingIdentityMayReuseItsOwnLegacyRowButRejectsChangedUuid() {
        UUID local = UuidV7.generate();
        var proof = custom("Alice", local);
        policy(new LegacyIdentityPolicy.Account("Alice", local, null, false, false)).check(proof, profile("Alice", local), true);
        assertThrows(IdentityConflictException.class,
                () -> policy(new LegacyIdentityPolicy.Account("Alice", UUID.randomUUID(), null, false, false)).check(proof, profile("Alice", local), true));
    }

    @Test void nativeUuidCannotClaimExistingWorldFilesAndFilesRemainUnchanged() throws Exception {
        UUID local = UuidV7.generate();
        Path worldFile = directory.resolve(local + ".dat");
        Files.writeString(worldFile, "existing world data");
        assertThrows(IdentityConflictException.class,
                () -> policy().check(custom("Alice", local), profile("Alice", local), false));
        assertEquals("existing world data", Files.readString(worldFile));
        policy().check(custom("Alice", local), profile("Alice", local), true);
    }

    @Test void incompleteLegacySnapshotCannotCreateAnIdentity() throws Exception {
        var unavailable = new LegacyIdentityPolicy(() -> { throw new IllegalStateException("database unavailable"); }, List.of(directory));
        try (var store = new IdentityStore(directory.resolve("identities.db"))) {
            assertThrows(IllegalStateException.class, () -> store.resolve(custom("Alice", UuidV7.generate()), unavailable));
            assertNull(store.findByName("Alice"));
        }
    }
}
