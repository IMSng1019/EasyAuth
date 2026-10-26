package xyz.nikitacartes.easyauth.integrations;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import xyz.nikitacartes.easyauth.interfaces.PlayerAuth;
import xyz.nikitacartes.easyauth.sources.MultiAuth;
import xyz.nikitacartes.easyauth.sources.AuthenticationChannel;
import xyz.nikitacartes.easyauth.sources.SourcesConfig;
import xyz.nikitacartes.easyauth.utils.StoneCutterUtils;

import java.util.EnumSet;
import java.util.Collection;
import java.util.List;

/** Chat/TAB presentation only: no GameProfile, scoreboard membership or identity changes. */
public final class SourceNameDisplay {
    private SourceNameDisplay() {}

    public static String prefix(ServerPlayer player) {
        SourcesConfig config = MultiAuth.snapshot();
        if (config == null) return "";
        PlayerAuth auth = (PlayerAuth) player;
        AuthenticationChannel channel = auth.easyAuth$getAuthenticationChannel();
        if (channel == null) return ""; // A display hook may run before ServerPlayer fields initialize.
        String sourceId = auth.easyAuth$getAuthenticationSourceId();
        return switch (channel) {
            case FLOODGATE -> config.floodgateNamePrefix();
            case OFFLINE -> config.offlineNamePrefix();
            case OFFICIAL -> sourceId == null ? config.officialNamePrefix() : config.namePrefix(sourceId);
            case UNION, SKIN_SITE -> sourceId == null ? "" : config.namePrefix(sourceId);
        };
    }

    public static Component chatName(ServerPlayer player, Component original) {
        String prefix = prefix(player);
        if (prefix.isEmpty() || original == null) return original;
        return Component.literal(prefix).append(original.copy());
    }

    public static Component tabName(ServerPlayer player, Component original) {
        String prefix = prefix(player);
        if (prefix.isEmpty()) return original;
        // getDisplayName() already carries the chat prefix. Build only the original team name
        // for vanilla's null TAB value, preserving ranks without applying our prefix twice.
        Component base = original != null ? original : PlayerTeam.formatNameForTeam(player.getTeam(),
                Component.literal(StoneCutterUtils.getName(player.getGameProfile())));
        return Component.literal(prefix).append(base.copy());
    }

    public static void refresh(MinecraftServer server) {
        if (server.getPlayerList() == null) return;
        refresh(server, server.getPlayerList().getPlayers());
    }

    public static void refresh(MinecraftServer server, Collection<ServerPlayer> affectedPlayers) {
        if (server.getPlayerList() == null) return;
        List<ServerPlayer> players = List.copyOf(affectedPlayers);
        if (players.isEmpty()) return;
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), players));
    }
}
