package xyz.nikitacartes.easyauth.sources;

//? if >= 1.21.5 {
import net.fabricmc.fabric.api.gametest.v1.GameTest;
//?} else {
/*import net.minecraft.gametest.framework.GameTest;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
*///?}
import com.mojang.authlib.GameProfile;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
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
import xyz.nikitacartes.easyauth.interfaces.PlayerAuth;
import xyz.nikitacartes.easyauth.storage.PlayerEntryV1;
import xyz.nikitacartes.easyauth.utils.StoneCutterUtils;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URLDecoder;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;

/** Uses the real HTTP parser, identity ledger and configured legacy backend inside Fabric. */
//? if >= 1.21.5 {
public class MultiAuthRuntimeTest {
@GameTest
//?} else {
/*public class MultiAuthRuntimeTest implements FabricGameTest {
@GameTest(template = EMPTY_STRUCTURE)
*///?}
    public void unionRuntimeIsolationAndRawPasswords(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String name = "Union" + suffix;
        String internalId = "fixture-" + suffix;
        AtomicReference<UUID> providerUuid = new AtomicReference<>(UUIDUtil.createOfflinePlayerUUID(name));
        AtomicReference<String> expectedDigest = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/joined", exchange -> {
            requests.incrementAndGet();
            String expected = expectedDigest.get();
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            if (expected != null && !query.contains("serverId=" + expected)) {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            String body = "{\"id\":\"" + providerUuid.get().toString().replace("-", "") + "\",\"name\":\"" + name
                    + "\",\"properties\":[{\"name\":\"textures\",\"value\":\"fixture-texture\",\"signature\":\"fixture-signature\"}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        fixture.createContext("/mapped/", exchange -> {
            String body = "{\"uuid\":\"" + providerUuid.get() + "\",\"internal_id\":\"" + internalId + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        fixture.start();
        SourcesConfig before = MultiAuth.snapshot();
        MinecraftServer server = context.getLevel().getServer();
        boolean oldOnlineMode = server.usesAuthentication();
        Path configFile = Files.createTempFile("easyauth-runtime-", ".json");
        try {
            int port = fixture.getAddress().getPort();
            Files.writeString(configFile, "{\"configVersion\":1,\"newPlayerAuth\":\"EXTERNAL\",\"relaxCustomChatKeys\":true,\"sources\":[{"
                    + "\"id\":\"gametest-union\",\"type\":\"UNION\",\"priority\":1,"
                    + "\"hasJoinedUrl\":\"http://127.0.0.1:" + port + "/joined\","
                    + "\"mappedUrl\":\"http://127.0.0.1:" + port + "/mapped/{uuid}\"}]}");
            SourcesConfig sources = SourcesConfig.load(configFile);
            MultiAuth.install(sources);
            // GameTestServer does not initialize its RSA key pair because it normally has no
            // socket clients. Initialize it for this real login-listener handshake test.
            if (server.getKeyPair() == null) {
                var initializeKeys = MinecraftServer.class.getDeclaredMethod("initializeKeyPair");
                initializeKeys.setAccessible(true);
                initializeKeys.invoke(server);
            }
            server.setUsesAuthentication(true);

            TestConnection invalidConnection = new TestConnection();
            ServerLoginPacketListenerImpl invalidListener = new ServerLoginPacketListenerImpl(server, invalidConnection);
            invalidListener.handleHello(new ServerboundHelloPacket(name, providerUuid.get()));
            check(invalidConnection.challenge != null, "Vanilla encryption challenge was not sent");
            byte[] corruptChallenge = invalidConnection.challenge.getChallenge().clone();
            corruptChallenge[0] ^= 1;
            expectRejected(() -> invalidListener.handleKey(new ServerboundKeyPacket(Crypt.generateSecretKey(),
                    invalidConnection.challenge.getPublicKey(), corruptChallenge)), "Invalid encryption challenge was accepted");
            check(!invalidConnection.encrypted && requests.get() == 0, "Invalid encryption response reached a provider");

            TestConnection authenticated = new TestConnection();
            ServerLoginPacketListenerImpl listener = new ServerLoginPacketListenerImpl(server, authenticated);
            listener.handleHello(new ServerboundHelloPacket(name, providerUuid.get()));
            check(authenticated.challenge != null, "Custom source did not use the vanilla challenge");
            SecretKey secret;
            String digest;
            do {
                secret = Crypt.generateSecretKey();
                digest = new BigInteger(Crypt.digestData(authenticated.challenge.getServerId(),
                        authenticated.challenge.getPublicKey(), secret)).toString(16);
            } while (!digest.startsWith("-"));
            expectedDigest.set(digest);
            listener.handleKey(new ServerboundKeyPacket(secret, authenticated.challenge.getPublicKey(),
                    authenticated.challenge.getChallenge()));
            GameProfile profile = awaitProfile(listener, authenticated);
            check(authenticated.encrypted && requests.get() == 1, "Handshake did not enable encryption and validate the exact digest once");
            expectedDigest.set(null);
            UUID localUuid = StoneCutterUtils.getId(profile);
            check(localUuid.version() == 7 && !localUuid.equals(providerUuid.get()), "Union did not receive a local UUIDv7");
            PlayerEntryV1 entry = EasyAuth.DB.getUserData(name);
            check(entry != null && entry.password.isEmpty() && localUuid.equals(entry.uuid), "Union did not automatically create a passwordless local profile");
            check(entry.onlineAccount != PlayerEntryV1.OnlineAccount.TRUE, "Union was marked as Mojang premium");
            check(profile.getProperties().get("textures").iterator().next().value().equals("fixture-texture"), "Profile textures were not preserved");
            MultiAuth.beforeVerification(authenticated, profile);
            expectRejected(() -> MultiAuth.beforeVerification(new Connection(PacketFlow.SERVERBOUND), profile), "Another connection reused authenticated identity");
            ServerPlayer player = new ServerPlayer(server, context.getLevel(), profile, ClientInformation.createDefault());
            player.connection = new ServerGamePacketListenerImpl(server, authenticated, player, CommonListenerCookie.createInitial(profile));
            AuthEventHandler.loadPlayerData(player, authenticated);
            PlayerAuth playerAuth = (PlayerAuth) player;
            check(playerAuth.easyAuth$isAuthenticated() && playerAuth.easyAuth$canSkipAuth(), "First Union login required local registration");
            check(playerAuth.easyAuth$isExternalAuthenticated() && !playerAuth.easyAuth$isUsingMojangAccount(), "External authentication was confused with Mojang trust");
            check(playerAuth.easyAuth$relaxSourceChatKeys(), "Custom chat-key policy did not follow the verified connection");
            check(MultiAuth.consumeProof(authenticated, profile) == null, "Connection proof was replayed");

            providerUuid.set(UUID.randomUUID());
            Connection remapped = new Connection(PacketFlow.SERVERBOUND);
            GameProfile second = MultiAuth.authenticate(context.getLevel().getServer(), remapped, sources, name, "-abcd1234");
            check(localUuid.equals(StoneCutterUtils.getId(second)), "Union remap changed local UUID");
            MultiAuth.forget(remapped);
            MultiAuth.install(before);
            expectRejected(() -> MultiAuth.preparePassword(context.getLevel().getServer(), name), "Disabled source identity fell back to password login");

            String offlineName = "Local" + suffix;
            GameProfile offline = MultiAuth.preparePassword(context.getLevel().getServer(), offlineName);
            check(StoneCutterUtils.getId(offline).equals(UUIDUtil.createOfflinePlayerUUID(offlineName)), "Password fallback changed native offline UUID");

            String forcedName = "Force" + suffix;
            UUID oldRaw = UUIDUtil.createOfflinePlayerUUID(forcedName);
            UUID forced = UUID.randomUUID();
            PlayerEntryV1 forcedEntry = new PlayerEntryV1(forcedName, oldRaw);
            forcedEntry.onlineAccount = PlayerEntryV1.OnlineAccount.FALSE;
            forcedEntry.forcedUuid = forced.toString();
            EasyAuth.DB.registerUser(forcedEntry);
            GameProfile forcedProfile = MultiAuth.preparePassword(context.getLevel().getServer(), forcedName);
            check(StoneCutterUtils.getId(forcedProfile).equals(forced), "Legacy forced UUID changed");
            check(EasyAuth.DB.getUserData(forcedName).uuid.equals(oldRaw), "Historical legacy UUID column was rewritten");
        } finally {
            MultiAuth.install(before);
            server.setUsesAuthentication(oldOnlineMode);
            fixture.stop(0);
            Files.deleteIfExists(configFile);
        }
        *///?}
        context.succeed();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    //? if >= 1.20.3 && < 1.20.5 {
    /*private static GameProfile awaitProfile(ServerLoginPacketListenerImpl listener, TestConnection connection) throws Exception {
        // Reading the vanilla volatile state establishes visibility of authenticatedProfile.
        var state = ServerLoginPacketListenerImpl.class.getDeclaredField("state");
        state.setAccessible(true);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!state.get(listener).toString().equals("VERIFYING") && connection.disconnected == null && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        check(connection.disconnected == null, "Handshake disconnected: " + connection.disconnected);
        check(listener.authenticatedProfile != null, "Authentication worker did not produce a profile");
        return listener.authenticatedProfile;
    }

    // The test replaces packet transport only. Vanilla RSA challenge verification and AES cipher
    // initialization, our mixin selection, HTTP verification, ledger and account persistence run.
    private static final class TestConnection extends Connection {
        private ClientboundHelloPacket challenge;
        private boolean encrypted;
        private volatile String disconnected;

        TestConnection() { super(PacketFlow.SERVERBOUND); }

        @Override public void send(Packet<?> packet) {
            if (packet instanceof ClientboundHelloPacket hello) challenge = hello;
        }

        @Override public boolean isMemoryConnection() { return false; }
        @Override public void setListener(PacketListener listener) { }
        @Override public boolean isConnected() { return disconnected == null; }
        @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 25565); }
        @Override public void disconnect(Component reason) { disconnected = reason.getString(); }

        @Override public void setEncryptionKey(Cipher decrypt, Cipher encrypt) {
            byte[] plaintext = "easyauth-handshake".getBytes(StandardCharsets.UTF_8);
            check(Arrays.equals(plaintext, decrypt.update(encrypt.update(plaintext))), "Vanilla AES ciphers did not round-trip");
            encrypted = true;
        }
    }
    *///?}

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }

    private static void expectRejected(CheckedAction action, String message) throws Exception {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
