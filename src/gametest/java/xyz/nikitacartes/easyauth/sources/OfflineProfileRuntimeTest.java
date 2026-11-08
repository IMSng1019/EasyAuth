package xyz.nikitacartes.easyauth.sources;

//? if >= 1.21.5 {
import net.fabricmc.fabric.api.gametest.v1.GameTest;
//?} else {
/*import net.minecraft.gametest.framework.GameTest;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
*///?}
import net.minecraft.gametest.framework.GameTestHelper;

//? if >= 1.20.3 && < 1.20.5 {
/*import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.StringReader;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import xyz.nikitacartes.easyauth.EasyAuth;
import xyz.nikitacartes.easyauth.event.AuthEventHandler;
import xyz.nikitacartes.easyauth.interfaces.PlayerAuth;
import xyz.nikitacartes.easyauth.storage.PlayerEntryV1;
import xyz.nikitacartes.easyauth.utils.AuthHelper;
import xyz.nikitacartes.easyauth.utils.PlayersCache;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
*///?}

//? if >= 1.21.5 {
public class OfflineProfileRuntimeTest {
    @GameTest
//?} else {
/*public class OfflineProfileRuntimeTest implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE)
*///?}
    public void realOfflineNamesPreserveCredentialsAndRequirePasswordAuthentication(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*var server = context.getLevel().getServer();
        var snapshotField = MultiAuth.class.getDeclaredField("current");
        snapshotField.setAccessible(true);
        Object previousSnapshot = snapshotField.get(null);
        var ledgerField = MultiAuth.class.getDeclaredField("identities");
        ledgerField.setAccessible(true);
        Object previousLedger = ledgerField.get(null);
        var configFile = Files.createTempFile("easyauth-offline-profile-", ".json");
        var ledgerFile = Files.createTempFile("easyauth-offline-profile-", ".sqlite");
        boolean onlineMode = server.usesAuthentication();
        ServerPlayer player = null;
        try (IdentityStore ledger = new IdentityStore(ledgerFile)) {
            ledgerField.set(null, ledger);
            server.setUsesAuthentication(false);
            Files.writeString(configFile, "{\"configVersion\":1,\"offlineNamePrefix\":\"[OFF] \",\"sources\":[]}");
            SourcesConfig snapshot = SourcesConfig.load(configFile);
            snapshotField.set(null, snapshot);
            String rawName = "Old" + UUID.randomUUID().toString().substring(0, 7);
            String localName = "[OFF]" + rawName;
            UUID rawUuid = UUIDUtil.createOfflinePlayerUUID(rawName);
            UUID forcedUuid = UUID.randomUUID();
            PlayerEntryV1 old = new PlayerEntryV1(rawName, rawUuid);
            old.forcedUuid = forcedUuid.toString();
            old.onlineAccount = PlayerEntryV1.OnlineAccount.FALSE;
            old.password = AuthHelper.hashPassword("old-password".toCharArray());
            old.otpSecret = "JBSWY3DPEHPK3PXP";
            old.otpEnabled = true;
            old.passkeys = new java.util.ArrayList<>(List.of("fixture-key"));
            EasyAuth.DB.registerUser(old);
            ledger.reserveLegacy(rawName, forcedUuid, "OFFLINE");

            GameProfile profile = MultiAuth.preparePassword(server, rawName);
            check(profile.getName().equals(localName), "Offline GameProfile still uses its raw name instead of the MUA-style local name");
            check(profile.getId().equals(forcedUuid), "Offline prefix changed the effective legacy UUID");
            PlayerEntryV1 aliased = EasyAuth.DB.getUserData(localName);
            check(aliased != null && aliased.username.equals(rawName) && aliased.password.equals(old.password)
                            && aliased.otpEnabled && aliased.otpSecret.equals(old.otpSecret) && aliased.passkeys.equals(old.passkeys),
                    "Mapped login lost or replaced the existing credential record");
            check(!EasyAuth.DB.getAllDataStrict().containsKey(localName.toLowerCase(java.util.Locale.ROOT)),
                    "Mapping created a second password account");
            check(AuthHelper.checkPassword(localName, "old-password".toCharArray()) == AuthHelper.PasswordOptions.CORRECT,
                    "Existing password does not work through the local name");
            check(PlayersCache.getOrLoadOrRegister(localName) == PlayersCache.getOrLoadOrRegister(rawName),
                    "Raw and local account names acquired different password caches");

            TestConnection connection = new TestConnection();
            expectRejected(() -> MultiAuth.beforeVerification(connection, profile), "An allocated name alone granted admission");
            expectRejected(() -> MultiAuth.beforeVerification(new TestConnection(), new GameProfile(UUID.randomUUID(), rawName)),
                    "A different authenticated UUID could claim the mapped account's original password key");
            var prepare = MultiAuth.class.getMethod("preparePassword", net.minecraft.server.MinecraftServer.class,
                    Connection.class, SourcesConfig.class, String.class);
            GameProfile bound = (GameProfile) prepare.invoke(null, server, connection, snapshot, rawName);
            MultiAuth.beforeVerification(connection, bound);
            expectRejected(() -> MultiAuth.beforeVerification(new TestConnection(), bound), "Offline mapping proof crossed connections");
            expectRejected(() -> MultiAuth.preparePassword(server, localName), "Launcher could directly claim an allocated offline name");
            player = new ServerPlayer(server, context.getLevel(), bound, ClientInformation.createDefault());
            player.connection = new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(bound));
            AuthEventHandler.loadPlayerData(player, connection);
            PlayerAuth auth = (PlayerAuth) player;
            check(auth.easyAuth$getAuthenticationChannel() == AuthenticationChannel.OFFLINE
                            && !auth.easyAuth$isExternalAuthenticated() && !auth.easyAuth$canSkipAuth()
                            && !auth.easyAuth$isAuthenticated() && !auth.easyAuth$relaxSourceChatKeys(),
                    "A name mapping bypassed password or 2FA authentication");
            check(auth.easyAuth$getLocalNamePrefix().equals("[OFF]"), "Creation-time offline prefix was not attached to the session");
            check(player.getDisplayName().getString().equals(localName) && player.getTabListDisplayName() == null,
                    "The real offline prefix was added twice in chat/TAB");
            server.getPlayerList().getPlayers().add(player);
            check(EntityArgument.player().parse(new StringReader("\"" + localName + "\"")).findSinglePlayer(server.createCommandSourceStack()) == player,
                    "Allocated offline name does not work as a vanilla quoted command target");
            check(MultiAuth.rejectIdentityEdit(server.createCommandSourceStack(), rawName)
                            && MultiAuth.rejectIdentityEdit(server.createCommandSourceStack(), localName),
                    "An alias bypassed identity-edit protection");
            check(!MultiAuth.rejectExternalRegistration(server.createCommandSourceStack(), localName),
                    "Local password registration was treated as external authentication");

            Files.writeString(configFile, "{\"configVersion\":1,\"offlineNamePrefix\":\"[NEW]\",\"sources\":[]}");
            snapshotField.set(null, SourcesConfig.load(configFile));
            GameProfile reloaded = MultiAuth.preparePassword(server, rawName);
            String newName = "[NEW]" + rawName;
            check(reloaded.getName().equals(newName) && reloaded.getId().equals(forcedUuid),
                    "Reconnect did not replace the old offline prefix while preserving UUID");
            check(EasyAuth.DB.getUserData(newName).toJson().equals(EasyAuth.DB.getUserData(localName).toJson())
                            && AuthHelper.checkPassword(newName, "old-password".toCharArray()) == AuthHelper.PasswordOptions.CORRECT,
                    "Prefix refresh lost the original credential data");
            check(PlayersCache.getOrLoadOrRegister(newName) == PlayersCache.getOrLoadOrRegister(localName),
                    "Old and new names no longer share the original account cache");
            check(xyz.nikitacartes.easyauth.commands.AuthCommand.forceLogin(server.createCommandSourceStack(), newName) == 1,
                    "Admin command could not locate the still-online old-name session after prefix refresh");
            expectRejected(() -> MultiAuth.preparePassword(server, localName), "Old offline name became claimable");
            expectRejected(() -> MultiAuth.preparePassword(server, newName), "New offline name became claimable");
            snapshotField.set(null, SourcesConfig.defaults());
            GameProfile cleared = MultiAuth.preparePassword(server, rawName);
            check(cleared.getName().equals(rawName) && cleared.getId().equals(forcedUuid),
                    "Clearing the configured prefix failed to restore the raw name with its effective UUID");
            check(EasyAuth.DB.getUserData(newName).otpEnabled && EasyAuth.DB.getUserData(newName).passkeys.equals(old.passkeys),
                    "Cleared prefix lost old-session credential aliases");
            check(MultiAuth.preventAutomaticMigration() && MultiAuth.offlineNamesEnabled() && MultiAuth.hasOfflineAccount(rawName),
                    "Clearing the last offline prefix disabled ownership or UUID migration protection");
            expectRejected(() -> MultiAuth.beforeVerification(new TestConnection(), cleared),
                    "Cleared offline identity no longer requires connection-bound ownership");
            TestConnection clearedConnection = new TestConnection();
            GameProfile clearedBound = MultiAuth.preparePassword(server, clearedConnection, SourcesConfig.defaults(), rawName);
            MultiAuth.beforeVerification(clearedConnection, clearedBound);
            check(MultiAuth.consumeOfflineProof(clearedConnection, clearedBound) != null,
                    "Cleared offline prefix did not issue its ownership proof");
            PlayerEntryV1 oldSessionEntry = auth.easyAuth$getPlayerEntryV1();
            String previousHash = oldSessionEntry.password;
            oldSessionEntry.passkeys.add("key-to-revoke");
            oldSessionEntry.issueSessionToken(3600);
            xyz.nikitacartes.easyauth.commands.AuthCommand.updatePassword(server.createCommandSourceStack(), newName, "reset-password");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && (oldSessionEntry.password.equals(previousHash)
                    || oldSessionEntry.sessionTokenHash != null || !oldSessionEntry.passkeys.isEmpty())) {
                Thread.sleep(10);
            }
            check(!oldSessionEntry.password.equals(previousHash) && oldSessionEntry.sessionTokenHash == null
                            && oldSessionEntry.passkeys.isEmpty(),
                    "Password reset missed the old-name session, allowing logout to restore old credentials");
        } finally {
            if (player != null) server.getPlayerList().getPlayers().remove(player);
            server.setUsesAuthentication(onlineMode);
            snapshotField.set(null, previousSnapshot);
            ledgerField.set(null, previousLedger);
            Files.deleteIfExists(configFile);
            Files.deleteIfExists(ledgerFile);
        }
        *///?}
        context.succeed();
    }

    //? if >= 1.20.3 && < 1.20.5 {
    /*private static final class TestConnection extends Connection {
        TestConnection() { super(PacketFlow.SERVERBOUND); }
        @Override public void setListener(PacketListener listener) {}
        @Override public boolean isConnected() { return true; }
        @Override public java.net.SocketAddress getRemoteAddress() { return new java.net.InetSocketAddress("127.0.0.1", 25565); }
        @Override public void send(Packet<?> packet) {}
        @Override public void send(Packet<?> packet, PacketSendListener listener) {}
        @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {}
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
    private static void expectRejected(CheckedAction action, String message) throws Exception {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    *///?}
}
