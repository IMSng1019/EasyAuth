package xyz.nikitacartes.easyauth.sources;

//? if >= 1.21.5 {
import net.fabricmc.fabric.api.gametest.v1.GameTest;
//?} else {
/*import net.minecraft.gametest.framework.GameTest;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
*///?}
import net.minecraft.gametest.framework.GameTestHelper;

//? if >= 1.20.3 && < 1.20.5 {
/*import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.sun.net.httpserver.HttpServer;
import io.netty.buffer.Unpooled;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.util.Crypt;
import xyz.nikitacartes.easyauth.EasyAuth;
import xyz.nikitacartes.easyauth.event.AuthEventHandler;
import xyz.nikitacartes.easyauth.integrations.MojangApi;
import xyz.nikitacartes.easyauth.interfaces.PlayerAuth;
import xyz.nikitacartes.easyauth.storage.PlayerEntryV1;
import xyz.nikitacartes.easyauth.utils.PlayersCache;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
*///?}

//? if >= 1.21.5 {
public class OfflineLoginRuntimeTest {
    @GameTest
//?} else {
/*public class OfflineLoginRuntimeTest implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE)
*///?}
    public void actualLoginRoutesPublishBoundOfflineNamesAndKeepPasswordGates(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*try (Fixture fixture = new Fixture(context)) {
            String suffix = UUID.randomUUID().toString().substring(0, 6);
            String rawOffline = "Off" + suffix;
            fixture.seed(rawOffline, null);
            fixture.server.setUsesAuthentication(false);
            fixture.install(false);
            check(!MultiAuth.enabled(), "Offline fixture must use the upstream legacy login route");
            CaptureConnection offline = fixture.connection();
            ServerLoginPacketListenerImpl offlineListener = new ServerLoginPacketListenerImpl(fixture.server, offline);
            offlineListener.handleHello(new ServerboundHelloPacket(rawOffline, UUIDUtil.createOfflinePlayerUUID(rawOffline)));
            check(offline.challenge == null, "Offline-mode hello unexpectedly started an online handshake");
            // A reload between Hello and verification must not change this connection's
            // captured allocation policy. No earlier identity may mask this regression.
            fixture.snapshotField.set(null, SourcesConfig.defaults());
            check(!MultiAuth.offlineNamesEnabled(), "Snapshot regression unexpectedly has an existing offline mapping");
            GameProfile offlineProfile = fixture.finish(offlineListener, offline, rawOffline, UUIDUtil.createOfflinePlayerUUID(rawOffline));
            fixture.assertPasswordGate(offline, offlineProfile, rawOffline);

            fixture.install(false);
            fixture.server.setUsesAuthentication(true);
            String rawCached = "Old" + suffix;
            UUID forced = UUID.randomUUID();
            fixture.seed(rawCached, forced);
            CaptureConnection cached = fixture.connection();
            ServerLoginPacketListenerImpl cachedListener = new ServerLoginPacketListenerImpl(fixture.server, cached);
            cachedListener.handleHello(new ServerboundHelloPacket(rawCached, UUIDUtil.createOfflinePlayerUUID(rawCached)));
            check(cached.challenge == null, "A cached offline account entered the premium handshake");
            GameProfile cachedProfile = fixture.finish(cachedListener, cached, rawCached, forced);
            check(fixture.requests.get() == 0, "Legacy offline routes called an authentication provider");
            fixture.assertPasswordGate(cached, cachedProfile, rawCached);

            fixture.ensureKeys();
            fixture.setAccountStatus(rawCached, PlayerEntryV1.OnlineAccount.UNKNOWN);
            UUID claimedPremiumUuid = UUID.randomUUID();
            fixture.cachePremiumUuid(rawCached, claimedPremiumUuid);
            CaptureConnection unknown = fixture.connection();
            ServerLoginPacketListenerImpl unknownListener = new ServerLoginPacketListenerImpl(fixture.server, unknown);
            unknownListener.handleHello(new ServerboundHelloPacket(rawCached, claimedPremiumUuid));
            check(unknown.challenge == null && EasyAuth.DB.getUserData(rawCached).onlineAccount == PlayerEntryV1.OnlineAccount.UNKNOWN,
                    "A mapped offline account was promoted to premium from the client's claimed UUID");
            GameProfile unknownProfile = fixture.finish(unknownListener, unknown, rawCached, forced);
            fixture.assertPasswordGate(unknown, unknownProfile, rawCached);
            expectRejected(() -> MultiAuth.beforeVerification(fixture.connection(), new GameProfile(claimedPremiumUuid, rawCached)),
                    "Raw account name plus an unrelated UUID bypassed the mapped offline identity");

            fixture.setAccountStatus(rawCached, PlayerEntryV1.OnlineAccount.TRUE);
            CaptureConnection incompatible = fixture.connection();
            ServerLoginPacketListenerImpl incompatibleListener = new ServerLoginPacketListenerImpl(fixture.server, incompatible);
            incompatibleListener.handleHello(new ServerboundHelloPacket(rawCached, claimedPremiumUuid));
            incompatibleListener.tick();
            check(!incompatible.isConnected() && incompatible.loginProfile == null
                            && EasyAuth.DB.getUserData(rawCached).onlineAccount == PlayerEntryV1.OnlineAccount.TRUE,
                    "An inconsistent premium flag on a mapped offline account was silently downgraded or admitted");

            fixture.install(true);
            String rawManaged = "Pwd" + suffix;
            fixture.seed(rawManaged, null);
            CaptureConnection managed = fixture.connection();
            ServerLoginPacketListenerImpl managedListener = new ServerLoginPacketListenerImpl(fixture.server, managed);
            managedListener.handleHello(new ServerboundHelloPacket(rawManaged, UUIDUtil.createOfflinePlayerUUID(rawManaged)));
            GameProfile managedProfile = fixture.finish(managedListener, managed, rawManaged, UUIDUtil.createOfflinePlayerUUID(rawManaged));
            check(managed.challenge == null && fixture.requests.get() == 0,
                    "Configured direct password route unnecessarily queried an external source");
            fixture.assertPasswordGate(managed, managedProfile, rawManaged);

            String rawFallback = "Back" + suffix;
            fixture.seed(rawFallback, null);
            fixture.ensureKeys();
            CaptureConnection fallback = fixture.connection();
            ServerLoginPacketListenerImpl fallbackListener = new ServerLoginPacketListenerImpl(fixture.server, fallback);
            fallbackListener.handleHello(new ServerboundHelloPacket(rawFallback, UUID.randomUUID()));
            check(fallback.challenge != null, "Provider fallback fixture did not perform the real RSA handshake");
            fallbackListener.handleKey(new ServerboundKeyPacket(Crypt.generateSecretKey(), fallback.challenge.getPublicKey(),
                    fallback.challenge.getChallenge()));
            GameProfile fallbackProfile = fixture.finish(fallbackListener, fallback, rawFallback, UUIDUtil.createOfflinePlayerUUID(rawFallback));
            check(fallback.encrypted && fixture.requests.get() == 1,
                    "Provider fallback did not query the fixture once through the encrypted handshake");
            fixture.assertPasswordGate(fallback, fallbackProfile, rawFallback);

            fixture.install(false);
            fixture.server.setUsesAuthentication(false);
            for (String forbidden : List.of("[OFF]New" + suffix, cachedProfile.getName())) {
                var before = EasyAuth.DB.getAllDataStrict();
                CaptureConnection attempt = fixture.connection();
                ServerLoginPacketListenerImpl listener = new ServerLoginPacketListenerImpl(fixture.server, attempt);
                listener.handleHello(new ServerboundHelloPacket(forbidden, UUIDUtil.createOfflinePlayerUUID(forbidden)));
                check(!attempt.isConnected() && attempt.loginProfile == null,
                        "Client-supplied allocated offline name was admitted");
                var after = EasyAuth.DB.getAllDataStrict();
                check(after.keySet().equals(before.keySet()) && !after.containsKey(forbidden.toLowerCase(Locale.ROOT)),
                        "Rejecting a forged local name created a legacy account row");
            }

            // The consumed proof must not survive loadPlayerData; a fresh pending login is
            // also cleared by the vanilla login-listener disconnect event.
            CaptureConnection pending = fixture.connection();
            ServerLoginPacketListenerImpl pendingListener = new ServerLoginPacketListenerImpl(fixture.server, pending);
            pendingListener.handleHello(new ServerboundHelloPacket(rawOffline, UUIDUtil.createOfflinePlayerUUID(rawOffline)));
            GameProfile pendingProfile = fixture.finish(pendingListener, pending, rawOffline, offlineProfile.getId());
            check(MultiAuth.hasOfflineProof(pending, pendingProfile), "Fixture failed to publish a pending mapping proof");
            pendingListener.onDisconnect(Component.literal("Fixture disconnected before admission"));
            check(!MultiAuth.hasOfflineProof(pending, pendingProfile), "Login disconnect retained an offline mapping proof");
            expectRejected(() -> MultiAuth.beforeVerification(pending, pendingProfile), "Disconnected mapping proof remained usable");
        }
        *///?}
        context.succeed();
    }

    //? if >= 1.20.3 && < 1.20.5 {
    /*private static final class Fixture implements AutoCloseable {
        final GameTestHelper context;
        final MinecraftServer server;
        final Field snapshotField;
        final Object previousSnapshot;
        final Field ledgerField;
        final Object previousLedger;
        final boolean previousOnlineMode;
        final boolean previousSkipAllAuth;
        final Path configFile;
        final Path ledgerFile;
        final IdentityStore ledger;
        final HttpServer http;
        final AtomicInteger requests = new AtomicInteger();
        final List<CaptureConnection> connections = new ArrayList<>();
        final Map<String, UUID> previousPremiumUuids = new HashMap<>();

        Fixture(GameTestHelper context) throws Exception {
            this.context = context;
            server = context.getLevel().getServer();
            snapshotField = MultiAuth.class.getDeclaredField("current");
            snapshotField.setAccessible(true);
            previousSnapshot = snapshotField.get(null);
            ledgerField = MultiAuth.class.getDeclaredField("identities");
            ledgerField.setAccessible(true);
            previousLedger = ledgerField.get(null);
            previousOnlineMode = server.usesAuthentication();
            previousSkipAllAuth = EasyAuth.extendedConfig.skipAllAuthChecks;
            EasyAuth.extendedConfig.skipAllAuthChecks = false;
            configFile = Files.createTempFile("easyauth-offline-login-", ".json");
            ledgerFile = Files.createTempFile("easyauth-offline-login-", ".sqlite");
            ledger = new IdentityStore(ledgerFile);
            ledgerField.set(null, ledger);
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/joined", exchange -> {
                requests.incrementAndGet();
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            http.start();
        }

        void install(boolean managed) throws Exception {
            JsonObject json = new JsonObject();
            json.addProperty("configVersion", 1);
            json.addProperty("offlineNamePrefix", "[OFF] ");
            JsonObject source = new JsonObject();
            source.addProperty("id", managed ? "offline-login-fixture" : "official");
            source.addProperty("type", managed ? "SKIN_SITE" : "OFFICIAL");
            source.addProperty("timeoutMillis", 2000);
            if (managed) {
                json.addProperty("newPlayerAuth", "EXTERNAL");
                source.addProperty("hasJoinedUrl", "http://127.0.0.1:" + http.getAddress().getPort() + "/joined");
            }
            JsonArray sources = new JsonArray();
            sources.add(source);
            json.add("sources", sources);
            Files.writeString(configFile, json.toString());
            snapshotField.set(null, SourcesConfig.load(configFile));
        }

        void seed(String name, UUID forced) {
            PlayerEntryV1 entry = new PlayerEntryV1(name, UUIDUtil.createOfflinePlayerUUID(name));
            entry.onlineAccount = PlayerEntryV1.OnlineAccount.FALSE;
            entry.forcedUuid = forced == null ? null : forced.toString();
            entry.password = "fixture-existing-password";
            EasyAuth.DB.registerUser(entry);
        }

        void setAccountStatus(String name, PlayerEntryV1.OnlineAccount status) {
            PlayerEntryV1 entry = EasyAuth.DB.getUserData(name);
            entry.onlineAccount = status;
            check(EasyAuth.DB.updateUserData(entry), "Could not update fixture account status");
            PlayersCache.loadOrRegister(name);
        }

        void ensureKeys() throws Exception {
            if (server.getKeyPair() == null) {
                var initializeKeys = MinecraftServer.class.getDeclaredMethod("initializeKeyPair");
                initializeKeys.setAccessible(true);
                initializeKeys.invoke(server);
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, UUID> premiumUuids() throws Exception {
            var field = MojangApi.class.getDeclaredField("UUID_CACHE");
            field.setAccessible(true);
            return (Map<String, UUID>) field.get(null);
        }

        void cachePremiumUuid(String name, UUID uuid) throws Exception {
            String key = EasyAuth.extendedConfig.profileLookup.url + "|" + name.toLowerCase(Locale.ENGLISH);
            previousPremiumUuids.put(key, premiumUuids().put(key, uuid));
        }

        CaptureConnection connection() {
            CaptureConnection connection = new CaptureConnection();
            connections.add(connection);
            return connection;
        }

        GameProfile finish(ServerLoginPacketListenerImpl listener, CaptureConnection connection,
                           String rawName, UUID expectedUuid) throws Exception {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (connection.loginProfile == null && connection.isConnected() && System.nanoTime() < deadline) {
                listener.tick();
                if (connection.loginProfile == null) Thread.sleep(2);
            }
            check(connection.isConnected(), "Login was rejected: " + connection.disconnected);
            check(connection.loginProfile != null, "Real login listener never sent its login-success profile");
            check(listener.authenticatedProfile != null && listener.authenticatedProfile.getName().equals("[OFF]" + rawName),
                    "Login verification lost the captured offline prefix or failed to update authenticatedProfile");
            check(connection.loginProfile.equals(listener.authenticatedProfile)
                            && connection.loginProfile.getId().equals(expectedUuid),
                    "Serialized login-success profile disagrees with the listener name or preserved UUID");
            return connection.loginProfile;
        }

        void assertPasswordGate(CaptureConnection connection, GameProfile profile, String rawName) {
            check(MultiAuth.hasOfflineProof(connection, profile), "Password route did not publish a connection-bound name mapping");
            expectRejected(() -> MultiAuth.beforeVerification(connection(), profile), "Mapping proof crossed login connections");
            ServerPlayer player = new ServerPlayer(server, context.getLevel(), profile, ClientInformation.createDefault());
            player.connection = new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile));
            AuthEventHandler.loadPlayerData(player, connection);
            PlayerAuth auth = (PlayerAuth) player;
            check(!auth.easyAuth$isAuthenticated() && !auth.easyAuth$canSkipAuth() && !auth.easyAuth$isExternalAuthenticated()
                            && !auth.easyAuth$relaxSourceChatKeys() && auth.easyAuth$getAuthenticationChannel() == AuthenticationChannel.OFFLINE,
                    "Actual login route promoted an offline name mapping into authentication");
            check(auth.easyAuth$getPlayerEntryV1().username.equals(rawName)
                            && auth.easyAuth$getPlayerEntryV1().password.equals("fixture-existing-password"),
                    "Actual login route replaced the original password account");
            check(!MultiAuth.hasOfflineProof(connection, profile), "Loading a player did not consume the mapping proof");
            expectRejected(() -> MultiAuth.beforeVerification(connection, profile), "Consumed mapping proof could be replayed");
        }

        @Override public void close() throws Exception {
            for (CaptureConnection connection : connections) MultiAuth.forget(connection);
            var premiumUuids = premiumUuids();
            previousPremiumUuids.forEach((key, previous) -> {
                if (previous == null) premiumUuids.remove(key);
                else premiumUuids.put(key, previous);
            });
            server.setUsesAuthentication(previousOnlineMode);
            EasyAuth.extendedConfig.skipAllAuthChecks = previousSkipAllAuth;
            snapshotField.set(null, previousSnapshot);
            ledgerField.set(null, previousLedger);
            http.stop(0);
            ledger.close();
            Files.deleteIfExists(configFile);
            Files.deleteIfExists(ledgerFile);
        }
    }

    private static final class CaptureConnection extends Connection {
        volatile String disconnected;
        volatile GameProfile loginProfile;
        ClientboundHelloPacket challenge;
        boolean encrypted;
        CaptureConnection() { super(PacketFlow.SERVERBOUND); }
        @Override public void setListener(PacketListener listener) {}
        @Override public boolean isConnected() { return disconnected == null; }
        @Override public boolean isMemoryConnection() { return false; }
        @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 25565); }
        @Override public void disconnect(Component reason) { disconnected = reason.getString(); }
        @Override public void setEncryptionKey(Cipher decrypt, Cipher encrypt) { encrypted = true; }
        @Override public void send(Packet<?> packet) {
            if (packet instanceof ClientboundHelloPacket hello) challenge = hello;
            if (packet instanceof ClientboundGameProfilePacket success) {
                FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    success.write(buffer);
                    loginProfile = new ClientboundGameProfilePacket(buffer).getGameProfile();
                    check(!buffer.isReadable(), "Login-success packet left unread bytes");
                } finally {
                    buffer.release();
                }
            }
        }
        @Override public void send(Packet<?> packet, PacketSendListener listener) { send(packet); }
        @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) { send(packet); }
    }

    private static void expectRejected(Runnable action, String message) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    *///?}
}
