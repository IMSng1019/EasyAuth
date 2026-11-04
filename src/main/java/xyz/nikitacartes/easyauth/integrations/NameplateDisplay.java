package xyz.nikitacartes.easyauth.integrations;

//? if >= 1.20.3 && < 1.20.5 {
/*import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import xyz.nikitacartes.easyauth.mixin.PlayerTeamPacketInvoker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

// Per-connection cosmetic scoreboard projection; never edits account or server team data.
public final class NameplateDisplay {
    private static final Map<ServerGamePacketListenerImpl, View> VIEWS = new WeakHashMap<>();

    private NameplateDisplay() {}

    public static List<Packet<?>> project(ServerGamePacketListenerImpl listener, Packet<?> packet) {
        if (!(packet instanceof ClientboundSetPlayerTeamPacket)
                && !(packet instanceof ClientboundPlayerInfoUpdatePacket)
                && !(packet instanceof ClientboundPlayerInfoRemovePacket)) return List.of(packet);
        synchronized (VIEWS) {
            return VIEWS.computeIfAbsent(listener, ignored -> new View()).project(listener.player, packet);
        }
    }

    public static void forget(ServerGamePacketListenerImpl listener) {
        synchronized (VIEWS) {
            VIEWS.remove(listener);
        }
    }

    private record Group(String originalTeam, String prefix, UUID unteamedPlayer) {}

    private static final class View {
        // Mirror only packets this viewer actually received, including other mods' cosmetic
        // teams. Hidden/unadvertised profiles must never gain nameplate memberships here.
        private final Scoreboard original = new Scoreboard();
        private final Map<UUID, String> visible = new LinkedHashMap<>();
        private final Map<Group, PlayerTeam> projected = new LinkedHashMap<>();
        private long sequence;

        List<Packet<?>> project(ServerPlayer viewer, Packet<?> packet) {
            List<Packet<?>> result = new ArrayList<>();
            Set<String> previousNames = projectedNames(projected);
            if (packet instanceof ClientboundPlayerInfoUpdatePacket info
                    && info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                for (var entry : info.entries()) {
                    if (entry.profile() != null) visible.put(entry.profileId(), entry.profile().getName());
                }
            } else if (packet instanceof ClientboundPlayerInfoRemovePacket removed) {
                removed.profileIds().forEach(visible::remove);
            } else if (packet instanceof ClientboundSetPlayerTeamPacket teamPacket) {
                // A later vanilla/mod team may claim a generated name. Retire that cosmetic
                // team before the original ADD reaches the client, then allocate a fresh name.
                var iterator = projected.entrySet().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    if (entry.getValue().getName().equals(teamPacket.getName())) {
                        result.add(ClientboundSetPlayerTeamPacket.createRemovePacket(entry.getValue()));
                        iterator.remove();
                    }
                }
                acceptOriginal(teamPacket);
            }

            Map<UUID, ServerPlayer> online = new HashMap<>();
            for (ServerPlayer player : viewer.server.getPlayerList().getPlayers()) online.put(player.getUUID(), player);
            Map<Group, PlayerTeam> desired = new LinkedHashMap<>();
            visible.forEach((uuid, name) -> {
                ServerPlayer player = online.get(uuid);
                if (player == null || !player.getGameProfile().getName().equals(name)) return;
                String prefix = SourceNameDisplay.prefix(player);
                if (prefix.isEmpty()) return;
                PlayerTeam base = original.getPlayersTeam(name);
                Group group = new Group(base == null ? null : base.getName(), prefix, base == null ? uuid : null);
                PlayerTeam team = desired.computeIfAbsent(group, key -> {
                    PlayerTeam previous = projected.get(key);
                    return decorate(previous == null ? nextName() : previous.getName(), prefix, base);
                });
                team.getPlayers().add(name);
            });
            Set<String> desiredNames = projectedNames(desired);
            if (packet instanceof ClientboundSetPlayerTeamPacket teamPacket && !teamPacket.getPlayers().isEmpty()) {
                // The client has these members in virtual teams. In particular, a REMOVE
                // against the original team would throw in vanilla's client scoreboard.
                List<String> members = teamPacket.getPlayers().stream()
                        .filter(name -> !previousNames.contains(name) && !desiredNames.contains(name)).toList();
                if (members.size() != teamPacket.getPlayers().size()) {
                    packet = PlayerTeamPacketInvoker.easyAuth$create(teamPacket.getName(), method(teamPacket),
                            teamPacket.getParameters(), members);
                }
            }
            result.add(packet);

            // Remove old memberships first so all subsequent additions are unambiguous.
            projected.forEach((group, old) -> {
                PlayerTeam replacement = desired.get(group);
                if (replacement == null) {
                    result.add(ClientboundSetPlayerTeamPacket.createRemovePacket(old));
                } else {
                    for (String name : old.getPlayers()) {
                        if (!replacement.getPlayers().contains(name)) result.add(ClientboundSetPlayerTeamPacket
                                .createPlayerPacket(old, name, ClientboundSetPlayerTeamPacket.Action.REMOVE));
                    }
                }
            });
            desired.forEach((group, team) -> {
                PlayerTeam old = projected.get(group);
                if (old == null) {
                    result.add(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
                } else {
                    if (!sameStyle(old, team)) result.add(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, false));
                    for (String name : team.getPlayers()) {
                        if (!old.getPlayers().contains(name)) result.add(ClientboundSetPlayerTeamPacket
                                .createPlayerPacket(team, name, ClientboundSetPlayerTeamPacket.Action.ADD));
                    }
                }
            });
            for (String name : previousNames) {
                if (desiredNames.contains(name)) continue;
                PlayerTeam base = original.getPlayersTeam(name);
                if (base != null) result.add(ClientboundSetPlayerTeamPacket
                        .createPlayerPacket(base, name, ClientboundSetPlayerTeamPacket.Action.ADD));
            }
            projected.clear();
            projected.putAll(desired);
            return result;
        }

        private String nextName() {
            String name;
            do {
                name = "ea$" + Long.toUnsignedString(sequence++, 36);
            } while (original.getPlayerTeam(name) != null || usesName(name));
            return name;
        }

        private boolean usesName(String name) {
            return projected.values().stream().anyMatch(team -> team.getName().equals(name));
        }

        private void acceptOriginal(ClientboundSetPlayerTeamPacket packet) {
            PlayerTeam team = packet.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD
                    ? original.addPlayerTeam(packet.getName()) : original.getPlayerTeam(packet.getName());
            if (team == null) return; // Match vanilla's handling of an unknown team packet.
            packet.getParameters().ifPresent(parameters -> {
                team.setDisplayName(parameters.getDisplayName());
                team.setPlayerPrefix(parameters.getPlayerPrefix());
                team.setPlayerSuffix(parameters.getPlayerSuffix());
                team.setColor(parameters.getColor());
                team.unpackOptions(parameters.getOptions());
                Team.Visibility visibility = Team.Visibility.byName(parameters.getNametagVisibility());
                if (visibility != null) team.setNameTagVisibility(visibility);
                Team.CollisionRule collision = Team.CollisionRule.byName(parameters.getCollisionRule());
                if (collision != null) team.setCollisionRule(collision);
            });
            if (packet.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
                for (String name : packet.getPlayers()) original.addPlayerToTeam(name, team);
            } else if (packet.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
                for (String name : packet.getPlayers()) {
                    if (original.getPlayersTeam(name) == team) original.removePlayerFromTeam(name, team);
                }
            }
            if (packet.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) original.removePlayerTeam(team);
        }
    }

    private static Set<String> projectedNames(Map<Group, PlayerTeam> teams) {
        Set<String> names = new HashSet<>();
        teams.values().forEach(team -> names.addAll(team.getPlayers()));
        return names;
    }

    private static PlayerTeam decorate(String name, String prefix, PlayerTeam base) {
        PlayerTeam team = new PlayerTeam(new Scoreboard(), name);
        team.setPlayerPrefix(Component.literal(prefix).append(base == null ? Component.empty() : base.getPlayerPrefix().copy()));
        if (base != null) {
            team.setDisplayName(base.getDisplayName().copy());
            team.setPlayerSuffix(base.getPlayerSuffix().copy());
            team.setColor(base.getColor());
            team.unpackOptions(base.packOptions());
            team.setNameTagVisibility(base.getNameTagVisibility());
            team.setCollisionRule(base.getCollisionRule());
        }
        return team;
    }

    private static boolean sameStyle(PlayerTeam first, PlayerTeam second) {
        return first.getDisplayName().equals(second.getDisplayName())
                && first.getPlayerPrefix().equals(second.getPlayerPrefix())
                && first.getPlayerSuffix().equals(second.getPlayerSuffix())
                && first.getColor() == second.getColor() && first.packOptions() == second.packOptions()
                && first.getNameTagVisibility() == second.getNameTagVisibility()
                && first.getCollisionRule() == second.getCollisionRule();
    }

    private static int method(ClientboundSetPlayerTeamPacket packet) {
        if (packet.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD) return 0;
        if (packet.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) return 1;
        if (packet.getParameters().isPresent()) return 2;
        return packet.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD ? 3 : 4;
    }
}
*///?} else {
public final class NameplateDisplay { private NameplateDisplay() {} }
//?}
