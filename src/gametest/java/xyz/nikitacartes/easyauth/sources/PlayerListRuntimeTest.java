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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import xyz.nikitacartes.easyauth.EasyAuth;
import xyz.nikitacartes.easyauth.interfaces.PlayerAuth;
import xyz.nikitacartes.easyauth.storage.PlayerEntryV1;
import xyz.nikitacartes.easyauth.utils.PlayersCache;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
*///?}

//? if >= 1.21.5 {
public class PlayerListRuntimeTest {
    @GameTest
//?} else {
/*public class PlayerListRuntimeTest implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
*///?}
    public void actualJoinsInitializeBothDirectionsForAllSources(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*var server = context.getLevel().getServer();
        var playerList = server.getPlayerList();
        boolean onlineMode = server.usesAuthentication();
        List<ServerPlayer> joined = new ArrayList<>();
        List<CaptureConnection> connections = new ArrayList<>();
        String suffix = UUID.randomUUID().toString().substring(0, 5);
        var proofField = MultiAuth.class.getDeclaredField("PROOFS");
        proofField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConnectionProofs<Connection> proofs = (ConnectionProofs<Connection>) proofField.get(null);
        Runnable cleanup = () -> {
            for (ServerPlayer player : joined) {
                if (playerList.getPlayers().contains(player)) playerList.remove(player);
            }
            connections.forEach(MultiAuth::forget);
            connections.forEach(connection -> connection.channel.finishAndReleaseAll());
        };
        boolean scheduled = false;
        try {
            server.setUsesAuthentication(true);
            for (AuthenticationChannel channel : List.of(AuthenticationChannel.OFFICIAL,
                    AuthenticationChannel.UNION, AuthenticationChannel.OFFLINE)) {
                String name = switch (channel) {
                    case UNION -> "[MUA]" + suffix;
                    case OFFLINE -> "[OFF]" + suffix;
                    default -> "Real" + suffix;
                };
                UUID uuid = switch (channel) {
                    case UNION -> UuidV7.generate();
                    case OFFLINE -> net.minecraft.core.UUIDUtil.createOfflinePlayerUUID("Off" + suffix);
                    default -> UUID.randomUUID();
                };
                GameProfile profile = new GameProfile(uuid, name);
                PlayerEntryV1 account = PlayersCache.getOrCreate(name);
                account.uuid = uuid;
                account.onlineAccount = channel == AuthenticationChannel.OFFICIAL
                        ? PlayerEntryV1.OnlineAccount.TRUE : PlayerEntryV1.OnlineAccount.FALSE;
                CaptureConnection connection = new CaptureConnection();
                connections.add(connection);
                if (channel != AuthenticationChannel.OFFLINE) {
                    proofs.put(connection, new ConnectionProofs.Proof(uuid, name,
                            channel == AuthenticationChannel.UNION, channel == AuthenticationChannel.UNION,
                            "tab-fixture", channel == AuthenticationChannel.UNION ? SourceType.UNION : SourceType.OFFICIAL,
                            channel == AuthenticationChannel.UNION ? "[MUA]" : ""));
                }
                ServerPlayer player = new ServerPlayer(server, context.getLevel(), profile, ClientInformation.createDefault());
                joined.add(player);
                playerList.placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile));
                check(((PlayerAuth) player).easyAuth$getAuthenticationChannel() == channel,
                        "Real join lost authentication source " + channel);
                Set<UUID> expected = new HashSet<>();
                joined.forEach(online -> expected.add(online.getUUID()));
                for (CaptureConnection viewer : connections) {
                    check(viewer.listed.containsAll(expected), "Real join omitted players from TAB: expected "
                            + expected + ", got " + viewer.listed + "; packets=" + viewer.events);
                    for (ServerPlayer target : joined) {
                        check(viewer.profiles.get(target.getUUID()).getName().equals(target.getGameProfile().getName()),
                                "Real join changed a profile name");
                    }
                }
            }
            context.startSequence()
                    .thenWaitUntil(() -> joined.forEach(player -> context.assertTrue(
                            player.serverLevel().getEntity(player.getUUID()) == player,
                            "Waiting for the actual world join, including async chunk loading")))
                    .thenIdle(3)
                    .thenExecute(() -> {
                        try {
                            Set<UUID> expected = new HashSet<>();
                            joined.forEach(player -> expected.add(player.getUUID()));
                            for (CaptureConnection viewer : connections) {
                                check(viewer.listed.containsAll(expected),
                                        "TAB membership changed after world join: " + viewer.events);
                            }
                        } finally {
                            cleanup.run();
                        }
                    }).thenSucceed();
            scheduled = true;
        } finally {
            if (!scheduled) cleanup.run();
            server.setUsesAuthentication(onlineMode);
        }
        *///?} else {
        context.succeed();
        //?}
    }

    //? if >= 1.21.5 {
    @GameTest
    //?} else {
    /*@GameTest(template = EMPTY_STRUCTURE)
    *///?}
    public void tabTraceReportsQueuedAndEncodedMembershipWithoutChangingPackets(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*var server = context.getLevel().getServer();
        var source = server.createCommandSourceStack();
        var dispatcher = server.getCommands().getDispatcher();
        check(dispatcher.getRoot().getChild("auth").getChild("tab") != null, "TAB packet tracing command is missing");
        int started = dispatcher.execute("auth tab trace 120", source);
        check(started == 1, "TAB packet tracing command is missing");
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(new net.minecraft.network.PacketEncoder(Connection.ATTRIBUTE_CLIENTBOUND_PROTOCOL), connection);
        channel.attr(Connection.ATTRIBUTE_SERVERBOUND_PROTOCOL).set(ConnectionProtocol.PLAY.codec(PacketFlow.SERVERBOUND));
        channel.attr(Connection.ATTRIBUTE_CLIENTBOUND_PROTOCOL).set(ConnectionProtocol.PLAY.codec(PacketFlow.CLIENTBOUND));
        try {
            GameProfile profile = new GameProfile(UuidV7.generate(), "[MUA]Trace");
            profile.getProperties().put("textures", new com.mojang.authlib.properties.Property("textures", "private-fixture-texture"));
            ServerPlayer player = new ServerPlayer(server, context.getLevel(), profile, ClientInformation.createDefault());
            player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(server, connection, player,
                    CommonListenerCookie.createInitial(profile));
            var describe = Class.forName("xyz.nikitacartes.easyauth.diagnostics.TabListDiagnostics")
                    .getMethod("describe", Connection.class);
            player.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
            String state = (String) describe.invoke(null, connection);
            check(state.contains("queued_profiles=1 queued_listed=1 encoded_profiles=1 encoded_listed=1"),
                    "Trace did not observe both enqueue and final encoding: " + state);
            check(!state.contains("private-fixture-texture"), "Trace leaked texture payloads");
            io.netty.buffer.ByteBuf encoded = channel.readOutbound();
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded);
            wire.readVarInt(); // Packet id added by the real PacketEncoder.
            var initialized = new ClientboundPlayerInfoUpdatePacket(wire);
            check(initialized.newEntries().get(0).profileId().equals(profile.getId())
                            && initialized.newEntries().get(0).listed(), "Tracing modified the player-info wire packet");
            encoded.release();

            // A serializer/mod can change the bytes without mutating the Packet object.
            // Exercise the injected encoder observer at its return boundary with that
            // exact situation; object-only logging would incorrectly report listed=true.
            var objectUpdate = new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED, player);
            FriendlyByteBuf changedWire = new FriendlyByteBuf(Unpooled.buffer());
            try {
                var codec = ConnectionProtocol.PLAY.codec(PacketFlow.CLIENTBOUND);
                changedWire.writeVarInt(codec.packetId(objectUpdate));
                objectUpdate.write(changedWire);
                changedWire.setBoolean(changedWire.writerIndex() - 1, false);
                int readerIndex = changedWire.readerIndex();
                int writerIndex = changedWire.writerIndex();
                var observer = java.util.Arrays.stream(net.minecraft.network.PacketEncoder.class.getDeclaredMethods())
                        .filter(method -> method.getName().contains("easyAuth$observeEncodedTab")).findFirst().orElseThrow();
                observer.setAccessible(true);
                observer.invoke(channel.pipeline().get(net.minecraft.network.PacketEncoder.class),
                        channel.pipeline().context(net.minecraft.network.PacketEncoder.class), objectUpdate, changedWire,
                        new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("encode", false));
                state = (String) describe.invoke(null, connection);
                check(state.contains("queued_profiles=1 queued_listed=1 encoded_profiles=1 encoded_listed=0"),
                        "Encoder trace reported the Packet object instead of changed wire bytes: " + state);
                check(changedWire.readerIndex() == readerIndex && changedWire.writerIndex() == writerIndex,
                        "Encoder trace consumed or changed the real output buffer");
            } finally {
                changedWire.release();
            }

            FriendlyByteBuf hidden = new FriendlyByteBuf(Unpooled.buffer());
            try {
                hidden.writeEnumSet(java.util.EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED),
                        ClientboundPlayerInfoUpdatePacket.Action.class);
                hidden.writeVarInt(1);
                hidden.writeUUID(profile.getId());
                hidden.writeBoolean(false);
                player.connection.send(new ClientboundPlayerInfoUpdatePacket(hidden));
            } finally {
                hidden.release();
            }
            state = (String) describe.invoke(null, connection);
            check(state.contains("queued_profiles=1 queued_listed=0 encoded_profiles=1 encoded_listed=0"),
                    "Trace confused an unlisted player with a removed profile: " + state);

            player.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(profile.getId())));
            state = (String) describe.invoke(null, connection);
            check(state.contains("queued_profiles=0 queued_listed=0 encoded_profiles=0 encoded_listed=0"),
                    "Trace did not apply player removal: " + state);
            check(dispatcher.execute("auth tab status", source) == 1, "TAB status command failed");
            check(dispatcher.execute("auth tab stop", source) == 1, "TAB trace stop failed");
            player.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
            check(describe.invoke(null, connection).equals(state), "Tracing continued after stop");
        } finally {
            dispatcher.execute("auth tab stop", source);
            channel.finishAndReleaseAll();
        }
        *///?}
        context.succeed();
    }

    //? if >= 1.20.3 && < 1.20.5 {
    /*private static final class CaptureConnection extends Connection {
        final Map<UUID, GameProfile> profiles = new HashMap<>();
        final Set<UUID> listed = new HashSet<>();
        final List<String> events = new ArrayList<>();
        final EmbeddedChannel channel;

        CaptureConnection() {
            super(PacketFlow.SERVERBOUND);
            channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                    if (message instanceof Packet<?> packet) capture(packet);
                    promise.setSuccess();
                }
            }, this);
            channel.attr(Connection.ATTRIBUTE_SERVERBOUND_PROTOCOL).set(ConnectionProtocol.PLAY.codec(PacketFlow.SERVERBOUND));
            channel.attr(Connection.ATTRIBUTE_CLIENTBOUND_PROTOCOL).set(ConnectionProtocol.PLAY.codec(PacketFlow.CLIENTBOUND));
        }
        @Override public java.net.SocketAddress getRemoteAddress() {
            return new java.net.InetSocketAddress("127.0.0.1", 25565);
        }
        private void capture(Packet<?> packet) {
            if (!(packet instanceof ClientboundPlayerInfoUpdatePacket || packet instanceof ClientboundPlayerInfoRemovePacket)) return;
            FriendlyByteBuf bytes = new FriendlyByteBuf(Unpooled.buffer());
            try {
                packet.write(bytes);
                if (packet instanceof ClientboundPlayerInfoUpdatePacket) {
                    var decoded = new ClientboundPlayerInfoUpdatePacket(bytes);
                    events.add(decoded.actions() + " " + decoded.entries().stream().map(entry -> entry.profileId().toString()).toList());
                    for (var entry : decoded.newEntries()) profiles.putIfAbsent(entry.profileId(), entry.profile());
                    for (var entry : decoded.entries()) {
                        if (profiles.containsKey(entry.profileId()) && decoded.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED)) {
                            if (entry.listed()) listed.add(entry.profileId());
                            else listed.remove(entry.profileId());
                        }
                    }
                } else {
                    var removed = new ClientboundPlayerInfoRemovePacket(bytes).profileIds();
                    events.add("REMOVE " + removed);
                    removed.forEach(uuid -> { profiles.remove(uuid); listed.remove(uuid); });
                }
            } finally {
                bytes.release();
            }
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    *///?}
}
