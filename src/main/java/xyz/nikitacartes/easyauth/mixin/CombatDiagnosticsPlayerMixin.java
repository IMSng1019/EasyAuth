package xyz.nikitacartes.easyauth.mixin;

//? if >= 1.20.3 && < 1.20.5 {
/*import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import xyz.nikitacartes.easyauth.diagnostics.CombatDiagnostics;

@Mixin(ServerPlayer.class)
public abstract class CombatDiagnosticsPlayerMixin {
    @WrapMethod(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z")
    private boolean easyAuth$observePlayerDamage(DamageSource source, float amount, Operation<Boolean> original) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        CombatDiagnostics.HurtSnapshot before = CombatDiagnostics.beforeHurt(player, source);
        boolean accepted = original.call(source, amount);
        CombatDiagnostics.afterHurt(player, source, amount, before, accepted);
        return accepted;
    }
}
*///?}
