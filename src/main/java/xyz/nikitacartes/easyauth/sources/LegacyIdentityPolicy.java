package xyz.nikitacartes.easyauth.sources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Pure legacy reservation policy, kept independent of Minecraft and database adapters. */
public final class LegacyIdentityPolicy implements IdentityStore.LegacyGuard {
    public record Account(String name, UUID uuid, UUID forcedUuid, boolean official, boolean hasPassword) {}
    private final Supplier<List<Account>> accounts;
    private final List<Path> worldDataDirectories;

    public LegacyIdentityPolicy(Supplier<List<Account>> accounts, List<Path> worldDataDirectories) {
        this.accounts = Objects.requireNonNull(accounts);
        this.worldDataDirectories = List.copyOf(worldDataDirectories);
    }

    @Override public void check(AuthenticatedIdentity identity, IdentityStore.LocalProfile profile, boolean existingIdentity) {
        for (Account account : accounts.get()) {
            UUID effective = account.forcedUuid() != null ? account.forcedUuid() : account.uuid();
            if (identity.type() != SourceType.OFFICIAL && account.official()
                    && (identity.providerUuid().equals(account.uuid()) || identity.providerUuid().equals(account.forcedUuid()))) {
                throw conflict("OFFICIAL_UUID_COLLISION", "Custom source returned a UUID reserved by an existing official account");
            }

            boolean sameName = account.name().equalsIgnoreCase(profile.name());
            boolean sameUuid = profile.uuid().equals(account.uuid()) || profile.uuid().equals(account.forcedUuid());
            if (!sameName && !sameUuid) continue;

            // Reuse is authorized by the persisted source identity, never just name/UUID equality.
            if (existingIdentity && account.name().equals(profile.name()) && profile.uuid().equals(effective)) continue;

            boolean pristinePremiumPlaceholder = account.uuid() == null && account.forcedUuid() == null && !account.hasPassword();
            if (identity.type() == SourceType.OFFICIAL && sameName && account.name().equals(profile.name())
                    && account.official() && (profile.uuid().equals(effective) || pristinePremiumPlaceholder)) continue;

            throw conflict(sameName ? "LEGACY_NAME_COLLISION" : "LEGACY_UUID_COLLISION",
                    "An existing EasyAuth account reserves this " + (sameName ? "player name" : "UUID")
                            + "; explicit identity binding is required");
        }

        // A returning identity owns its files. Official proof establishes raw UUID ownership;
        // a new custom identity may not claim any existing world data at its requested UUID.
        if (!existingIdentity && identity.type() != SourceType.OFFICIAL) {
            for (Path directory : worldDataDirectories) {
                for (String suffix : List.of(".dat", ".dat_old", ".json")) {
                    Path file = directory.resolve(profile.uuid() + suffix);
                    if (Files.exists(file)) {
                        throw conflict("WORLD_UUID_COLLISION", "World data already exists for the requested UUID; refusing to reuse it");
                    }
                    if (!Files.notExists(file)) {
                        throw new IllegalStateException("Cannot check existing world UUID data: " + file);
                    }
                }
            }
        }
    }

    private static IdentityConflictException conflict(String code, String message) {
        return new IdentityConflictException(code, message);
    }
}
