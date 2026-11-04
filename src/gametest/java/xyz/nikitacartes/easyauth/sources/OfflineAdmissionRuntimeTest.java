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
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
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
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.UUID;
*///?}

//? if >= 1.21.5 {
public class OfflineAdmissionRuntimeTest {
    @GameTest
//?} else {
/*public class OfflineAdmissionRuntimeTest implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE)
*///?}
    public void offlinePrefixUpgradeKeepsDuplicateLoginProtection(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*var server = context.getLevel().getServer();
        var manager = server.getPlayerList();
        var snapshotField = MultiAuth.class.getDeclaredField("current");
        snapshotField.setAccessible(true);
        Object oldSnapshot = snapshotField.get(null);
        var ledgerField = MultiAuth.class.getDeclaredField("identities");
        ledgerField.setAccessible(true);
        Object oldLedger = ledgerField.get(null);
        boolean oldOnline = server.usesAuthentication();
        boolean oldDuplicateProtection = EasyAuth.extendedConfig.preventAnotherLocationKick;
        boolean oldIpLimit = EasyAuth.extendedConfig.ipLimit.enabled;
        var configFile = Files.createTempFile("easyauth-offline-admission-", ".json");
        var ledgerFile = Files.createTempFile("easyauth-offline-admission-", ".sqlite");
        String suffix = UUID.randomUUID().toString().substring(0, 7);
        String rawName = "Gate" + suffix;
        String otherName = "Peer" + suffix;
        UUID uuid = UUIDUtil.createOfflinePlayerUUID(rawName);
        ServerPlayer online = null;
        try (IdentityStore ledger = new IdentityStore(ledgerFile)) {
            ledgerField.set(null, ledger);
            Files.writeString(configFile, "{\"configVersion\":1,\"offlineNamePrefix\":\"[OFF]\",\"sources\":[]}");
            snapshotField.set(null, SourcesConfig.load(configFile));
            server.setUsesAuthentication(false);
            EasyAuth.extendedConfig.preventAnotherLocationKick = true;
            EasyAuth.extendedConfig.ipLimit.enabled = false;
            PlayerEntryV1 entry = new PlayerEntryV1(rawName, uuid);
            entry.onlineAccount = PlayerEntryV1.OnlineAccount.FALSE;
            entry.password = "existing-password-fixture";
            EasyAuth.DB.registerUser(entry);
            GameProfile raw = new GameProfile(uuid, rawName);
            online = new ServerPlayer(server, context.getLevel(), raw, ClientInformation.createDefault());
            TestConnection transport = new TestConnection();
            online.connection = new ServerGamePacketListenerImpl(server, transport, online, CommonListenerCookie.createInitial(raw));
            PlayerAuth auth = (PlayerAuth) online;
            auth.easyAuth$setPlayerEntryV1(entry);
            auth.easyAuth$setIpAddress("127.0.0.1");
            auth.easyAuth$setAuthenticated(true);
            manager.getPlayers().add(online);

            // The existing session retains its original GameProfile when a real prefix is
            // enabled. The reconnecting identity has another name but the very same UUID.
            GameProfile mapped = MultiAuth.preparePassword(server, rawName);
            check(mapped.getName().equals("[OFF]" + rawName) && mapped.getId().equals(uuid),
                    "Admission fixture did not create a real-name upgrade of the existing UUID");
            InetSocketAddress otherAddress = new InetSocketAddress("127.0.0.2", 25565);
            InetSocketAddress sameAddress = new InetSocketAddress("127.0.0.1", 25565);
            String alreadyOnline = EasyAuth.langConfig.account.alreadyOnline.getNonTranslatable(mapped.getName()).getString();
            Component rejected = AuthEventHandler.checkCanPlayerJoinServer(mapped, manager, otherAddress);
            check(rejected != null && rejected.getString().equals(alreadyOnline),
                    "A prefix upgrade bypassed duplicate-login protection for the authenticated old-name session");
            check(AuthEventHandler.checkCanPlayerJoinServer(mapped, manager, sameAddress) == null,
                    "Same-address reconnect policy changed for the mapped account");
            check(AuthEventHandler.checkCanPlayerJoinServer(raw, manager, sameAddress) == null,
                    "Existing raw-name same-address admission policy changed");

            GameProfile wrongUuid = new GameProfile(UUID.randomUUID(), mapped.getName());
            Component invalid = AuthEventHandler.checkCanPlayerJoinServer(wrongUuid, manager, otherAddress);
            check(invalid == null || !invalid.getString().equals(alreadyOnline),
                    "A different UUID was incorrectly matched to the authenticated old-name session");
            PlayerEntryV1 other = new PlayerEntryV1(otherName, UUIDUtil.createOfflinePlayerUUID(otherName));
            other.onlineAccount = PlayerEntryV1.OnlineAccount.FALSE;
            EasyAuth.DB.registerUser(other);
            check(AuthEventHandler.checkCanPlayerJoinServer(new GameProfile(other.uuid, otherName), manager, otherAddress) == null,
                    "Unrelated offline account was blocked by another account's prefix upgrade");
            EasyAuth.extendedConfig.preventAnotherLocationKick = false;
            check(AuthEventHandler.checkCanPlayerJoinServer(mapped, manager, otherAddress) == null,
                    "Disabling duplicate-login protection no longer follows the configured policy");
        } finally {
            if (online != null) manager.getPlayers().remove(online);
            server.setUsesAuthentication(oldOnline);
            EasyAuth.extendedConfig.preventAnotherLocationKick = oldDuplicateProtection;
            EasyAuth.extendedConfig.ipLimit.enabled = oldIpLimit;
            snapshotField.set(null, oldSnapshot);
            ledgerField.set(null, oldLedger);
            if (EasyAuth.DB.getUserData(rawName) != null) EasyAuth.DB.deleteUserData(rawName);
            if (EasyAuth.DB.getUserData(otherName) != null) EasyAuth.DB.deleteUserData(otherName);
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
        @Override public java.net.SocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 25565); }
        @Override public void send(Packet<?> packet) {}
        @Override public void send(Packet<?> packet, PacketSendListener callback) {}
        @Override public void send(Packet<?> packet, PacketSendListener callback, boolean flush) {}
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    *///?}
}
