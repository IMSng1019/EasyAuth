package xyz.nikitacartes.easyauth.diagnostics;

//? if >= 1.20.3 && < 1.20.5 {
/*import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import xyz.nikitacartes.easyauth.interfaces.PlayerAuth;
import xyz.nikitacartes.easyauth.utils.EasyLogger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

// Optional observations only: never changes authentication, damage, movement or packet handling.
public final class CombatDiagnostics {
    private static final int LIMIT = 1000;
    private static final AtomicInteger EVENTS = new AtomicInteger();

    private CombatDiagnostics() {}

    private static boolean enabled() {
        return Boolean.getBoolean("easyauth.debugCombat") && EVENTS.get() <= LIMIT;
    }

    public static void observeAttack(ServerPlayer attacker, ServerboundInteractPacket packet) {
        try {
            if (!enabled()) return;
            // dispatch only selects one of these observers. It does not invoke vanilla actions.
            packet.dispatch(new ServerboundInteractPacket.Handler() {
                @Override public void onInteraction(InteractionHand hand) {}
                @Override public void onInteraction(InteractionHand hand, Vec3 location) {}

                @Override public void onAttack() {
                    emit(() -> {
                        Entity target = packet.getTarget(attacker.serverLevel());
                        String distance = target == null ? "unavailable"
                                : Double.toString(target.getBoundingBox().distanceToSqr(attacker.getEyePosition()));
                        return "stage=attack server_tick=" + attacker.server.getTickCount()
                                + " attacker=" + describe(attacker)
                                + " target=" + describe(target)
                                + " distance_sq=" + distance + " pvp=" + attacker.server.isPvpAllowed();
                    });
                }
            });
        } catch (RuntimeException ignored) {
            // A diagnostic getter, property lookup or logger must not interrupt an incoming packet.
        }
    }

    public static HurtSnapshot beforeHurt(ServerPlayer target, DamageSource source) {
        try {
            if (!enabled() || !target.server.isSameThread()
                    || !(source.getEntity() instanceof ServerPlayer attacker)) return null;
            return new HurtSnapshot(target.getHealth(), target.invulnerableTime,
                    describe(attacker), describe(target));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static void afterHurt(ServerPlayer target, DamageSource source, float amount,
                                 HurtSnapshot before, boolean accepted) {
        if (before == null) return;
        try {
            emit(() -> "stage=hurt server_tick=" + target.server.getTickCount()
                    + " attacker=" + before.attacker() + " target=" + before.target()
                    + " source=" + source.getMsgId() + " amount=" + amount + " accepted=" + accepted
                    + " health_before=" + before.health() + " health_after=" + target.getHealth()
                    + " cooldown_before=" + before.cooldown() + " cooldown_after=" + target.invulnerableTime
                    + " pvp=" + target.server.isPvpAllowed());
        } catch (RuntimeException ignored) {
            // In particular, a logging failure must not change the original hurt return value.
        }
    }

    private static String describe(Entity entity) {
        if (entity == null) return "missing";
        String name = entity instanceof ServerPlayer player ? player.getGameProfile().getName()
                : entity.getType().toShortString();
        String state = "{entity_id=" + entity.getId() + " name=\"" + safeName(name) + "\" uuid=" + entity.getUUID()
                + " pos=(" + entity.getX() + "," + entity.getY() + "," + entity.getZ() + ")"
                + " dimension=" + entity.level().dimension().location()
                + " tick=" + entity.tickCount + " cooldown=" + entity.invulnerableTime;
        if (entity instanceof ServerPlayer player) {
            PlayerAuth auth = (PlayerAuth) player;
            state += " authenticated=" + auth.easyAuth$isAuthenticated()
                    + " external=" + auth.easyAuth$isExternalAuthenticated()
                    + " game_mode=" + player.gameMode.getGameModeForPlayer()
                    + " changing_dimension=" + player.isChangingDimension();
        }
        return state + "}";
    }

    private static String safeName(String name) {
        return name.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace('\r', '_').replace('\n', '_').replace('\t', '_');
    }

    private static void emit(Supplier<String> message) {
        if (!enabled()) return;
        // Saturate at LIMIT + 1: exactly LIMIT observations and one suppression notice per process.
        int index;
        do {
            index = EVENTS.get();
            if (index > LIMIT) return;
        } while (!EVENTS.compareAndSet(index, index + 1));
        if (index < LIMIT) {
            EasyLogger.LogInfo("[combat-debug] " + message.get());
        } else {
            EasyLogger.LogInfo("[combat-debug] limit=1000 reached; further combat diagnostics suppressed until restart");
        }
    }

    public record HurtSnapshot(float health, int cooldown, String attacker, String target) {}
}
*///?}
