package xyz.nikitacartes.easyauth.sources;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import xyz.nikitacartes.easyauth.EasyAuth;
import xyz.nikitacartes.easyauth.config.ExtendedConfigV1;
import xyz.nikitacartes.easyauth.config.ProxyConfigV1;
import xyz.nikitacartes.easyauth.integrations.SourceNameDisplay;
import xyz.nikitacartes.easyauth.storage.PlayerEntryV1;
import xyz.nikitacartes.easyauth.utils.PlayersCache;
import xyz.nikitacartes.easyauth.utils.StoneCutterUtils;
import xyz.nikitacartes.easyauth.skins.ServerSkinBridge;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static xyz.nikitacartes.easyauth.EasyAuth.*;
import static xyz.nikitacartes.easyauth.utils.EasyLogger.*;

/** Bridges provider evidence into the unchanged name-keyed EasyAuth account backend. */
public final class MultiAuth {
    private static volatile SourcesConfig current;
    private static IdentityStore identities;
    private static SourceAuthenticator authenticator;
    private static volatile ServerSkinBridge skinBridge;
    private static final ConnectionProofs<Connection> PROOFS = new ConnectionProofs<>();
    // Slow external providers cannot consume the password lane or spawn unbounded coordinators.
    private static final ThreadPoolExecutor PASSWORD_LOGINS = loginLane(8, "password");
    private static final ThreadPoolExecutor PROVIDER_LOGINS = loginLane(64, "provider");

    private MultiAuth() {}

    private static ThreadPoolExecutor loginLane(int maximum, String label) {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(0, maximum, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "EasyAuth-login-" + label + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public static void submitLogin(Runnable task, boolean password) {
        try {
            (password ? PASSWORD_LOGINS : PROVIDER_LOGINS).execute(task);
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("Authentication is busy; please reconnect in a moment", e);
        }
    }

    public static Path configPath() {
        return gameDirectory.resolve("config/EasyAuth/sources.json");
    }

    public static SourcesConfig readConfig() {
        try {
            return SourcesConfig.load(configPath());
        } catch (Exception e) {
            throw new IllegalStateException("Invalid EasyAuth sources.json: " + e.getMessage(), e);
        }
    }

    public static void validate(SourcesConfig candidate, ExtendedConfigV1 extended) {
        validate(candidate, extended, proxyConfig);
    }

    public static void validate(SourcesConfig candidate, ExtendedConfigV1 extended, ProxyConfigV1 proxy) {
        //? if < 1.20.3 || >= 1.20.5 {
        if (!candidate.usesLegacyAuthentication()) {
            throw new IllegalArgumentException("Configured authentication sources are supported by the Minecraft 1.20.4 Fabric artifact only");
        }
        //?}
        if (!candidate.usesLegacyAuthentication() && extended.forcedOfflineUuid) {
            throw new IllegalArgumentException("sources.json: configured sources cannot be combined with forced-offline-uuid; official UUIDs must remain raw");
        }
        if (!candidate.usesLegacyAuthentication() && proxy != null && proxy.enabled) {
            throw new IllegalArgumentException("sources.json: configured sources require direct Fabric login; disable the proxy bridge");
        }
    }

    /** Validate fully before publishing the immutable snapshot. The ledger outlives source disable. */
    public static synchronized void install(SourcesConfig candidate) {
        validate(candidate, extendedConfig);
        try {
            if (identities == null) {
                identities = new IdentityStore(gameDirectory.resolve("config/EasyAuth/identities.sqlite"));
                authenticator = new SourceAuthenticator();
            }
            current = candidate;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot open EasyAuth identity ledger", e);
        }
    }

    public static SourcesConfig snapshot() {
        return current;
    }

    public static boolean enabled() {
        SourcesConfig config = current;
        return config != null && !config.usesLegacyAuthentication();
    }

    public static boolean preventAutomaticMigration() {
        try {
            return enabled() || (identities != null && identities.hasCustomIdentities());
        } catch (Exception e) {
            // An unreadable ledger must never cause UUID reassignment or migration.
            throw new IllegalStateException("Cannot check identity ledger", e);
        }
    }

    public static boolean protectedName(String name) {
        try {
            return identities != null && identities.isProtectedName(name);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot check protected identity", e);
        }
    }

    public static boolean protectedUuid(UUID uuid) {
        try {
            return identities != null && identities.isProtectedUuid(uuid);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot check protected UUID", e);
        }
    }

    /** Namespace matching only prevents unverified local claims; it never proves authentication. */
    public static boolean reservedCustomName(String name) {
        try {
            SourcesConfig snapshot = current;
            return (snapshot != null && snapshot.isReservedCustomName(name))
                    || (identities != null && identities.isReservedCustomName(name));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot check reserved identity namespaces", e);
        }
    }

    /** Only exempts a server-allocated name from the legacy username-format regex. */
    public static boolean isReservedCustomProfileName(String name, UUID uuid) {
        try {
            if (identities == null || !identities.isProtectedName(name)) return false;
            IdentityStore.LocalProfile profile = identities.findByName(name);
            return profile != null && profile.name().equals(name) && profile.uuid().equals(uuid)
                    && ExternalProfileNames.localName("", name).equals(name);
        } catch (IllegalArgumentException e) {
            return false;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot validate allocated custom profile name", e);
        }
    }

    public static void onServerStart(MinecraftServer server) {
        validateServer(current, server);
        try {
            skinBridge = ServerSkinBridge.open(server, gameDirectory.resolve("config/EasyAuth"));
            if (skinBridge != null) LogInfo("[skin-bridge] " + skinBridge.status());
        } catch (Exception cosmeticConfiguration) {
            // Cosmetic configuration must not prevent an otherwise valid login/server start.
            LogWarn("[skin-bridge] Could not start skin bridge; account authentication is unchanged. Check skin-bridge.json and mineskin.key.");
        }
    }

    public static void validateServer(SourcesConfig candidate, MinecraftServer server) {
        if (candidate != null && !candidate.usesLegacyAuthentication() && !server.usesAuthentication()) {
            throw new IllegalStateException("EasyAuth configured sources require online-mode=true so clients perform the Yggdrasil join handshake");
        }
        if (candidate != null && candidate.hasCustomSources() && Boolean.parseBoolean(serverProp.getProperty("enforce-secure-profile", "false"))) {
            throw new IllegalStateException("EasyAuth custom sources require enforce-secure-profile=false; third-party players do not have Mojang chat certificates");
        }
    }

    public static synchronized void close() {
        try {
            PASSWORD_LOGINS.shutdownNow();
            PROVIDER_LOGINS.shutdownNow();
            if (skinBridge != null) skinBridge.close();
            if (authenticator != null) authenticator.close();
            if (identities != null) identities.close();
        } catch (Exception e) {
            LogError("Failed to close authentication sources", e);
        }
    }

    public static int reload(CommandSourceStack source) {
        try {
            SourcesConfig candidate = readConfig();
            validate(candidate, extendedConfig);
            validateServer(candidate, source.getServer());
            install(candidate);
            SourceNameDisplay.refresh(source.getServer());
            source.sendSystemMessage(Component.literal("[EasyAuth] Authentication sources reloaded."));
            return 1;
        } catch (Exception e) {
            LogError("Authentication source reload rejected; previous snapshot remains active", e);
            source.sendFailure(Component.literal("[EasyAuth] Source reload rejected: " + e.getMessage()));
            return 0;
        }
    }

    public static int reloadSkins(CommandSourceStack source) {
        try {
            ServerSkinBridge replacement = ServerSkinBridge.open(source.getServer(), gameDirectory.resolve("config/EasyAuth"));
            if (replacement == null) {
                source.sendFailure(Component.literal("[EasyAuth] Skin bridging is supported by the Fabric 1.20.4 build."));
                return 0;
            }
            ServerSkinBridge previous = skinBridge;
            skinBridge = replacement;
            if (previous != null) previous.close();
            source.sendSystemMessage(Component.literal("[EasyAuth] Skin bridge reloaded: " + replacement.status() + ". Changes apply on login."));
            return 1;
        } catch (Exception cosmeticConfiguration) {
            source.sendFailure(Component.literal("[EasyAuth] Skin reload rejected; previous configuration kept. Check skin-bridge.json and mineskin.key."));
            return 0;
        }
    }

    /** Hello UUID is a routing hint only. It never grants authentication. */
    public static boolean shouldUsePasswordPath(SourcesConfig snapshot, String name, UUID claimedUuid) {
        if (protectedName(name) || snapshot.isReservedCustomName(name) || reservedCustomName(name)) return false;
        PlayerEntryV1 existing = DB.getUserData(name);
        if (existing != null && existing.onlineAccount == PlayerEntryV1.OnlineAccount.TRUE) return false;
        if (snapshot.isExplicitOfflineName(name)) return true;
        if (snapshot.hasCustomSources() && claimedUuid != null
                && !claimedUuid.equals(UUIDUtil.createOfflinePlayerUUID(name))
                && (existing == null || existing.forcedUuid == null
                || !existing.forcedUuid.equalsIgnoreCase(claimedUuid.toString()))) {
            // A routing hint is not authentication: the source must still verify this session.
            return false;
        }
        if (existing != null && (existing.onlineAccount == PlayerEntryV1.OnlineAccount.FALSE || !existing.password.isEmpty())) return true;
        if (snapshot.newPlayerAuth() == SourcesConfig.NewPlayerAuth.EXTERNAL) return false;
        return claimedUuid == null || claimedUuid.equals(UUIDUtil.createOfflinePlayerUUID(name));
    }

    public static GameProfile authenticate(MinecraftServer server, Connection connection, SourcesConfig snapshot,
                                           String username, String serverDigest) throws Exception {
        try {
            return authenticateAsync(server, connection, snapshot, username, serverDigest).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Exception cause) throw cause;
            throw new IllegalStateException("Authentication could not complete", failed);
        }
    }

    /** Provider verification runs in its existing lane; cosmetic waiting releases that lane. */
    public static CompletableFuture<GameProfile> authenticateAsync(MinecraftServer server, Connection connection,
                                           SourcesConfig snapshot, String username, String serverDigest) throws Exception {
        long started = System.nanoTime();
        ServerSkinBridge bridge = skinBridge;
        InetAddress address = null;
        if (server.getPreventProxyConnections() && connection.getRemoteAddress() instanceof InetSocketAddress socket) {
            address = socket.getAddress();
        }
        Optional<AuthenticatedIdentity> result = authenticator.authenticate(snapshot, username, serverDigest, address);
        if (result.isEmpty()) return CompletableFuture.completedFuture(preparePassword(server, username));
        AuthenticatedIdentity identity = result.get();
        VerifiedProfile verified = prepareVerified(server, identity, snapshot);
        SourcesConfig.Source source = snapshot.sources().stream()
                .filter(candidate -> candidate.id().equals(identity.sourceId())).findFirst().orElse(null);
        CompletableFuture<GameProfile> decorated = bridge == null ? CompletableFuture.completedFuture(verified.profile())
                : bridge.prepare(identity, source, verified.profile(), started);
        return decorated.handle((profile, cosmeticFailure) -> {
            GameProfile accepted = cosmeticFailure == null && profile != null ? profile : verified.profile();
            PROOFS.put(connection, new ConnectionProofs.Proof(StoneCutterUtils.getId(accepted),
                    StoneCutterUtils.getName(accepted), identity.type() != SourceType.OFFICIAL,
                    snapshot.relaxCustomChatKeys(), identity.sourceId(), identity.type(), verified.localNamePrefix()));
            return accepted;
        });
    }

    private record VerifiedProfile(GameProfile profile, String localNamePrefix) {}

    private static synchronized VerifiedProfile prepareVerified(MinecraftServer server, AuthenticatedIdentity identity,
                                                                 SourcesConfig snapshot) throws Exception {
        requireDatabase();
        IdentityStore.LocalProfile local;
        try {
            LegacyIdentityGuard legacyGuard = new LegacyIdentityGuard(() -> DB, List.of(
                    server.getWorldPath(LevelResource.PLAYER_DATA_DIR),
                    server.getWorldPath(LevelResource.PLAYER_STATS_DIR),
                    server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR)));
            local = identities.resolve(identity, snapshot.namePrefix(identity.sourceId()), (candidateIdentity, candidate, existing) -> {
                // Check the actual candidate inside the transaction, before either ledger row is
                // committed. Existing mappings keep their original names even if config changes.
                if (!existing && candidateIdentity.type() != SourceType.OFFICIAL) {
                    String owner = snapshot.reservedNameSource(candidate.name());
                    if (owner != null && !owner.equals(candidateIdentity.sourceId())) {
                        throw new IdentityConflictException("SOURCE_NAMESPACE_COLLISION", "This name namespace belongs to another authentication source");
                    }
                }
                legacyGuard.check(candidateIdentity, candidate, existing);
            });
        } catch (IdentityConflictException conflict) {
            LogWarn("[auth-source] identity rejected source=" + identity.sourceId() + " subject=" + identity.subjectId()
                    + " provider_uuid=" + identity.providerUuid() + " code=" + conflict.code());
            throw conflict;
        }
        PlayerEntryV1 entry = DB.getUserData(local.name());
        if (entry == null) {
            entry = new PlayerEntryV1(local.name(), local.uuid());
            entry.onlineAccount = identity.type() == SourceType.OFFICIAL
                    ? PlayerEntryV1.OnlineAccount.TRUE : PlayerEntryV1.OnlineAccount.FALSE;
            DB.registerUser(entry);
        } else if (entry.uuid == null) {
            entry.uuid = local.uuid();
            if (!DB.updateUserData(entry)) throw new IllegalStateException("Could not persist authenticated profile");
        }
        PlayerEntryV1 persisted = DB.getUserData(local.name());
        if (persisted == null || !local.uuid().equals(effectiveUuid(persisted))) {
            throw new IllegalStateException("Authenticated profile could not be persisted; login refused");
        }
        if (identity.type() != SourceType.OFFICIAL && persisted.onlineAccount == PlayerEntryV1.OnlineAccount.TRUE) {
            throw new IllegalStateException("External profile is incorrectly marked as Mojang premium; login refused");
        }
        PlayersCache.put(local.name(), persisted);
        GameProfile profile = new GameProfile(local.uuid(), local.name());
        //? if < 1.21.9 {
        /*for (AuthenticatedIdentity.PropertyData property : identity.properties()) {
            profile.getProperties().put(property.name(), property.signature() == null
                    ? new Property(property.name(), property.value())
                    : new Property(property.name(), property.value(), property.signature()));
        }
        *///?}
        LogInfo("[auth-source] accepted source=" + identity.sourceId() + " subject=" + identity.subjectId()
                + " profile=" + local.id() + " provider_uuid=" + identity.providerUuid() + " local_uuid=" + local.uuid()
                + " uuid_policy=" + (identity.providerUuid().equals(local.uuid()) ? "raw" : "isolated_v7"));
        return new VerifiedProfile(profile, local.namePrefix());
    }

    /** Password fallback keeps the effective legacy UUID and must never claim a source identity. */
    public static GameProfile preparePassword(MinecraftServer server, String username) throws Exception {
        requireDatabase();
        if (reservedCustomName(username)) throw new IllegalStateException("This name namespace requires verified source authentication");
        PlayerEntryV1 existing = DB.getUserData(username);
        if (protectedName(username)) throw new IllegalStateException("This name belongs to an external identity; sign in using its authentication source");
        if (existing != null && existing.onlineAccount == PlayerEntryV1.OnlineAccount.TRUE) {
            throw new IllegalStateException("Mojang authentication failed for a protected premium account");
        }
        if (server.usesAuthentication() && extendedConfig.preventOfflinePlayersWithOnlineUsernames
                && (existing == null || existing.onlineAccount != PlayerEntryV1.OnlineAccount.FALSE)) {
            UUID onlineUuid = xyz.nikitacartes.easyauth.integrations.MojangApi.getUuid(username);
            if (onlineUuid != null) throw new IllegalStateException("This username requires Mojang authentication");
        }
        return reservePassword(username);
    }

    private static synchronized GameProfile reservePassword(String username) throws Exception {
        requireDatabase();
        if (reservedCustomName(username)) throw new IllegalStateException("This name namespace cannot be registered as a local password account");
        PlayerEntryV1 entry = DB.getUserData(username);
        if (entry != null && entry.onlineAccount == PlayerEntryV1.OnlineAccount.TRUE) {
            throw new IllegalStateException("Premium account cannot fall back to password login");
        }
        UUID uuid = UUIDUtil.createOfflinePlayerUUID(username);
        if (entry != null && entry.forcedUuid != null && !entry.forcedUuid.isEmpty()) uuid = UUID.fromString(entry.forcedUuid);
        if (entry != null && effectiveUuid(entry) != null && !effectiveUuid(entry).equals(uuid)) {
            throw new IllegalStateException("Stored UUID differs from password UUID; automatic migration is disabled");
        }
        for (PlayerEntryV1 other : DB.getAllDataStrict().values()) {
            if (!other.username.equalsIgnoreCase(username)
                    && (uuid.equals(other.uuid) || uuid.equals(effectiveUuid(other)))) {
                throw new IllegalStateException("Another legacy account reserves the password UUID");
            }
        }
        identities.assertOfflineAllowed(username, uuid);
        identities.reserveLegacy(username, uuid, "OFFLINE");
        if (entry == null) {
            entry = new PlayerEntryV1(username, uuid);
            if (config.offlineByDefault) entry.onlineAccount = PlayerEntryV1.OnlineAccount.FALSE;
            DB.registerUser(entry);
        } else if (entry.uuid == null) {
            entry.uuid = uuid;
            if (!DB.updateUserData(entry)) throw new IllegalStateException("Could not persist password profile");
        }
        PlayerEntryV1 persisted = DB.getUserData(username);
        if (persisted == null || !uuid.equals(effectiveUuid(persisted))) throw new IllegalStateException("Password profile could not be persisted");
        PlayersCache.put(username, persisted);
        return new GameProfile(uuid, username);
    }

    public static void beforeVerification(Connection connection, GameProfile profile) {
        UUID uuid = StoneCutterUtils.getId(profile);
        String name = StoneCutterUtils.getName(profile);
        if (PROOFS.get(connection, uuid, name) != null) return;
        if (protectedName(name) || protectedUuid(uuid)) {
            throw new IllegalStateException("This external identity requires a freshly verified source session");
        }
    }

    public static ConnectionProofs.Proof consumeProof(Connection connection, GameProfile profile) {
        return PROOFS.consume(connection, StoneCutterUtils.getId(profile), StoneCutterUtils.getName(profile));
    }

    public static void forget(Connection connection) {
        PROOFS.remove(connection);
    }

    public static boolean rejectIdentityEdit(CommandSourceStack source, String name) {
        try {
            if (identities == null || identities.findByName(name) == null) return false;
            source.sendFailure(Component.literal("[EasyAuth] This account is reserved by the identity ledger. Legacy rename, UUID, premium and removal commands cannot change its identity."));
        } catch (Exception e) {
            source.sendFailure(Component.literal("[EasyAuth] Identity ledger unavailable; change refused."));
            LogError("Identity edit refused", e);
        }
        return true;
    }

    public static boolean rejectUuidEdit(CommandSourceStack source, UUID uuid) {
        try {
            if (identities == null || identities.findByUuid(uuid) == null) return false;
            source.sendFailure(Component.literal("[EasyAuth] UUID is reserved by the identity ledger; change refused."));
        } catch (Exception e) {
            source.sendFailure(Component.literal("[EasyAuth] Identity ledger unavailable; change refused."));
            LogError("Identity UUID edit refused", e);
        }
        return true;
    }

    /** Updating a legacy account's password does not change its identity. */
    public static boolean rejectExternalRegistration(CommandSourceStack source, String name) {
        try {
            if (!protectedName(name) && !reservedCustomName(name)) return false;
            source.sendFailure(Component.literal("[EasyAuth] External identities authenticate with their source and cannot be registered as local accounts."));
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal("[EasyAuth] Identity ledger unavailable; change refused."));
            LogError("External registration refused", e);
        }
        return true;
    }

    private static void requireDatabase() {
        if (DB == null || DB.isClosed()) throw new IllegalStateException("EasyAuth account database is unavailable");
    }

    private static UUID effectiveUuid(PlayerEntryV1 entry) {
        return entry.forcedUuid != null && !entry.forcedUuid.isEmpty() ? UUID.fromString(entry.forcedUuid) : entry.uuid;
    }
}
