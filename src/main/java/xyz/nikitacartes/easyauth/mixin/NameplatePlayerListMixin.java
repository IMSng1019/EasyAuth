package xyz.nikitacartes.easyauth.mixin;

//? if >= 1.20.3 && < 1.20.5 {
/*import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.nikitacartes.easyauth.integrations.NameplateDisplay;
import xyz.nikitacartes.easyauth.integrations.SourceNameDisplay;
import java.util.List;

@Mixin(PlayerList.class)
public abstract class NameplatePlayerListMixin {
    @Inject(method = "placeNewPlayer", at = @At("RETURN"))
    private void easyAuth$refreshJoinedNameplate(Connection connection, ServerPlayer player,
                                                CommonListenerCookie cookie, CallbackInfo ci) {
        SourceNameDisplay.refresh(player.server, List.of(player));
    }

    @Inject(method = "respawn", at = @At("RETURN"))
    private void easyAuth$refreshRespawnedNameplate(ServerPlayer previous, boolean alive,
                                                   CallbackInfoReturnable<ServerPlayer> cir) {
        ServerPlayer player = cir.getReturnValue();
        SourceNameDisplay.refresh(player.server, List.of(player));
    }

    @Inject(method = "remove", at = @At("RETURN"))
    private void easyAuth$forgetNameplateView(ServerPlayer player, CallbackInfo ci) {
        NameplateDisplay.forget(player.connection);
    }
}
*///?} else {
public abstract class NameplatePlayerListMixin {}
//?}
