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
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import xyz.nikitacartes.easyauth.event.AuthEventHandler;
import xyz.nikitacartes.easyauth.integrations.SourceNameDisplay;
import xyz.nikitacartes.easyauth.interfaces.PlayerAuth;
import xyz.nikitacartes.easyauth.storage.PlayerEntryV1;
import xyz.nikitacartes.easyauth.utils.PlayersCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
*///?}

//? if >= 1.21.5 {
public class NameplateRuntimeTest {
    @GameTest
//?} else {
/*public class NameplateRuntimeTest implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE)
*///?}
    public void offlinePrefixesReachVanillaNameplatesWithoutChangingServerTeams(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*var server = context.getLevel().getServer();
        var scoreboard = server.getScoreboard();
        var snapshotField = MultiAuth.class.getDeclaredField("current");
        snapshotField.setAccessible(true);
        Object previousSnapshot = snapshotField.get(null);
        Path configFile = Files.createTempFile("easyauth-nameplate-", ".json");
        List<TestPlayer> players = new ArrayList<>();
        List<PlayerTeam> teams = new ArrayList<>();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "[OFF] ";
        try {
            installPrefix(snapshotField, configFile, prefix);
            PlayerTeam rank = scoreboard.addPlayerTeam("rank" + suffix);
            teams.add(rank);
            rank.setPlayerPrefix(Component.literal("[Rank] "));
            rank.setPlayerSuffix(Component.literal(" ★"));
            rank.setColor(ChatFormatting.GOLD);
            rank.setAllowFriendlyFire(false);
            rank.setSeeFriendlyInvisibles(false);
            rank.setNameTagVisibility(Team.Visibility.HIDE_FOR_OTHER_TEAMS);
            rank.setCollisionRule(Team.CollisionRule.PUSH_OTHER_TEAMS);
            String npcName = "Npc" + suffix;
            scoreboard.addPlayerToTeam(npcName, rank);
            PlayerTeam other = scoreboard.addPlayerTeam("other" + suffix);
            teams.add(other);
            other.setPlayerPrefix(Component.literal("[Other] "));
            other.setColor(ChatFormatting.AQUA);
            var realTeamNames = new HashSet<>(scoreboard.getTeamNames());

            TestPlayer target = player(context, "Plate" + suffix, players);
            TestPlayer peer = player(context, "Pair" + suffix, players);
            TestPlayer external = player(context, "[MUA]" + suffix, players);
            ((PlayerAuth) external).easyAuth$setAuthenticationSource(AuthenticationChannel.UNION, "fixture");
            ((PlayerAuth) external).easyAuth$setLocalNamePrefix("[MUA]");
            TestPlayer viewer = player(context, "View" + suffix, players);
            ((PlayerAuth) viewer).easyAuth$setAuthenticationSource(AuthenticationChannel.OFFICIAL, null);
            List<ServerPlayer> visiblePlayers = List.of(target, peer, external);
            initializeViewer(viewer, visiblePlayers, scoreboard, false);
            SourceNameDisplay.refresh(server);

            assertAppearance(viewer, target, prefix, null);
            assertAppearance(viewer, peer, prefix, null);
            assertAppearance(viewer, external, "", null);
            check(scoreboard.getPlayersTeam(target.getGameProfile().getName()) == null,
                    "Nametag decoration assigned a real scoreboard team to the offline player");
            check(new HashSet<>(scoreboard.getTeamNames()).equals(realTeamNames),
                    "Nametag decoration created teams in the server scoreboard");

            // Real scoreboard mutation must remain authoritative, while wire membership is
            // projected. Applying every packet with vanilla semantics catches wrong-team
            // REMOVE packets, duplicate ADD packets and references to unknown teams.
            scoreboard.addPlayerToTeam(target.getGameProfile().getName(), rank);
            scoreboard.addPlayerToTeam(peer.getGameProfile().getName(), rank);
            assertAppearance(viewer, target, prefix, rank);
            assertAppearance(viewer, peer, prefix, rank);
            check(viewer.transport.board.getPlayersTeam(target.getGameProfile().getName())
                            == viewer.transport.board.getPlayersTeam(peer.getGameProfile().getName()),
                    "Players sharing a real team and source prefix lost their client team relationship");
            check(viewer.transport.board.getPlayersTeam(npcName).getName().equals(rank.getName()),
                    "Projection moved a non-player scoreboard member");

            rank.setPlayerPrefix(Component.literal("[VIP] "));
            rank.setPlayerSuffix(Component.literal(" ✓"));
            rank.setColor(ChatFormatting.GREEN);
            assertAppearance(viewer, target, prefix, rank);
            scoreboard.addPlayerToTeam(target.getGameProfile().getName(), other);
            assertAppearance(viewer, target, prefix, other);
            assertAppearance(viewer, peer, prefix, rank);
            scoreboard.removePlayerFromTeam(target.getGameProfile().getName(), other);
            assertAppearance(viewer, target, prefix, null);
            scoreboard.addPlayerToTeam(target.getGameProfile().getName(), other);
            assertAppearance(viewer, target, prefix, other);

            // Also exercise player info arriving before initial scoreboard packets. The
            // first viewer used vanilla's team-first order; both must settle correctly.
            TestPlayer lateViewer = player(context, "Late" + suffix, players);
            ((PlayerAuth) lateViewer).easyAuth$setAuthenticationSource(AuthenticationChannel.OFFICIAL, null);
            initializeViewer(lateViewer, visiblePlayers, scoreboard, true);
            assertAppearance(lateViewer, target, prefix, other);
            assertAppearance(lateViewer, peer, prefix, rank);
            assertAppearance(lateViewer, external, "", null);

            String changedPrefix = "[LOCAL] ";
            installPrefix(snapshotField, configFile, changedPrefix);
            SourceNameDisplay.refresh(server);
            assertAppearance(viewer, target, changedPrefix, other);
            assertAppearance(lateViewer, peer, changedPrefix, rank);
            assertAppearance(viewer, external, "", null);

            viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(target.getUUID())));
            check(!viewer.transport.profiles.containsKey(target.getUUID()), "Player-info removal was not delivered");
            PlayerTeam removedTeam = viewer.transport.board.getPlayersTeam(target.getGameProfile().getName());
            check(removedTeam == null || removedTeam.getName().equals(other.getName()),
                    "Player-info removal left the hidden player in a virtual nameplate team");
            viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(target)));
            assertAppearance(viewer, target, changedPrefix, other);

            installPrefix(snapshotField, configFile, "");
            SourceNameDisplay.refresh(server);
            assertAppearance(viewer, target, "", other);
            assertAppearance(lateViewer, peer, "", rank);
            check(viewer.transport.board.getPlayersTeam(target.getGameProfile().getName()).getName().equals(other.getName()),
                    "Clearing the prefix did not restore the real team on the client");
            check(viewer.transport.board.getPlayersTeam(peer.getGameProfile().getName()).getName().equals(rank.getName()),
                    "Clearing the prefix did not restore shared real team membership");

            installPrefix(snapshotField, configFile, prefix);
            SourceNameDisplay.refresh(server);
            scoreboard.removePlayerTeam(other);
            teams.remove(other);
            realTeamNames.remove(other.getName());
            assertAppearance(viewer, target, prefix, null);
            assertAppearance(lateViewer, target, prefix, null);
            check(scoreboard.getPlayersTeam(peer.getGameProfile().getName()) == rank
                            && scoreboard.getPlayersTeam(npcName) == rank,
                    "Projection changed authoritative team membership");
            check(new HashSet<>(scoreboard.getTeamNames()).equals(realTeamNames),
                    "Virtual nameplate teams leaked into the server scoreboard");
            for (TestPlayer account : List.of(target, peer, external)) {
                String name = account.getGameProfile().getName();
                check(account.getUUID().equals(UUIDUtil.createOfflinePlayerUUID(name)), "Projection rewrote an account UUID");
                check(viewer.transport.profiles.get(account.getUUID()).getName().equals(name),
                        "Projection rewrote the profile name sent to the client");
            }
            check(viewer.transport.teamPackets > 0 && lateViewer.transport.teamPackets > 0,
                    "Nameplate test did not observe real serialized team packets");
        } finally {
            // Stop fixture receivers before removing shared state, then restore only the
            // teams and configuration owned by this test.
            server.getPlayerList().getPlayers().removeAll(players);
            for (PlayerTeam team : teams) scoreboard.removePlayerTeam(team);
            snapshotField.set(null, previousSnapshot);
            Files.deleteIfExists(configFile);
        }
        *///?}
        context.succeed();
    }

    //? if >= 1.21.5 {
    @GameTest
    //?} else {
    /*@GameTest(template = EMPTY_STRUCTURE)
    *///?}
    public void cosmeticTeamPacketsAndNameCollisionsPreserveViewerVisibility(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*var server = context.getLevel().getServer();
        var scoreboard = server.getScoreboard();
        var snapshotField = MultiAuth.class.getDeclaredField("current");
        snapshotField.setAccessible(true);
        Object previousSnapshot = snapshotField.get(null);
        Path configFile = Files.createTempFile("easyauth-nameplate-wire-", ".json");
        List<TestPlayer> players = new ArrayList<>();
        List<PlayerTeam> teams = new ArrayList<>();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "[OFF] ";
        try {
            installPrefix(snapshotField, configFile, prefix);
            PlayerTeam realTeam = scoreboard.addPlayerTeam("truth" + suffix);
            teams.add(realTeam);
            realTeam.setPlayerPrefix(Component.literal("[Real] "));
            realTeam.setColor(ChatFormatting.GOLD);
            TestPlayer target = player(context, "Wire" + suffix, players);
            TestPlayer peer = player(context, "Pair" + suffix, players);
            TestPlayer hidden = player(context, "Hide" + suffix, players);
            TestPlayer viewer = player(context, "View" + suffix, players);
            ((PlayerAuth) viewer).easyAuth$setAuthenticationSource(AuthenticationChannel.OFFICIAL, null);
            String name = target.getGameProfile().getName();
            String peerName = peer.getGameProfile().getName();
            String hiddenName = hidden.getGameProfile().getName();
            scoreboard.addPlayerToTeam(name, realTeam);
            scoreboard.addPlayerToTeam(peerName, realTeam);
            initializeViewer(viewer, List.of(target, peer), scoreboard, false);
            assertAppearance(viewer, target, prefix, realTeam);
            SourceNameDisplay.refresh(server);
            check(!viewer.transport.profiles.containsKey(hidden.getUUID())
                            && viewer.transport.board.getPlayersTeam(hiddenName) == null,
                    "Display refresh advertised a player without an ADD_PLAYER packet");

            // Cosmetic teams from another mod exist only on this viewer's wire. Their
            // style controls the head name, while chat/TAB still use the server's real team.
            Scoreboard cosmeticBoard = new Scoreboard();
            PlayerTeam cosmetic = cosmeticBoard.addPlayerTeam("wire" + suffix);
            cosmetic.setPlayerPrefix(Component.literal("[Cosmetic] "));
            cosmetic.setPlayerSuffix(Component.literal(" ✦"));
            cosmetic.setColor(ChatFormatting.LIGHT_PURPLE);
            cosmetic.setAllowFriendlyFire(false);
            cosmetic.setCollisionRule(Team.CollisionRule.NEVER);
            String npc = "Npc" + suffix;
            cosmeticBoard.addPlayerToTeam(name, cosmetic);
            cosmeticBoard.addPlayerToTeam(npc, cosmetic);
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(cosmetic, true));
            assertWireNameplate(viewer, target, prefix, cosmetic);
            check(viewer.transport.board.getPlayersTeam(npc).getName().equals(cosmetic.getName()),
                    "Cosmetic NPC membership was projected as a player");
            cosmeticBoard.addPlayerToTeam(peerName, cosmetic);
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(cosmetic, peerName,
                    ClientboundSetPlayerTeamPacket.Action.ADD));
            assertWireNameplate(viewer, peer, prefix, cosmetic);
            cosmetic.setPlayerPrefix(Component.literal("[Changed] "));
            cosmetic.setPlayerSuffix(Component.literal(" ✓"));
            cosmetic.setColor(ChatFormatting.BLUE);
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(cosmetic, false));
            assertWireNameplate(viewer, target, prefix, cosmetic);
            cosmeticBoard.removePlayerFromTeam(name, cosmetic);
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(cosmetic, name,
                    ClientboundSetPlayerTeamPacket.Action.REMOVE));
            assertWireNameplate(viewer, target, prefix, null);
            cosmeticBoard.addPlayerToTeam(name, cosmetic);
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(cosmetic, name,
                    ClientboundSetPlayerTeamPacket.Action.ADD));
            assertWireNameplate(viewer, target, prefix, cosmetic);
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createRemovePacket(cosmetic));
            cosmeticBoard.removePlayerTeam(cosmetic);
            assertWireNameplate(viewer, target, prefix, null);
            assertWireNameplate(viewer, peer, prefix, null);
            check(scoreboard.getPlayersTeam(name) == realTeam && scoreboard.getPlayersTeam(peerName) == realTeam,
                    "Client-only cosmetic packets changed server-side team membership");
            check(target.getDisplayName().getString().equals(prefix + "[Real] " + name)
                            && viewer.transport.tabNames.get(target.getUUID()).getString().equals(prefix + "[Real] " + name),
                    "Client-only cosmetic styling changed the server chat/TAB display");
            for (String member : List.of(name, peerName)) {
                viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(realTeam, member,
                        ClientboundSetPlayerTeamPacket.Action.ADD));
            }
            assertAppearance(viewer, target, prefix, realTeam);

            // First a third-party team, then a real server team claim the exact identifier
            // already used by a virtual team. Strict client application rejects duplicate
            // team ADDs or later REMOVE packets aimed at the wrong replacement team.
            String virtualName = viewer.transport.board.getPlayersTeam(name).getName();
            PlayerTeam cosmeticCollision = cosmeticBoard.addPlayerTeam(virtualName);
            cosmeticCollision.setPlayerPrefix(Component.literal("[Collision] "));
            cosmeticBoard.addPlayerToTeam(npc, cosmeticCollision);
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(cosmeticCollision, true));
            check(!viewer.transport.board.getPlayersTeam(name).getName().equals(virtualName),
                    "Cosmetic team collision did not reallocate the virtual nameplate team");
            check(viewer.transport.board.getPlayersTeam(npc).getName().equals(virtualName),
                    "Cosmetic collision lost the incoming team's NPC member");
            assertWireNameplate(viewer, target, prefix, realTeam);
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createRemovePacket(cosmeticCollision));
            cosmeticBoard.removePlayerTeam(cosmeticCollision);
            assertWireNameplate(viewer, target, prefix, realTeam);

            String secondVirtualName = viewer.transport.board.getPlayersTeam(name).getName();
            check(scoreboard.getPlayerTeam(secondVirtualName) == null, "Collision fixture name already exists on the server");
            PlayerTeam serverCollision = scoreboard.addPlayerTeam(secondVirtualName);
            teams.add(serverCollision);
            serverCollision.setPlayerPrefix(Component.literal("[ServerCollision] "));
            scoreboard.addPlayerToTeam(npc, serverCollision);
            check(!viewer.transport.board.getPlayersTeam(name).getName().equals(secondVirtualName),
                    "Server team collision did not reallocate the virtual nameplate team");
            check(viewer.transport.board.getPlayersTeam(npc).getName().equals(secondVirtualName),
                    "Server team collision lost the actual team's NPC member");
            assertAppearance(viewer, target, prefix, realTeam);
            scoreboard.removePlayerTeam(serverCollision);
            teams.remove(serverCollision);
            assertAppearance(viewer, target, prefix, realTeam);

            // Raw team packets may already contain a hidden player's name, but EasyAuth
            // must not promote that name into a source-prefix projection without ADD_PLAYER.
            scoreboard.addPlayerToTeam(hiddenName, realTeam);
            SourceNameDisplay.refresh(server);
            check(!viewer.transport.profiles.containsKey(hidden.getUUID()), "Hidden player gained a client profile");
            check(viewer.transport.board.getPlayersTeam(hiddenName).getName().equals(realTeam.getName()),
                    "Team broadcast projected a player who was never advertised to this viewer");
            assertWireNameplate(viewer, hidden, "", realTeam);
            scoreboard.removePlayerFromTeam(hiddenName, realTeam);
            check(viewer.transport.board.getPlayersTeam(hiddenName) == null,
                    "Hidden player was re-added to a virtual team after raw membership removal");
            check(scoreboard.getPlayersTeam(name) == realTeam && scoreboard.getPlayersTeam(peerName) == realTeam,
                    "Name collision handling changed authoritative membership");
        } finally {
            server.getPlayerList().getPlayers().removeAll(players);
            for (PlayerTeam team : teams) scoreboard.removePlayerTeam(team);
            snapshotField.set(null, previousSnapshot);
            Files.deleteIfExists(configFile);
        }
        *///?}
        context.succeed();
    }

    //? if >= 1.20.3 && < 1.20.5 {
    /*private static void installPrefix(java.lang.reflect.Field snapshotField, Path configFile, String prefix) throws Exception {
        var json = new com.google.gson.JsonObject();
        json.addProperty("configVersion", 1);
        json.addProperty("offlineNamePrefix", prefix);
        json.add("sources", new com.google.gson.JsonArray());
        Files.writeString(configFile, json.toString());
        snapshotField.set(null, SourcesConfig.load(configFile));
    }

    private static TestPlayer player(GameTestHelper context, String name, List<TestPlayer> players) {
        GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
        TestPlayer player = new TestPlayer(context, profile);
        players.add(player);
        PlayerEntryV1 entry = PlayersCache.getOrCreate(name);
        entry.uuid = profile.getId();
        entry.onlineAccount = PlayerEntryV1.OnlineAccount.FALSE;
        AuthEventHandler.loadPlayerData(player, player.transport);
        check(((PlayerAuth) player).easyAuth$getAuthenticationChannel() == AuthenticationChannel.OFFLINE,
                "Offline fixture was classified as another authentication channel");
        ((PlayerAuth) player).easyAuth$setAuthenticated(true);
        context.getLevel().getServer().getPlayerList().getPlayers().add(player);
        return player;
    }

    private static void initializeViewer(TestPlayer viewer, List<ServerPlayer> players, Scoreboard scoreboard, boolean infoFirst) {
        viewer.transport.active = true;
        if (infoFirst) viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(players));
        for (PlayerTeam team : scoreboard.getPlayerTeams()) {
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
        }
        if (!infoFirst) viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(players));
    }

    private static void assertAppearance(TestPlayer viewer, ServerPlayer player, String prefix, PlayerTeam realTeam) {
        String name = player.getGameProfile().getName();
        String expected = prefix + PlayerTeam.formatNameForTeam(realTeam, Component.literal(name)).getString();
        PlayerTeam clientTeam = viewer.transport.board.getPlayersTeam(name);
        Component rendered = PlayerTeam.formatNameForTeam(clientTeam, Component.literal(name));
        check(rendered.getString().equals(expected), "Vanilla nameplate did not render exactly one source prefix: expected "
                + expected + ", got " + rendered.getString());
        check(player.getDisplayName().getString().equals(expected), "Chat display name diverged from the nameplate");
        Component tab = viewer.transport.tabNames.get(player.getUUID());
        String renderedTab = tab == null ? rendered.getString() : tab.getString();
        check(renderedTab.equals(expected), "Serialized TAB display name diverged from the nameplate");
        if (realTeam != null) {
            check(clientTeam != null && clientTeam.getColor() == realTeam.getColor(), "Nameplate lost the real team color");
            check(rendered.getStyle().getColor().equals(TextColor.fromLegacyFormat(realTeam.getColor())),
                    "Vanilla formatted name lost its real team color");
            check(clientTeam.getPlayerSuffix().getString().equals(realTeam.getPlayerSuffix().getString()),
                    "Nameplate lost the real team suffix");
            check(clientTeam.packOptions() == realTeam.packOptions()
                            && clientTeam.getNameTagVisibility() == realTeam.getNameTagVisibility()
                            && clientTeam.getCollisionRule() == realTeam.getCollisionRule(),
                    "Nameplate projection changed team options, visibility or collision rules");
        }
    }

    private static void assertWireNameplate(TestPlayer viewer, ServerPlayer player, String prefix, PlayerTeam base) {
        String name = player.getGameProfile().getName();
        PlayerTeam clientTeam = viewer.transport.board.getPlayersTeam(name);
        Component rendered = PlayerTeam.formatNameForTeam(clientTeam, Component.literal(name));
        String expected = prefix + PlayerTeam.formatNameForTeam(base, Component.literal(name)).getString();
        check(rendered.getString().equals(expected), "Client-only nameplate style mismatch: expected " + expected
                + ", got " + rendered.getString());
        if (base != null) {
            check(clientTeam != null && clientTeam.getColor() == base.getColor()
                            && clientTeam.packOptions() == base.packOptions()
                            && clientTeam.getCollisionRule() == base.getCollisionRule(),
                    "Client-only nameplate lost team color or options");
        }
    }

    private static class TestPlayer extends ServerPlayer {
        final CaptureConnection transport = new CaptureConnection();
        TestPlayer(GameTestHelper context, GameProfile profile) {
            super(context.getLevel().getServer(), context.getLevel(), profile, ClientInformation.createDefault());
            connection = new ServerGamePacketListenerImpl(context.getLevel().getServer(), transport, this,
                    CommonListenerCookie.createInitial(profile));
        }
    }

    private static final class CaptureConnection extends Connection {
        final Scoreboard board = new Scoreboard();
        final Map<UUID, GameProfile> profiles = new HashMap<>();
        final Map<UUID, Component> tabNames = new HashMap<>();
        boolean active;
        int teamPackets;

        CaptureConnection() { super(PacketFlow.SERVERBOUND); }
        @Override public void setListener(PacketListener listener) {}
        @Override public boolean isConnected() { return true; }
        @Override public java.net.SocketAddress getRemoteAddress() {
            return new java.net.InetSocketAddress("127.0.0.1", 25565);
        }
        @Override public void send(Packet<?> packet) {
            if (!active || !(packet instanceof ClientboundSetPlayerTeamPacket
                    || packet instanceof ClientboundPlayerInfoUpdatePacket || packet instanceof ClientboundPlayerInfoRemovePacket)) return;
            FriendlyByteBuf bytes = new FriendlyByteBuf(Unpooled.buffer());
            try {
                packet.write(bytes);
                if (packet instanceof ClientboundSetPlayerTeamPacket) {
                    applyTeam(new ClientboundSetPlayerTeamPacket(bytes));
                    teamPackets++;
                } else if (packet instanceof ClientboundPlayerInfoUpdatePacket) {
                    var decoded = new ClientboundPlayerInfoUpdatePacket(bytes);
                    for (var entry : decoded.entries()) {
                        if (decoded.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                            profiles.put(entry.profileId(), entry.profile());
                        }
                        if (profiles.containsKey(entry.profileId())
                                && decoded.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME)) {
                            if (entry.displayName() == null) tabNames.remove(entry.profileId());
                            else tabNames.put(entry.profileId(), entry.displayName());
                        }
                    }
                } else {
                    for (UUID uuid : new ClientboundPlayerInfoRemovePacket(bytes).profileIds()) {
                        profiles.remove(uuid);
                        tabNames.remove(uuid);
                    }
                }
                check(!bytes.isReadable(), "Clientbound fixture packet left unread bytes");
            } finally {
                bytes.release();
            }
        }
        @Override public void send(Packet<?> packet, PacketSendListener listener) { send(packet); }
        @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) { send(packet); }

        // Mirrors ClientPacketListener.handleSetPlayerTeamPacket using an independent
        // client scoreboard. Fail on states vanilla logs or throws for, instead of hiding
        // projection ordering errors by replacing teams/members wholesale.
        private void applyTeam(ClientboundSetPlayerTeamPacket packet) {
            var teamAction = packet.getTeamAction();
            PlayerTeam team;
            if (teamAction == ClientboundSetPlayerTeamPacket.Action.ADD) {
                check(board.getPlayerTeam(packet.getName()) == null, "Client received a duplicate team ADD: " + packet.getName());
                team = board.addPlayerTeam(packet.getName());
            } else {
                team = board.getPlayerTeam(packet.getName());
                check(team != null, "Client received a packet for an unknown team: " + packet.getName());
            }
            packet.getParameters().ifPresent(parameters -> {
                team.setDisplayName(parameters.getDisplayName());
                team.setColor(parameters.getColor());
                team.unpackOptions(parameters.getOptions());
                team.setNameTagVisibility(Team.Visibility.byName(parameters.getNametagVisibility()));
                team.setCollisionRule(Team.CollisionRule.byName(parameters.getCollisionRule()));
                team.setPlayerPrefix(parameters.getPlayerPrefix());
                team.setPlayerSuffix(parameters.getPlayerSuffix());
            });
            var playerAction = packet.getPlayerAction();
            if (playerAction == ClientboundSetPlayerTeamPacket.Action.ADD) {
                for (String name : packet.getPlayers()) board.addPlayerToTeam(name, team);
            } else if (playerAction == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
                for (String name : packet.getPlayers()) board.removePlayerFromTeam(name, team);
            }
            if (teamAction == ClientboundSetPlayerTeamPacket.Action.REMOVE) board.removePlayerTeam(team);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    *///?}
}
