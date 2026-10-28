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
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
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
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.level.GameType;
import xyz.nikitacartes.easyauth.EasyAuth;
import xyz.nikitacartes.easyauth.event.AuthEventHandler;
import xyz.nikitacartes.easyauth.interfaces.PlayerAuth;
import xyz.nikitacartes.easyauth.integrations.SourceNameDisplay;
import xyz.nikitacartes.easyauth.storage.PlayerEntryV1;
import xyz.nikitacartes.easyauth.utils.StoneCutterUtils;
import xyz.nikitacartes.easyauth.utils.PlayersCache;
import net.minecraft.world.level.storage.LevelResource;

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
        String oldSecureProfile = EasyAuth.serverProp.getProperty("enforce-secure-profile");
        Path configFile = Files.createTempFile("easyauth-runtime-", ".json");
        try {
            EasyAuth.serverProp.setProperty("enforce-secure-profile", "false");
            int port = fixture.getAddress().getPort();
            Files.writeString(configFile, "{\"configVersion\":1,\"newPlayerAuth\":\"EXTERNAL\",\"relaxCustomChatKeys\":true,\"sources\":[{"
                    + "\"id\":\"gametest-union\",\"type\":\"UNION\",\"priority\":1,\"namePrefix\":\"[MUA] \","
                    + "\"hasJoinedUrl\":\"http://127.0.0.1:" + port + "/joined\","
                    + "\"mappedUrl\":\"http://127.0.0.1:" + port + "/mapped/{uuid}\"}]}");
            SourcesConfig sources = SourcesConfig.load(configFile);
            // First allocate without a namespace, as in schema-v1/.2, then verify that enabling
            // display prefixes does not rename an existing identity or its legacy DB record.
            String namespacedConfig = Files.readString(configFile);
            Files.writeString(configFile, namespacedConfig.replace("[MUA] ", ""));
            MultiAuth.install(SourcesConfig.load(configFile));
            Files.writeString(configFile, namespacedConfig);
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
            MultiAuth.install(sources);
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
            check(player.getDisplayName().getString().equals("[MUA] " + name), "Union chat prefix was not applied");
            check(player.getTabListDisplayName() != null && player.getTabListDisplayName().getString().equals("[MUA] " + name), "Union TAB prefix was missing or duplicated");
            check(player.getScoreboardName().equals(name) && StoneCutterUtils.getName(player.getGameProfile()).equals(name), "Display prefix changed the real player name");
            check(player.getUUID().equals(localUuid) && EasyAuth.DB.getUserData(name).uuid.equals(localUuid), "Display prefix changed persisted identity");
            check(playerAuth.easyAuth$getAuthenticationChannel() == AuthenticationChannel.UNION
                    && "gametest-union".equals(playerAuth.easyAuth$getAuthenticationSourceId()), "Display channel did not come from the verified source");

            String originalConfig = Files.readString(configFile);
            Files.writeString(configFile, originalConfig.replace("[MUA] ", "[Campus] "));
            MultiAuth.install(SourcesConfig.load(configFile));
            check(player.getDisplayName().getString().equals("[Campus] " + name), "Online chat prefix did not refresh");
            check(player.getTabListDisplayName().getString().equals("[Campus] " + name), "Online TAB prefix did not refresh or doubled");
            Component rankedName = Component.literal("[Rank] " + name).withStyle(ChatFormatting.GOLD);
            Component decorated = SourceNameDisplay.tabName(player, rankedName);
            check(decorated.getString().equals("[Campus] [Rank] " + name), "Existing TAB decoration was lost");
            check(decorated.getSiblings().get(0).getStyle().getColor().equals(rankedName.getStyle().getColor()), "Existing TAB style was lost");
            check(rankedName.getString().equals("[Rank] " + name), "Display prefix mutated another mod's Component");

            Files.writeString(configFile, originalConfig.replace("\"priority\":1", "\"priority\":1,\"enabled\":false"));
            MultiAuth.install(SourcesConfig.load(configFile));
            check(player.getDisplayName().getString().equals("[MUA] " + name), "Disabling a provider relabeled an established session");
            Files.writeString(configFile, originalConfig.replace("gametest-union", "other-union"));
            MultiAuth.install(SourcesConfig.load(configFile));
            check(player.getDisplayName().getString().equals(name) && player.getTabListDisplayName() == null, "Missing source inherited another provider's prefix");

            Files.writeString(configFile, originalConfig.replace("[MUA] ", ""));
            MultiAuth.install(SourcesConfig.load(configFile));
            check(player.getDisplayName().getString().equals(name) && player.getTabListDisplayName() == null, "Clearing a prefix did not restore vanilla display");
            check(SourceNameDisplay.tabName(player, rankedName) == rankedName
                    && SourceNameDisplay.chatName(player, rankedName) == rankedName, "Empty prefix did not preserve the original Component");
            check(playerAuth.easyAuth$isAuthenticated() && playerAuth.easyAuth$isExternalAuthenticated()
                    && player.getUUID().equals(localUuid) && player.getScoreboardName().equals(name), "Display reload changed identity or authentication");
            MultiAuth.install(sources);

            // Exercise the actual reload command path and the packet sent to an online player.
            String diskConfig = Files.readString(MultiAuth.configPath());
            server.getPlayerList().getPlayers().add(player);
            PlayerTeam testTeam = null;
            try {
                Files.writeString(MultiAuth.configPath(), originalConfig.replace("[MUA] ", "[Live] "));
                authenticated.lastPlayerInfo = null;
                check(MultiAuth.reload(server.createCommandSourceStack()) == 1, "Source-only prefix reload failed");
                check(authenticated.lastPlayerInfo != null && authenticated.lastPlayerInfo.entries().get(0).displayName()
                        .getString().equals("[Live] " + name), "Reload did not broadcast the updated TAB name");

                Files.writeString(MultiAuth.configPath(), originalConfig.replace("[MUA] ", ""));
                authenticated.lastPlayerInfo = null;
                check(MultiAuth.reload(server.createCommandSourceStack()) == 1, "Empty-prefix reload failed");
                check(authenticated.lastPlayerInfo != null && authenticated.lastPlayerInfo.entries().get(0).displayName() == null,
                        "Clearing prefix did not broadcast the vanilla null TAB value");

                MultiAuth.install(sources);
                testTeam = server.getScoreboard().addPlayerTeam("src_" + suffix);
                testTeam.setPlayerPrefix(Component.literal("[Rank] ").withStyle(ChatFormatting.GOLD));
                authenticated.lastPlayerInfo = null;
                server.getScoreboard().addPlayerToTeam(name, testTeam);
                check(authenticated.lastPlayerInfo != null && authenticated.lastPlayerInfo.entries().get(0).displayName()
                        .getString().equals("[MUA] [Rank] " + name), "Joining a team did not refresh prefixed TAB name");
                authenticated.lastPlayerInfo = null;
                testTeam.setPlayerPrefix(Component.literal("[NewRank] "));
                check(authenticated.lastPlayerInfo != null && authenticated.lastPlayerInfo.entries().get(0).displayName()
                        .getString().equals("[MUA] [NewRank] " + name), "Team prefix update left the TAB name stale");
                authenticated.lastPlayerInfo = null;
                server.getScoreboard().removePlayerFromTeam(name, testTeam);
                check(authenticated.lastPlayerInfo != null && authenticated.lastPlayerInfo.entries().get(0).displayName()
                        .getString().equals("[MUA] " + name), "Leaving a team left its TAB decoration behind");
                server.getScoreboard().addPlayerToTeam(name, testTeam);
                authenticated.lastPlayerInfo = null;
                server.getScoreboard().removePlayerTeam(testTeam);
                testTeam = null;
                check(authenticated.lastPlayerInfo != null && authenticated.lastPlayerInfo.entries().get(0).displayName()
                        .getString().equals("[MUA] " + name), "Deleting a team left its TAB decoration behind");
            } finally {
                if (testTeam != null) server.getScoreboard().removePlayerTeam(testTeam);
                server.getPlayerList().getPlayers().remove(player);
                Files.writeString(MultiAuth.configPath(), diskConfig);
                MultiAuth.install(sources);
            }

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
            if (oldSecureProfile == null) EasyAuth.serverProp.remove("enforce-secure-profile");
            else EasyAuth.serverProp.setProperty("enforce-secure-profile", oldSecureProfile);
            fixture.stop(0);
            Files.deleteIfExists(configFile);
        }
        *///?}
        context.succeed();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    //? if >= 1.21.5 {
    @GameTest
    //?} else {
    /*@GameTest(template = EMPTY_STRUCTURE)
    *///?}
    public void unionNamespacePreservesExistingOfficialAccount(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*// A short random suffix keeps this LXW1019 regression independent of prior test runs.
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        String rawName = "LXW1019" + suffix;
        String localName = "[MUA]" + rawName;
        UUID officialUuid = UUID.randomUUID();
        UUID providerUuid = UUIDUtil.createOfflinePlayerUUID(rawName);
        MinecraftServer server = context.getLevel().getServer();
        SourcesConfig before = MultiAuth.snapshot();
        boolean oldOnlineMode = server.usesAuthentication();
        String oldSecure = EasyAuth.serverProp.getProperty("enforce-secure-profile");
        Path configFile = Files.createTempFile("easyauth-namespace-", ".json");
        Path worldFile = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(officialUuid + ".dat");
        byte[] marker = "official-playerdata-must-not-move".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> expectedDigest = new AtomicReference<>();
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/joined", exchange -> {
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            if (expectedDigest.get() == null || !query.contains("serverId=" + expectedDigest.get())
                    || !query.contains("username=" + rawName)) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            byte[] body = ("{\"id\":\"" + providerUuid + "\",\"name\":\"" + rawName + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fixture.createContext("/mapped/", exchange -> {
            byte[] body = ("{\"uuid\":\"" + providerUuid + "\",\"internal_id\":\"namespace-" + rawName + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fixture.start();
        ServerPlayer official = null;
        ServerPlayer union = null;
        try {
            EasyAuth.serverProp.setProperty("enforce-secure-profile", "false");
            int port = fixture.getAddress().getPort();
            String sourceJson = "{\"id\":\"gametest-namespace\",\"type\":\"UNION\",\"priority\":1,\"namePrefix\":\"[MUA] \","
                    + "\"hasJoinedUrl\":\"http://127.0.0.1:" + port + "/joined\","
                    + "\"mappedUrl\":\"http://127.0.0.1:" + port + "/mapped/{uuid}\"}";
            // Even a disabled source reserves its namespace before a profile has been created.
            Files.writeString(configFile, "{\"configVersion\":1,\"sources\":[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"priority\":0,\"timeoutMillis\":2000},"
                    + sourceJson.replace("\"priority\":1", "\"priority\":1,\"enabled\":false") + "]}");
            MultiAuth.install(SourcesConfig.load(configFile));
            server.setUsesAuthentication(false);
            check(!MultiAuth.enabled(), "Namespace preclaim fixture must exercise the legacy login path");
            // Other integrations (including a claimed Floodgate UUID) can reach the legacy cache
            // path without the managed handshake branch. It must not create a reserved name.
            expectRejected(() -> PlayersCache.loadOrRegister(localName), "Legacy cache registration claimed an external namespace");
            check(EasyAuth.DB.getUserData(localName) == null, "Namespace rejection left a legacy placeholder");
            TestConnection preclaim = new TestConnection();
            new ServerLoginPacketListenerImpl(server, preclaim).handleHello(new ServerboundHelloPacket(localName,
                    UUIDUtil.createOfflinePlayerUUID(localName)));
            check(preclaim.disconnected != null && EasyAuth.DB.getUserData(localName) == null,
                    "Typing a reserved namespace created an unauthenticated local account");

            Files.writeString(configFile, "{\"configVersion\":1,\"newPlayerAuth\":\"EXTERNAL\",\"sources\":[" + sourceJson + "]}");
            SourcesConfig sources = SourcesConfig.load(configFile);
            MultiAuth.install(sources);
            server.setUsesAuthentication(true);
            if (server.getKeyPair() == null) {
                var keys = MinecraftServer.class.getDeclaredMethod("initializeKeyPair");
                keys.setAccessible(true);
                keys.invoke(server);
            }
            PlayerEntryV1 legacy = new PlayerEntryV1(rawName, officialUuid);
            legacy.onlineAccount = PlayerEntryV1.OnlineAccount.TRUE;
            legacy.password = "preserved-password-hash";
            EasyAuth.DB.registerUser(legacy);
            PlayersCache.put(rawName, legacy);
            Files.createDirectories(worldFile.getParent());
            Files.write(worldFile, marker);

            TestConnection unionConnection = new TestConnection();
            ServerLoginPacketListenerImpl listener = new ServerLoginPacketListenerImpl(server, unionConnection);
            listener.handleHello(new ServerboundHelloPacket(rawName, providerUuid));
            check(unionConnection.challenge != null, "Existing official name prevented the Union challenge");
            SecretKey secret = Crypt.generateSecretKey();
            expectedDigest.set(new BigInteger(Crypt.digestData(unionConnection.challenge.getServerId(),
                    unionConnection.challenge.getPublicKey(), secret)).toString(16));
            listener.handleKey(new ServerboundKeyPacket(secret, unionConnection.challenge.getPublicKey(), unionConnection.challenge.getChallenge()));
            GameProfile profile = awaitProfile(listener, unionConnection);
            check(StoneCutterUtils.getName(profile).equals(localName), "Union did not receive its actual local name namespace");
            check(StoneCutterUtils.getId(profile).version() == 7 && !StoneCutterUtils.getId(profile).equals(officialUuid), "Union reused official UUID");
            MultiAuth.beforeVerification(unionConnection, profile);
            expectRejected(() -> MultiAuth.beforeVerification(new TestConnection(), profile), "Prefix text bypassed connection authentication");

            official = new ServerPlayer(server, context.getLevel(), new GameProfile(officialUuid, rawName), ClientInformation.createDefault());
            TestConnection officialConnection = new TestConnection();
            official.connection = new ServerGamePacketListenerImpl(server, officialConnection, official,
                    CommonListenerCookie.createInitial(official.getGameProfile()));
            AuthEventHandler.loadPlayerData(official, officialConnection);
            ((PlayerAuth) official).easyAuth$setIpAddress("198.51.100.42");
            server.getPlayerList().getPlayers().add(official);
            check(!AuthEventHandler.usernamePattern.matcher(localName).matches(), "Regression fixture did not exercise bracket-name validation");
            check(AuthEventHandler.checkCanPlayerJoinServer(profile, server.getPlayerList(), unionConnection.getRemoteAddress()) == null,
                    "Verified bracket namespace was rejected or collided with the online official name");
            check(AuthEventHandler.checkCanPlayerJoinServer(new GameProfile(UUID.randomUUID(), localName), server.getPlayerList(),
                    unionConnection.getRemoteAddress()) != null, "Unrelated UUID gained the custom-name regex exemption");

            union = new ServerPlayer(server, context.getLevel(), profile, ClientInformation.createDefault());
            union.connection = new ServerGamePacketListenerImpl(server, unionConnection, union, CommonListenerCookie.createInitial(profile));
            AuthEventHandler.loadPlayerData(union, unionConnection);
            server.getPlayerList().getPlayers().add(union);
            check(((PlayerAuth) union).easyAuth$isAuthenticated(), "Namespaced Union player still required registration");
            check(union.getDisplayName().getString().equals(localName), "Actual namespace was prefixed a second time");
            check(server.getPlayerList().getPlayerByName(rawName) == official
                    && server.getPlayerList().getPlayerByName(localName) == union, "Official and Union could not coexist by their local names");
            verifyStationaryMappedPlayerCombat(official, union, unionConnection);
            PlayerEntryV1 unchanged = EasyAuth.DB.getUserData(rawName);
            check(unchanged.uuid.equals(officialUuid) && unchanged.onlineAccount == PlayerEntryV1.OnlineAccount.TRUE
                    && unchanged.password.equals(legacy.password), "Union login changed the original official account");
            check(EasyAuth.DB.getUserData(localName).uuid.equals(StoneCutterUtils.getId(profile)), "New local profile was not stored separately");
            check(Arrays.equals(Files.readAllBytes(worldFile), marker) && !Files.exists(worldFile.resolveSibling(worldFile.getFileName() + ".migrated")),
                    "Union login moved or changed original playerdata");

            String offlineName = "OldLocal" + suffix;
            PlayerEntryV1 local = new PlayerEntryV1(offlineName, UUIDUtil.createOfflinePlayerUUID(offlineName));
            local.onlineAccount = PlayerEntryV1.OnlineAccount.FALSE;
            local.password = "local-password-hash";
            EasyAuth.DB.registerUser(local);
            check(MultiAuth.shouldUsePasswordPath(sources, offlineName, local.uuid), "Existing genuine offline client lost password routing");
            check(!MultiAuth.shouldUsePasswordPath(sources, offlineName, UUID.randomUUID()), "A distinct custom UUID was forced into the old password account");
        } finally {
            if (union != null) server.getPlayerList().getPlayers().remove(union);
            if (official != null) server.getPlayerList().getPlayers().remove(official);
            MultiAuth.install(before);
            server.setUsesAuthentication(oldOnlineMode);
            if (oldSecure == null) EasyAuth.serverProp.remove("enforce-secure-profile");
            else EasyAuth.serverProp.setProperty("enforce-secure-profile", oldSecure);
            fixture.stop(0);
            Files.deleteIfExists(worldFile);
            Files.deleteIfExists(configFile);
        }
        *///?}
        context.succeed();
    }

    //? if >= 1.20.3 && < 1.20.5 {
    /*private static void verifyStationaryMappedPlayerCombat(ServerPlayer official, ServerPlayer union,
                                                            TestConnection unionConnection) {
        MinecraftServer server = union.server;
        boolean oldPvp = server.isPvpAllowed();
        try {
            server.setPvpAllowed(true);
            official.setGameMode(GameType.SURVIVAL);
            union.setGameMode(GameType.SURVIVAL);
            check(((PlayerAuth) official).easyAuth$isAuthenticated(), "Official attacker was not authenticated");
            check(((PlayerAuth) union).easyAuth$isAuthenticated(), "Mapped target was not authenticated");

            // Let vanilla expire its 60-tick spawn protection. The fixture replaces transport,
            // so drive the world and listener tick entry points, including the world's tickCount
            // update; do not bypass the listener by calling doTick.
            // Neither player sends a movement packet anywhere in this regression.
            for (int i = 0; i < 61; i++) {
                official.serverLevel().tickNonPassenger(official);
                official.connection.tick();
                union.serverLevel().tickNonPassenger(union);
                union.connection.tick();
            }
            var stationaryPosition = union.position();
            int tickBeforeDamage = union.tickCount;
            float healthBeforeAttack = union.getHealth();
            int motionPacketsBeforeAttack = unionConnection.entityMotionPackets;
            official.attack(union);
            check(union.getHealth() < healthBeforeAttack, "Official attack did not damage the authenticated mapped player");
            check(union.invulnerableTime > 0, "First attack did not establish vanilla damage cooldown");
            check(unionConnection.entityMotionPackets > motionPacketsBeforeAttack
                            && unionConnection.lastEntityMotion.getId() == union.getId(),
                    "Mapped player did not receive knockback for its actual entity ID");
            check(!union.hurt(union.damageSources().playerAttack(official), 1.0F),
                    "Mapped player lost vanilla protection against immediate repeated damage");

            for (int i = 0; i < 20; i++) {
                official.serverLevel().tickNonPassenger(official);
                official.connection.tick();
                union.serverLevel().tickNonPassenger(union);
                union.connection.tick();
            }
            check(union.tickCount >= tickBeforeDamage + 20, "Mapped player's normal ticks stopped without movement packets");
            check(union.invulnerableTime == 0, "Mapped player's damage cooldown did not expire while stationary");
            check(union.position().equals(stationaryPosition), "Server moved the mapped player without client movement");
            check(((PlayerAuth) union).easyAuth$isAuthenticated(), "Stationary mapped player lost authentication");
            healthBeforeAttack = union.getHealth();
            motionPacketsBeforeAttack = unionConnection.entityMotionPackets;
            official.attack(union);
            check(union.getHealth() < healthBeforeAttack, "Second official attack required the mapped player to move first");
            check(unionConnection.entityMotionPackets > motionPacketsBeforeAttack
                            && unionConnection.lastEntityMotion.getId() == union.getId(),
                    "Second hit did not send mapped-player knockback");
        } finally {
            server.setPvpAllowed(oldPvp);
        }
    }

    private static GameProfile awaitProfile(ServerLoginPacketListenerImpl listener, TestConnection connection) throws Exception {
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
        private ClientboundPlayerInfoUpdatePacket lastPlayerInfo;
        private ClientboundSetEntityMotionPacket lastEntityMotion;
        private int entityMotionPackets;

        TestConnection() { super(PacketFlow.SERVERBOUND); }

        @Override public void send(Packet<?> packet) {
            if (packet instanceof ClientboundHelloPacket hello) challenge = hello;
            if (packet instanceof ClientboundPlayerInfoUpdatePacket info) lastPlayerInfo = info;
            if (packet instanceof ClientboundSetEntityMotionPacket motion) {
                lastEntityMotion = motion;
                entityMotionPackets++;
            }
        }

        @Override public void send(Packet<?> packet, PacketSendListener listener) { send(packet); }
        @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) { send(packet); }

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
