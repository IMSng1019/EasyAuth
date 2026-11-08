package xyz.nikitacartes.easyauth.diagnostics;

//? if >= 1.20.3 && < 1.20.5 {
/*import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import xyz.nikitacartes.easyauth.interfaces.PlayerAuth;
import xyz.nikitacartes.easyauth.utils.EasyLogger;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

// Opt-in observations only. Never adds, removes or rewrites a player-info packet.
public final class TabListDiagnostics {
    private static final int MAX_EVENTS = 2000;
    private static final int MAX_CONNECTIONS = 128;
    private static final int MAX_PROFILES = 2048;
    private static final WeakHashMap<Connection, View> VIEWS = new WeakHashMap<>();
    private static volatile long deadline;
    private static int events;
    private static int nextConnectionId;

    private TabListDiagnostics() {}

    public static synchronized int start(CommandSourceStack source, int seconds) {
        if (seconds < 1 || seconds > 300) throw new IllegalArgumentException("Trace duration must be 1-300 seconds");
        VIEWS.clear();
        events = 0;
        nextConnectionId = 0;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        EasyLogger.LogInfo("[tab-debug] started seconds=" + seconds + " limit=" + MAX_EVENTS
                + " history=from-now; reconnect affected clients to capture initial lists");
        source.sendSystemMessage(Component.literal("[EasyAuth] TAB trace started for " + seconds
                + " seconds. Reconnect affected clients, then run /auth tab status. See [tab-debug] in the server log."));
        return 1;
    }

    public static synchronized int stop(CommandSourceStack source) {
        deadline = 0;
        source.sendSystemMessage(Component.literal("[EasyAuth] TAB trace stopped; captured status remains available."));
        return 1;
    }

    public static synchronized int status(CommandSourceStack source) {
        EasyLogger.LogInfo("[tab-debug] status active=" + active() + " events=" + events
                + " online=" + source.getServer().getPlayerList().getPlayerCount());
        for (var player : source.getServer().getPlayerList().getPlayers()) {
            PlayerAuth auth = (PlayerAuth) player;
            String observed = VIEWS.keySet().stream()
                    .filter(connection -> connection.getPacketListener() == player.connection)
                    .findFirst().map(TabListDiagnostics::describe).orElse("history=not-observed");
            EasyLogger.LogInfo("[tab-debug] player=" + safe(player.getGameProfile().getName()) + " uuid=" + player.getUUID()
                    + " source=" + auth.easyAuth$getAuthenticationChannel() + " authenticated=" + auth.easyAuth$isAuthenticated()
                    + " " + observed);
        }
        source.sendSystemMessage(Component.literal("[EasyAuth] TAB status written to [tab-debug] in the server log."
                + " Counts describe observed packets since trace start, not a client acknowledgement."));
        return 1;
    }

    public static synchronized String describe(Connection connection) {
        View view = VIEWS.get(connection);
        return view == null ? "history=not-observed" : "queued_profiles=" + view.queued.profiles.size()
                + " queued_listed=" + view.queued.listed.size() + " encoded_profiles=" + view.encoded.profiles.size()
                + " encoded_listed=" + view.encoded.listed.size() + " connection=" + view.id;
    }

    public static void observe(Connection connection, Packet<?> packet, boolean encoded) {
        if (!active() || connection == null || !(packet instanceof ClientboundPlayerInfoUpdatePacket
                || packet instanceof ClientboundPlayerInfoRemovePacket)) return;
        try {
            record(connection, packet, encoded);
        } catch (RuntimeException ignored) {
            // An observation failure must never interfere with networking.
        }
    }

    private static synchronized void record(Connection connection, Packet<?> packet, boolean encoded) {
        if (!active() || !(connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener)) return;
        if (events >= MAX_EVENTS || (!VIEWS.containsKey(connection) && VIEWS.size() >= MAX_CONNECTIONS)) {
            deadline = 0;
            EasyLogger.LogInfo("[tab-debug] trace limit reached; use /auth tab trace to start a new capture");
            return;
        }
        View view = VIEWS.computeIfAbsent(connection, ignored -> new View(++nextConnectionId));
        Roster roster = encoded ? view.encoded : view.queued;
        String viewer = safe(listener.player.getGameProfile().getName());
        String stage = encoded ? "encoded" : "queued";
        if (packet instanceof ClientboundPlayerInfoUpdatePacket info) {
            for (var entry : info.entries()) {
                if (++events > MAX_EVENTS || roster.profiles.size() >= MAX_PROFILES) {
                    deadline = 0;
                    EasyLogger.LogInfo("[tab-debug] trace limit reached");
                    break;
                }
                UUID uuid = entry.profileId();
                boolean known = roster.profiles.contains(uuid);
                if (info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) roster.profiles.add(uuid);
                if (roster.profiles.contains(uuid) && info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED)) {
                    if (entry.listed()) roster.listed.add(uuid);
                    else roster.listed.remove(uuid);
                }
                EasyLogger.LogInfo("[tab-debug] stage=" + stage + " viewer=" + viewer + " viewer_uuid=" + listener.player.getUUID()
                        + " connection=" + view.id
                        + " target=" + uuid + " actions=" + info.actions() + " known_before=" + known
                        + " listed=" + (info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED)
                        ? entry.listed() : "unchanged")
                        + (entry.profile() == null ? "" : " name=" + safe(entry.profile().getName()))
                        + (info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME)
                        ? display(entry.displayName()) : ""));
            }
        } else if (packet instanceof ClientboundPlayerInfoRemovePacket removed) {
            for (UUID uuid : removed.profileIds()) {
                if (++events > MAX_EVENTS) { deadline = 0; break; }
                roster.profiles.remove(uuid);
                roster.listed.remove(uuid);
                EasyLogger.LogInfo("[tab-debug] stage=" + stage + " viewer=" + viewer + " connection=" + view.id
                        + " target=" + uuid + " action=REMOVE");
            }
        }
    }

    public static boolean active() { return deadline != 0 && System.nanoTime() < deadline; }

    public static synchronized void encodingObservationFailed() {
        if (!active()) return;
        if (++events > MAX_EVENTS) { deadline = 0; return; }
        EasyLogger.LogWarn("[tab-debug] encoding_observation_failed; packet delivery is unchanged; encoded counts may be incomplete");
    }

    private static String display(Component component) {
        if (component == null) return " display=null";
        String text = component.getString();
        String limited = text.length() > 160 ? text.substring(0, 160) + "..." : text;
        return " display=" + (text.isBlank() ? "blank" : "text") + " display_length=" + text.length()
                + " display_text=\"" + limited.replace("\\", "\\\\").replace("\"", "\\\"")
                .replaceAll("[\\p{Cntrl}]", "_") + "\"";
    }

    private static String safe(String name) {
        return name == null ? "null" : name.replaceAll("[\\p{Cntrl}\\s]", "_");
    }

    private static final class Roster {
        final Set<UUID> profiles = new HashSet<>();
        final Set<UUID> listed = new HashSet<>();
    }

    private static final class View {
        final int id;
        final Roster queued = new Roster();
        final Roster encoded = new Roster();
        View(int id) { this.id = id; }
    }
}
*///?} else {
public final class TabListDiagnostics { private TabListDiagnostics() {} }
//?}
