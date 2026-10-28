package xyz.nikitacartes.easyauth.skins;

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
import com.mojang.authlib.properties.Property;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.util.Crypt;
import xyz.nikitacartes.easyauth.EasyAuth;
import xyz.nikitacartes.easyauth.sources.IdentityStore;
import xyz.nikitacartes.easyauth.sources.MultiAuth;
import xyz.nikitacartes.easyauth.sources.SourceType;
import xyz.nikitacartes.easyauth.sources.SourcesConfig;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
*///?}

//? if >= 1.21.5 {
public class SkinBridgeLoginRuntimeTest {
    @GameTest
//?} else {
/*public class SkinBridgeLoginRuntimeTest implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE)
*///?}
    public void verifiedExternalLoginReleasesCoordinatorBeforeSkinCompletion(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*for (SourceType type : List.of(SourceType.UNION, SourceType.SKIN_SITE)) {
            try (Fixture fixture = new Fixture(context, type)) {
                TestConnection connection = fixture.connection();
                CompletableFuture<CompletableFuture<GameProfile>> submitted = new CompletableFuture<>();
                CountDownLatch coordinatorReturned = new CountDownLatch(1);
                MultiAuth.submitLogin(() -> {
                    try {
                        submitted.complete(MultiAuth.authenticateAsync(fixture.server, connection, fixture.sources,
                                fixture.rawName, fixture.expectedDigest.get()));
                    } catch (Throwable failure) {
                        submitted.completeExceptionally(failure);
                    } finally {
                        coordinatorReturned.countDown();
                    }
                }, false);

                check(fixture.generationStarted.await(3, TimeUnit.SECONDS), "Verified " + type + " login did not reach the skin generator");
                check(coordinatorReturned.await(1, TimeUnit.SECONDS), "Skin waiting held the provider login coordinator");
                CompletableFuture<GameProfile> pending = submitted.get(1, TimeUnit.SECONDS);
                check(!pending.isDone(), "Login finished before the blocked first skin conversion");
                fixture.releaseGeneration.countDown();
                GameProfile result = pending.get(3, TimeUnit.SECONDS);
                check(texture(result).equals(fixture.converted), "Production authenticateAsync did not install converted " + type + " textures");
                check(result.getName().equals(fixture.localName) && result.getId().version() == 7
                                && !result.getId().equals(fixture.providerUuid), "Skin completion changed or bypassed mapped identity");
                check(result.getProperties().get("other").iterator().next().value().equals("preserved"), "Skin completion discarded another provider property");
                check(EasyAuth.DB.getUserData(fixture.localName).uuid.equals(result.getId()), "Skin completion diverged from the authenticated account UUID");
                MultiAuth.beforeVerification(connection, result);
                TestConnection other = fixture.connection();
                expectRejected(() -> MultiAuth.beforeVerification(other, result), "A different connection reused the decorated login proof");
                var proof = MultiAuth.consumeProof(connection, result);
                check(proof != null && proof.custom() && proof.sourceType() == type
                                && proof.sourceId().equals(fixture.sourceId), "Decorated profile lost the verified provider proof");
                check(MultiAuth.consumeProof(connection, result) == null, "Decorated login proof was reusable");
            }
        }
        *///?}
        context.succeed();
    }

    //? if >= 1.21.5 {
    @GameTest
    //?} else {
    /*@GameTest(template = EMPTY_STRUCTURE)
    *///?}
    public void disconnectedLoginCannotPublishLateConvertedProfile(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*try (Fixture fixture = new Fixture(context, SourceType.SKIN_SITE)) {
            if (fixture.server.getKeyPair() == null) {
                var initialize = MinecraftServer.class.getDeclaredMethod("initializeKeyPair");
                initialize.setAccessible(true);
                initialize.invoke(fixture.server);
            }
            TestConnection connection = fixture.connection();
            ServerLoginPacketListenerImpl listener = new ServerLoginPacketListenerImpl(fixture.server, connection);
            listener.handleHello(new ServerboundHelloPacket(fixture.rawName, fixture.providerUuid));
            check(connection.challenge != null, "Real skin login did not send the vanilla RSA challenge");
            var secret = Crypt.generateSecretKey();
            fixture.expectedDigest.set(new BigInteger(Crypt.digestData(connection.challenge.getServerId(),
                    connection.challenge.getPublicKey(), secret)).toString(16));
            listener.handleKey(new ServerboundKeyPacket(secret, connection.challenge.getPublicKey(), connection.challenge.getChallenge()));
            check(fixture.generationStarted.await(3, TimeUnit.SECONDS), "Real login listener did not wait for the skin conversion");
            check(connection.encrypted && listener.authenticatedProfile == null, "Login published a profile before skin selection completed");
            GameProfile probe = new GameProfile(EasyAuth.DB.getUserData(fixture.localName).uuid, fixture.localName);
            Component reason = Component.literal("Fixture disconnected during skin conversion");
            connection.disconnect(reason);
            listener.onDisconnect(reason);
            fixture.releaseGeneration.countDown();
            check(connection.disconnectedCheck.await(3, TimeUnit.SECONDS), "Late skin callback never checked its original disconnected connection");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            boolean proofRemoved = false;
            while (System.nanoTime() < deadline) {
                try {
                    MultiAuth.beforeVerification(connection, probe);
                } catch (IllegalStateException expected) {
                    proofRemoved = true;
                    break;
                }
                Thread.sleep(5);
            }
            check(proofRemoved, "Disconnected login retained a late authentication proof");
            check(listener.authenticatedProfile == null, "Late converted profile was published to a disconnected listener");
            expectRejected(() -> MultiAuth.beforeVerification(fixture.connection(), probe), "Late result authenticated a new connection");
        }
        *///?}
        context.succeed();
    }

    //? if >= 1.20.3 && < 1.20.5 {
    /*private static final class Fixture implements AutoCloseable {
        final MinecraftServer server;
        final String rawName = "Skin" + UUID.randomUUID().toString().substring(0, 6);
        final UUID providerUuid = UUIDUtil.createOfflinePlayerUUID(rawName);
        final String localName;
        final String sourceId;
        final SourcesConfig sources;
        final SourcesConfig previousSources = MultiAuth.snapshot();
        final String previousSecureProfile = EasyAuth.serverProp.getProperty("enforce-secure-profile");
        final boolean previousOnlineMode;
        final Field ledgerField = MultiAuth.class.getDeclaredField("identities");
        final Field bridgeField = MultiAuth.class.getDeclaredField("skinBridge");
        final Object previousLedger;
        final Object previousBridge;
        final IdentityStore ledger;
        final HttpServer http;
        final ServerSkinBridge bridge;
        final List<TestConnection> connections = new ArrayList<>();
        final CountDownLatch generationStarted = new CountDownLatch(1);
        final CountDownLatch releaseGeneration = new CountDownLatch(1);
        final AtomicReference<String> expectedDigest = new AtomicReference<>("-abcd1234");
        final TextureProperty converted = image("https://textures.minecraft.net/texture/" + "e".repeat(64), "fixture-signed");

        Fixture(GameTestHelper context, SourceType type) throws Exception {
            server = context.getLevel().getServer();
            previousOnlineMode = server.usesAuthentication();
            String prefix = type == SourceType.UNION ? "[MUA]" : "[S]";
            localName = prefix + rawName;
            sourceId = "skin-login-" + type.name().toLowerCase(java.util.Locale.ROOT);
            var directory = Files.createTempDirectory("easyauth-skin-login-");
            ledgerField.setAccessible(true);
            bridgeField.setAccessible(true);
            previousLedger = ledgerField.get(null);
            previousBridge = bridgeField.get(null);
            ledger = new IdentityStore(directory.resolve("identities.sqlite"));
            var config = SkinBridgeConfig.defaults();
            var engine = new SkinBridgeEngine(config, directory.resolve("cache.json"), descriptor -> {
                generationStarted.countDown();
                if (!releaseGeneration.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture skin generation was not released");
                return converted;
            }, converted::equals);
            bridge = new ServerSkinBridge(config, engine, converted::equals);
            TextureProperty input = image("https://skins.example.org/" + rawName + ".png", "provider-signature");
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/joined", exchange -> {
                String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                if (!query.contains("username=" + rawName) || !query.contains("serverId=" + expectedDigest.get())) {
                    exchange.sendResponseHeaders(403, -1);
                    exchange.close();
                    return;
                }
                JsonObject response = new JsonObject();
                response.addProperty("id", providerUuid.toString());
                response.addProperty("name", rawName);
                JsonArray properties = new JsonArray();
                JsonObject texture = new JsonObject();
                texture.addProperty("name", "textures");
                texture.addProperty("value", input.value());
                texture.addProperty("signature", input.signature());
                properties.add(texture);
                JsonObject other = new JsonObject();
                other.addProperty("name", "other");
                other.addProperty("value", "preserved");
                properties.add(other);
                response.add("properties", properties);
                byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            http.createContext("/mapped/", exchange -> {
                byte[] body = ("{\"uuid\":\"" + providerUuid + "\",\"internal_id\":\"skin-login-" + rawName + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            http.start();
            String endpoint = "http://127.0.0.1:" + http.getAddress().getPort();
            JsonObject source = new JsonObject();
            source.addProperty("id", sourceId);
            source.addProperty("type", type.name());
            source.addProperty("priority", 1);
            source.addProperty("namePrefix", prefix);
            source.addProperty("hasJoinedUrl", endpoint + "/joined");
            if (type == SourceType.UNION) source.addProperty("mappedUrl", endpoint + "/mapped/{uuid}");
            JsonArray sourceList = new JsonArray();
            sourceList.add(source);
            JsonObject root = new JsonObject();
            root.addProperty("configVersion", 1);
            root.addProperty("newPlayerAuth", "EXTERNAL");
            root.addProperty("relaxCustomChatKeys", true);
            root.add("sources", sourceList);
            var configFile = directory.resolve("sources.json");
            Files.writeString(configFile, root.toString());
            sources = SourcesConfig.load(configFile);
            ledgerField.set(null, ledger);
            bridgeField.set(null, bridge);
            EasyAuth.serverProp.setProperty("enforce-secure-profile", "false");
            server.setUsesAuthentication(true);
            MultiAuth.install(sources);
        }

        TestConnection connection() {
            TestConnection connection = new TestConnection();
            connections.add(connection);
            return connection;
        }

        @Override public void close() throws Exception {
            releaseGeneration.countDown();
            bridge.close();
            for (TestConnection connection : connections) MultiAuth.forget(connection);
            bridgeField.set(null, previousBridge);
            ledgerField.set(null, previousLedger);
            MultiAuth.install(previousSources);
            server.setUsesAuthentication(previousOnlineMode);
            if (previousSecureProfile == null) EasyAuth.serverProp.remove("enforce-secure-profile");
            else EasyAuth.serverProp.setProperty("enforce-secure-profile", previousSecureProfile);
            http.stop(0);
            ledger.close();
        }
    }

    private static final class TestConnection extends Connection {
        volatile boolean connected = true;
        volatile boolean encrypted;
        ClientboundHelloPacket challenge;
        final CountDownLatch disconnectedCheck = new CountDownLatch(1);

        TestConnection() { super(PacketFlow.SERVERBOUND); }
        @Override public void setListener(PacketListener listener) {}
        @Override public boolean isConnected() {
            if (!connected) disconnectedCheck.countDown();
            return connected;
        }
        @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 25565); }
        @Override public void send(Packet<?> packet) { if (packet instanceof ClientboundHelloPacket hello) challenge = hello; }
        @Override public void send(Packet<?> packet, PacketSendListener listener) { send(packet); }
        @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) { send(packet); }
        @Override public void disconnect(Component reason) { connected = false; }
        @Override public void setEncryptionKey(Cipher decrypt, Cipher encrypt) {
            byte[] plaintext = "skin-login-fixture".getBytes(StandardCharsets.UTF_8);
            check(Arrays.equals(plaintext, decrypt.update(encrypt.update(plaintext))), "Vanilla login ciphers did not round-trip");
            encrypted = true;
        }
    }

    private static TextureProperty image(String url, String signature) {
        String payload = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\",\"metadata\":{\"model\":\"slim\"}}}}";
        return new TextureProperty(Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)), signature);
    }

    private static TextureProperty texture(GameProfile profile) {
        Property property = profile.getProperties().get("textures").iterator().next();
        return new TextureProperty(property.value(), property.signature());
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
    private static void expectRejected(CheckedAction action, String message) throws Exception {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    *///?}
}
