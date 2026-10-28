package xyz.nikitacartes.easyauth.diagnostics;

//? if >= 1.21.5 {
import net.fabricmc.fabric.api.gametest.v1.GameTest;
//?} else {
/*import net.minecraft.gametest.framework.GameTest;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
*///?}
import net.minecraft.gametest.framework.GameTestHelper;

//? if >= 1.20.3 && < 1.20.5 {
/*import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import xyz.nikitacartes.easyauth.interfaces.PlayerAuth;
import xyz.nikitacartes.easyauth.sources.AuthenticationChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
*///?}

//? if >= 1.21.5 {
public class CombatDiagnosticsRuntimeTest {
    @GameTest
//?} else {
/*public class CombatDiagnosticsRuntimeTest implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE)
*///?}
    public void combatLoggingIsOptInAndPreservesIncomingAttacks(GameTestHelper context) {
        //? if >= 1.20.3 && < 1.20.5 {
        /*String property = "easyauth.debugCombat";
        String oldProperty = System.getProperty(property);
        var server = context.getLevel().getServer();
        boolean oldPvp = server.isPvpAllowed();
        Logger logger = (Logger) LogManager.getLogger("EasyAuth");
        CaptureAppender capture = new CaptureAppender();
        ServerPlayer attacker = null;
        ServerPlayer target = null;
        capture.start();
        logger.addAppender(capture);
        try {
            System.clearProperty(property);
            server.setPvpAllowed(true);
            String suffix = UUID.randomUUID().toString().substring(0, 6);
            attacker = player(context, "Diag" + suffix, false);
            target = player(context, "[MUA]Diag" + suffix, true);
            attacker.setPos(context.absoluteVec(new Vec3(1, 2, 1)));
            target.setPos(context.absoluteVec(new Vec3(3, 2, 1)));
            context.getLevel().addNewPlayer(attacker);
            context.getLevel().addNewPlayer(target);
            check(context.getLevel().getEntity(target.getId()) == target, "Attack target was not registered in the world");
            tickWithoutMovement(attacker, target, 61);

            float before = target.getHealth();
            attacker.connection.handleInteract(ServerboundInteractPacket.createAttackPacket(target, false));
            float damageWithoutLogging = before - target.getHealth();
            check(damageWithoutLogging > 0, "Baseline incoming attack did not damage the target");
            check(capture.messages.isEmpty(), "Combat diagnostics logged without its opt-in property");

            tickWithoutMovement(attacker, target, 20);
            System.setProperty(property, "true");
            before = target.getHealth();
            attacker.connection.handleInteract(ServerboundInteractPacket.createAttackPacket(target, false));
            check(before - target.getHealth() == damageWithoutLogging, "Enabling diagnostics changed incoming attack damage");
            check(((PlayerAuth) target).easyAuth$isAuthenticated(), "Diagnostics changed target authentication");
            int targetEntityId = target.getId();
            check(capture.messages.stream().anyMatch(message -> message.contains("stage=attack")
                            && message.contains("entity_id=" + targetEntityId)),
                    "Enabled diagnostics did not record the incoming attack");
            check(capture.messages.stream().anyMatch(message -> message.contains("stage=hurt")
                            && message.contains("accepted=true") && message.contains("health_before=")
                            && message.contains("health_after=")),
                    "Enabled diagnostics did not record the vanilla hurt result");
            check(capture.messages.stream().filter(message -> message.contains("stage=attack")).count() == 1
                            && capture.messages.stream().filter(message -> message.contains("stage=hurt")).count() == 1,
                    "One incoming attack produced duplicate combat diagnostics");
            check(capture.messages.stream().anyMatch(message -> message.contains("authenticated=true")
                            && message.contains("external=true") && message.contains("cooldown=")
                            && message.contains("tick=") && message.contains("distance_sq=")),
                    "Attack diagnostics omitted the mapped target state");
            check(capture.messages.stream().noneMatch(message -> message.contains("private-password-marker")
                            || message.contains("private-address-marker")),
                    "Combat diagnostics exposed unrelated account data");
            before = target.getHealth();
            attacker.connection.handleInteract(ServerboundInteractPacket.createAttackPacket(target, false));
            check(target.getHealth() == before, "Diagnostics bypassed vanilla damage cooldown");
            check(capture.messages.stream().anyMatch(message -> message.contains("stage=hurt")
                            && message.contains("accepted=false")), "Rejected vanilla hurt result was not logged");

            System.clearProperty(property);
            int recorded = capture.messages.size();
            tickWithoutMovement(attacker, target, 20);
            attacker.connection.handleInteract(ServerboundInteractPacket.createAttackPacket(target, false));
            check(capture.messages.size() == recorded, "Disabling diagnostics did not stop combat logging");

            // The same packet must still be diagnosable after its entity disappears. Repeating
            // missing-target packets also checks the process-wide logging cap without damage.
            var missingTargetPacket = ServerboundInteractPacket.createAttackPacket(target, false);
            context.getLevel().removePlayerImmediately(target, Entity.RemovalReason.DISCARDED);
            System.setProperty(property, "true");
            attacker.connection.handleInteract(missingTargetPacket);
            check(capture.messages.stream().anyMatch(message -> message.contains("stage=attack")
                            && message.contains("target=missing")), "Missing attack target was not logged");
            for (int i = 0; i < 1005; i++) attacker.connection.handleInteract(missingTargetPacket);
            check(capture.messages.stream().filter(message -> message.contains("stage=")).count() == 1000,
                    "Combat diagnostics did not enforce its 1000-event process limit");
            check(capture.messages.stream().filter(message -> message.contains("limit=1000")).count() == 1,
                    "Combat diagnostic limit notice was missing or repeated");
        } finally {
            if (attacker != null) context.getLevel().removePlayerImmediately(attacker, Entity.RemovalReason.DISCARDED);
            if (target != null && !target.isRemoved()) context.getLevel().removePlayerImmediately(target, Entity.RemovalReason.DISCARDED);
            if (oldProperty == null) System.clearProperty(property);
            else System.setProperty(property, oldProperty);
            server.setPvpAllowed(oldPvp);
            logger.removeAppender(capture);
            capture.stop();
        }
        *///?}
        context.succeed();
    }

    //? if >= 1.20.3 && < 1.20.5 {
    /*private static ServerPlayer player(GameTestHelper context, String name, boolean external) {
        var server = context.getLevel().getServer();
        var profile = new GameProfile(UUID.randomUUID(), name);
        ServerPlayer player = new ServerPlayer(server, context.getLevel(), profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override public void setListener(PacketListener listener) {}
            @Override public boolean isConnected() { return true; }
            @Override public java.net.SocketAddress getRemoteAddress() {
                return new java.net.InetSocketAddress("127.0.0.1", 25565);
            }
            @Override public void send(Packet<?> packet) {}
            @Override public void send(Packet<?> packet, PacketSendListener listener) {}
            @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {}
        };
        player.connection = new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile));
        PlayerAuth auth = (PlayerAuth) player;
        auth.easyAuth$setExternalAuthentication(external, external);
        auth.easyAuth$setAuthenticationSource(external ? AuthenticationChannel.UNION : AuthenticationChannel.OFFICIAL, "diagnostic-fixture");
        auth.easyAuth$getPlayerEntryV1().password = "private-password-marker";
        auth.easyAuth$setIpAddress("private-address-marker");
        auth.easyAuth$setAuthenticated(true);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static void tickWithoutMovement(ServerPlayer attacker, ServerPlayer target, int ticks) {
        for (int i = 0; i < ticks; i++) {
            attacker.serverLevel().tickNonPassenger(attacker);
            attacker.connection.tick();
            target.serverLevel().tickNonPassenger(target);
            target.connection.tick();
        }
    }

    private static final class CaptureAppender extends AbstractAppender {
        final List<String> messages = new ArrayList<>();

        CaptureAppender() {
            super("EasyAuthCombatTest", null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY);
        }

        @Override public void append(LogEvent event) {
            String message = event.getMessage().getFormattedMessage();
            if (message.contains("[combat-debug]")) messages.add(message);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    *///?}
}
