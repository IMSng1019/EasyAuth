package xyz.nikitacartes.easyauth.client;

//? if fabric && >= 1.20.3 && < 1.20.5 {
/*import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

// An explicit local snapshot, with no network requests or changes to the roster.
public final class TabListClientDiagnostics {
    private static final Logger LOGGER = LoggerFactory.getLogger("EasyAuthClient");
    private static final int MAX_ROWS = 256;

    private TabListClientDiagnostics() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("eatabdebug")
                        .executes(context -> snapshot(context.getSource()))));
    }

    private static int snapshot(FabricClientCommandSource source) {
        var client = source.getClient();
        var listener = client.getConnection();
        if (listener == null || client.player == null) {
            source.sendError(Component.literal("[EasyAuth] Join a server before capturing TAB state."));
            return 0;
        }
        List<PlayerInfo> profiles = List.copyOf(listener.getOnlinePlayers());
        List<PlayerInfo> listed = List.copyOf(listener.getListedOnlinePlayers());
        List<String> lines = new ArrayList<>();
        String viewer = client.player.getUUID().toString();
        record(lines, "[tab-client-debug] snapshot viewer=%s self_name=%s profiles=%s listed=%s encrypted=%s",
                viewer, quoted(client.player.getGameProfile().getName()), profiles.size(), listed.size(),
                listener.getConnection().isEncrypted());
        for (PlayerInfo info : profiles.stream().limit(MAX_ROWS).toList()) {
            var team = info.getTeam();
            record(lines, "[tab-client-debug] viewer=%s profile=%s name=%s listed_object=%s tab_name=%s vanilla_name=%s team=%s prefix=%s suffix=%s mode=%s latency=%s",
                    viewer, info.getProfile().getId(), quoted(info.getProfile().getName()), listed.contains(info),
                    info.getTabListDisplayName() == null ? "null" : quoted(info.getTabListDisplayName().getString()),
                    quoted(client.gui.getTabList().getNameForDisplay(info).getString()),
                    team == null ? "null" : quoted(team.getName()),
                    team == null ? "null" : quoted(team.getPlayerPrefix().getString()),
                    team == null ? "null" : quoted(team.getPlayerSuffix().getString()),
                    info.getGameMode(), info.getLatency());
        }
        // Track the actual listed objects as well, so a stale set entry cannot be
        // mistaken for a healthy map entry merely because both have the same UUID.
        for (PlayerInfo info : listed.stream().limit(MAX_ROWS).toList()) {
            record(lines, "[tab-client-debug] viewer=%s listed_profile=%s mapped_object=%s vanilla_name=%s",
                    viewer, info.getProfile().getId(), listener.getPlayerInfo(info.getProfile().getId()) == info,
                    quoted(client.gui.getTabList().getNameForDisplay(info).getString()));
        }
        record(lines, "[tab-client-debug] snapshot_end viewer=%s truncated=%s", viewer,
                profiles.size() > MAX_ROWS || listed.size() > MAX_ROWS);
        String output = "logs/latest.log";
        try {
            var directory = Files.createDirectories(client.gameDirectory.toPath().resolve("logs"));
            // Several clients may share gameDir and overwrite latest.log. Allocate a
            // unique snapshot so each viewer's evidence survives those concurrent writes.
            var snapshot = Files.createTempFile(directory, "easyauth-tab-" + viewer + "-", ".log");
            Files.write(snapshot, lines, StandardCharsets.UTF_8);
            output = "logs/" + snapshot.getFileName();
            LOGGER.info("[tab-client-debug] snapshot_file={}", snapshot.toAbsolutePath());
        } catch (IOException failure) {
            LOGGER.warn("[tab-client-debug] Could not write a separate snapshot file; snapshot is in latest.log");
        }
        source.sendFeedback(Component.literal("[EasyAuth] TAB snapshot: " + profiles.size() + " profiles, "
                + listed.size() + " listed. Saved to " + output));
        return 1;
    }

    private static void record(List<String> lines, String format, Object... arguments) {
        String line = format.formatted(arguments);
        lines.add(line);
        LOGGER.info(line);
    }

    private static String quoted(String text) {
        if (text == null) return "null";
        String limited = text.length() > 256 ? text.substring(0, 256) + "..." : text;
        return "\"" + limited.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
                .replaceAll("[\\p{Cntrl}]", "_") + "\"";
    }
}
*///?} else {
public final class TabListClientDiagnostics { private TabListClientDiagnostics() {} }
//?}
