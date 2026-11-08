package xyz.nikitacartes.easyauth.mixin;

//? if >= 1.20.3 && < 1.20.5 {
/*import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.nikitacartes.easyauth.diagnostics.TabListDiagnostics;

@Mixin(Connection.class)
public abstract class TabListConnectionDiagnosticsMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V", at = @At("HEAD"))
    private void easyAuth$observeTabQueue(Packet<?> packet, PacketSendListener callback, boolean flush, CallbackInfo ci) {
        TabListDiagnostics.observe((Connection) (Object) this, packet, false);
    }
}
*///?} else {
public abstract class TabListConnectionDiagnosticsMixin {}
//?}
