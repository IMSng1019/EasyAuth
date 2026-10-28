package xyz.nikitacartes.easyauth.skins;

//? if >= 1.21.5 {
import net.fabricmc.fabric.api.gametest.v1.GameTest;
//?} else {
/*import net.minecraft.gametest.framework.GameTest;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
*///?}
import net.minecraft.gametest.framework.GameTestHelper;

//? if >= 1.20.3 && < 1.20.5 {
/*import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import xyz.nikitacartes.easyauth.sources.AuthenticatedIdentity;
import xyz.nikitacartes.easyauth.sources.SourceType;
import xyz.nikitacartes.easyauth.sources.SourcesConfig;
import xyz.nikitacartes.easyauth.sources.UuidV7;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
*///?}

//? if >= 1.21.5 {
public class ServerSkinBridgeRuntimeTest {
    @GameTest
//?} else {
/*public class ServerSkinBridgeRuntimeTest implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE)
*///?}
    public void skinDecorationPreservesIdentityAndVanillaPackets(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*var directory = Files.createTempDirectory("easyauth-skin-runtime-");
        AtomicInteger conversions = new AtomicInteger();
        TextureProperty converted = texture("https://textures.minecraft.net/texture/" + "a".repeat(64), "slim", "fixture-mojang-signature");
        var config = SkinBridgeConfig.defaults();
        try (var engine = new SkinBridgeEngine(config, directory.resolve("cache.json"), input -> {
            conversions.incrementAndGet();
            return converted;
        }, value -> "fixture-mojang-signature".equals(value.signature()));
             var bridge = new ServerSkinBridge(config, engine, value -> "fixture-mojang-signature".equals(value.signature()))) {
            TextureProperty officialTexture = texture("https://textures.minecraft.net/texture/" + "b".repeat(64), "classic", "official-unchanged");
            GameProfile official = profile(UUID.randomUUID(), "Official", officialTexture);
            var officialIdentity = identity(SourceType.OFFICIAL, official.getName(), officialTexture);
            var direct = bridge.prepare(officialIdentity, SourcesConfig.defaults().sources().get(0), official, System.nanoTime());
            check(direct.isDone() && direct.join() == official && conversions.get() == 0, "Official skin entered the external conversion queue");

            for (SourceType type : List.of(SourceType.UNION, SourceType.SKIN_SITE)) {
                TextureProperty input = texture("https://skins.example.org/" + type + ".png", "slim", "provider-signature");
                var identity = identity(type, "Upstream", input);
                GameProfile mapped = profile(UuidV7.generate(), "[MUA]Upstream", input);
                var source = source(directory, type);
                GameProfile decorated = bridge.prepare(identity, source, mapped, System.nanoTime()).get(2, TimeUnit.SECONDS);
                check(textures(decorated).equals(converted), "External skin was not converted for " + type);
                check(textures(mapped).equals(input), "Decoration mutated the original profile in place");
                check(decorated.getId().equals(mapped.getId()) && decorated.getName().equals(mapped.getName()), "Skin conversion changed the local identity");
                check(decorated.getProperties().get("other").iterator().next().value().equals("preserved"), "Skin conversion discarded unrelated profile properties");
                verifyPackets(context, decorated, converted);
                var cached = bridge.prepare(identity, source, mapped, System.nanoTime()).get(2, TimeUnit.SECONDS);
                check(textures(cached).equals(converted), "Repeated login did not use the converted skin");
            }
            check(conversions.get() == 2, "Cached login generated a duplicate skin");
        }
        *///?}
        context.succeed();
    }

    //? if >= 1.21.5 {
    @GameTest
    //?} else {
    /*@GameTest(template = EMPTY_STRUCTURE)
    *///?}
    public void lateSkinResultsOnlyAffectLaterLogins(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*var directory = Files.createTempDirectory("easyauth-skin-late-");
        var config = new SkinBridgeConfig(true, 100, 5000, 60000, 24, 4, 60);
        TextureProperty input = texture("https://skins.example.org/late.png", "classic", "provider-signature");
        TextureProperty converted = texture("https://textures.minecraft.net/texture/" + "c".repeat(64), "classic", "fixture-mojang-signature");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger conversions = new AtomicInteger();
        try (var engine = new SkinBridgeEngine(config, directory.resolve("cache.json"), descriptor -> {
            conversions.incrementAndGet();
            started.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Test generation was not released");
            return converted;
        }, value -> "fixture-mojang-signature".equals(value.signature()));
             var bridge = new ServerSkinBridge(config, engine, value -> "fixture-mojang-signature".equals(value.signature()))) {
            var identity = identity(SourceType.SKIN_SITE, "Upstream", input);
            var source = source(directory, SourceType.SKIN_SITE);
            GameProfile mapped = profile(UuidV7.generate(), "[S]Upstream", input);
            var pending = bridge.prepare(identity, source, mapped, System.nanoTime());
            check(started.await(1, TimeUnit.SECONDS), "Skin generation did not start asynchronously");
            GameProfile returned = pending.get(2, TimeUnit.SECONDS);
            check(textures(returned).equals(input), "Timed-out login did not preserve the original skin");
            release.countDown();
            engine.select("observer", SkinDescriptor.from(input).orElseThrow()).refresh().get(2, TimeUnit.SECONDS);
            check(textures(returned).equals(input), "Late result mutated a profile already published to login");
            GameProfile next = bridge.prepare(identity, source, mapped, System.nanoTime()).get(2, TimeUnit.SECONDS);
            check(textures(next).equals(converted) && conversions.get() == 1, "Late conversion was not cached for the next login");
        } finally {
            release.countDown();
        }
        *///?}
        context.succeed();
    }

    //? if >= 1.21.5 {
    @GameTest
    //?} else {
    /*@GameTest(template = EMPTY_STRUCTURE)
    *///?}
    public void convertedTexturesUseAuthlibSignatureAndDomainValidation(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var key = new com.mojang.authlib.yggdrasil.ServicesKeyInfo() {
            @Override public int keyBitCount() { return 2048; }
            @Override public java.security.Signature signature() {
                try {
                    var verifier = java.security.Signature.getInstance("SHA1withRSA");
                    verifier.initVerify(pair.getPublic());
                    return verifier;
                } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
            }
            @Override public boolean validateProperty(Property property) {
                try {
                    var verifier = signature();
                    verifier.update(property.value().getBytes(StandardCharsets.UTF_8));
                    return verifier.verify(Base64.getDecoder().decode(property.signature()));
                } catch (Exception e) { return false; }
            }
        };
        // Authlib executes its actual payload/domain/signature-state logic. Only the public-key
        // set is a fixture so this test never downloads Mojang keys or stores account textures.
        var session = new com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService(
                type -> List.of(key), java.net.Proxy.NO_PROXY,
                new com.mojang.authlib.Environment("https://sessionserver.mojang.com", "https://api.minecraftservices.com", "TEST")) {};
        TextureProperty unsigned = texture("https://textures.minecraft.net/texture/" + "d".repeat(64), "slim", null);
        var signer = java.security.Signature.getInstance("SHA1withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(unsigned.value().getBytes(StandardCharsets.UTF_8));
        TextureProperty signed = new TextureProperty(unsigned.value(), Base64.getEncoder().encodeToString(signer.sign()));
        check(ServerSkinBridge.usableMojangTexture(session, signed), "A valid trusted texture was rejected");
        check(!ServerSkinBridge.usableMojangTexture(session, unsigned), "Unsigned output bypassed texture validation");
        String changed = new String(Base64.getDecoder().decode(signed.value()), StandardCharsets.UTF_8).replace("Upstream", "Changed");
        check(!ServerSkinBridge.usableMojangTexture(session, new TextureProperty(
                Base64.getEncoder().encodeToString(changed.getBytes(StandardCharsets.UTF_8)), signed.signature())),
                "Tampered texture bytes bypassed the original signature");
        TextureProperty blocked = texture("https://skins.example.org/skin.png", "slim", null);
        signer.initSign(pair.getPrivate());
        signer.update(blocked.value().getBytes(StandardCharsets.UTF_8));
        blocked = new TextureProperty(blocked.value(), Base64.getEncoder().encodeToString(signer.sign()));
        check(!ServerSkinBridge.usableMojangTexture(session, blocked), "A valid signature bypassed the required texture host");
        *///?}
        context.succeed();
    }

    //? if >= 1.21.5 {
    @GameTest
    //?} else {
    /*@GameTest(template = EMPTY_STRUCTURE)
    *///?}
    public void deduplicatedGenerationStillBoundsWaitingLogins(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*var directory = Files.createTempDirectory("easyauth-skin-capacity-");
        var config = new SkinBridgeConfig(true, 5000, 5000, 60000, 24, 1, 60);
        TextureProperty input = texture("https://skins.example.org/shared.png", "classic", "provider-signature");
        TextureProperty converted = texture("https://textures.minecraft.net/texture/" + "e".repeat(64), "classic", "fixture-mojang-signature");
        CountDownLatch generationStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch checkedInputs = new CountDownLatch(3);
        java.util.function.Predicate<TextureProperty> validation = value -> {
            if ("provider-signature".equals(value.signature())) checkedInputs.countDown();
            return "fixture-mojang-signature".equals(value.signature());
        };
        try (var engine = new SkinBridgeEngine(config, directory.resolve("cache.json"), descriptor -> {
            generationStarted.countDown();
            if (!release.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("Test generation was not released");
            return converted;
        }, validation); var bridge = new ServerSkinBridge(config, engine, validation)) {
            var source = source(directory, SourceType.SKIN_SITE);
            GameProfile mapped = profile(UuidV7.generate(), "[S]Capacity", input);
            var identity = identity(SourceType.SKIN_SITE, "Capacity", input);
            var first = bridge.prepare(identity, source, mapped, System.nanoTime());
            check(generationStarted.await(1, TimeUnit.SECONDS), "Capacity test generation did not start");
            var second = bridge.prepare(identity, source, mapped, System.nanoTime());
            var third = bridge.prepare(identity, source, mapped, System.nanoTime());
            check(checkedInputs.await(1, TimeUnit.SECONDS), "Initial cosmetic requests did not reach the shared job");
            var overflow = bridge.prepare(identity, source, mapped, System.nanoTime());
            check(overflow.isDone() && overflow.join() == mapped, "Deduplicated skin requests exceeded the waiting-login limit");
            GameProfile official = profile(UUID.randomUUID(), "Official", input);
            check(bridge.prepare(identity(SourceType.OFFICIAL, "Official", input), SourcesConfig.defaults().sources().get(0),
                    official, System.nanoTime()).join() == official, "Cosmetic capacity blocked an official profile");
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            third.get(2, TimeUnit.SECONDS);
            check(textures(bridge.prepare(identity, source, mapped, System.nanoTime()).get(2, TimeUnit.SECONDS)).equals(converted),
                    "Completing skin requests did not release the waiting-login capacity");
        } finally { release.countDown(); }
        *///?}
        context.succeed();
    }

    //? if >= 1.20.3 && < 1.20.5 {
    /*private static SourcesConfig.Source source(java.nio.file.Path directory, SourceType type) throws Exception {
        var file = directory.resolve(type + ".json");
        String mapped = type == SourceType.UNION ? ",\"mappedUrl\":\"https://skins.example.org/mapped/{uuid}\"" : "";
        Files.writeString(file, "{\"configVersion\":1,\"sources\":[{\"id\":\"skin-test\",\"type\":\"" + type
                + "\",\"apiRoot\":\"https://skins.example.org\"" + mapped + "}]}");
        return SourcesConfig.load(file).sources().get(0);
    }

    private static AuthenticatedIdentity identity(SourceType type, String name, TextureProperty texture) {
        UUID provider = UUID.randomUUID();
        return new AuthenticatedIdentity(type == SourceType.OFFICIAL ? "official" : "skin-test", type, provider.toString(), provider, name,
                List.of(new AuthenticatedIdentity.PropertyData("textures", texture.value(), texture.signature())));
    }

    private static TextureProperty texture(String url, String model, String signature) {
        String payload = "{\"profileId\":\"123456781234423482341234567890ab\",\"profileName\":\"Upstream\",\"textures\":{\"SKIN\":{\"url\":\""
                + url + "\",\"metadata\":{\"model\":\"" + model + "\"}}}}";
        return new TextureProperty(Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)), signature);
    }

    private static GameProfile profile(UUID uuid, String name, TextureProperty texture) {
        GameProfile profile = new GameProfile(uuid, name);
        profile.getProperties().put("textures", new Property("textures", texture.value(), texture.signature()));
        profile.getProperties().put("other", new Property("other", "preserved"));
        return profile;
    }

    private static TextureProperty textures(GameProfile profile) {
        Property value = profile.getProperties().get("textures").iterator().next();
        return new TextureProperty(value.value(), value.signature());
    }

    private static void verifyPackets(GameTestHelper context, GameProfile profile, TextureProperty expected) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ClientboundGameProfilePacket(profile).write(buffer);
            GameProfile decoded = new ClientboundGameProfilePacket(buffer).getGameProfile();
            check(decoded.getId().equals(profile.getId()) && decoded.getName().equals(profile.getName())
                    && textures(decoded).equals(expected), "Login-success packet lost skin or mapped identity");
            check(!buffer.isReadable(), "Login-success packet left unread bytes");
        } finally { buffer.release(); }
        var server = context.getLevel().getServer();
        ServerPlayer player = new ServerPlayer(server, context.getLevel(), profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override public void setListener(PacketListener listener) {}
            @Override public void send(Packet<?> packet) {}
            @Override public void send(Packet<?> packet, PacketSendListener listener) {}
            @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {}
        };
        player.connection = new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile));
        buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)).write(buffer);
            var entry = new ClientboundPlayerInfoUpdatePacket(buffer).entries().get(0);
            check(entry.profileId().equals(profile.getId()) && entry.profile().getName().equals(profile.getName())
                    && textures(entry.profile()).equals(expected), "ADD_PLAYER packet lost skin or mapped identity");
            check(!buffer.isReadable(), "ADD_PLAYER packet left unread bytes");
        } finally { buffer.release(); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    *///?}
}
