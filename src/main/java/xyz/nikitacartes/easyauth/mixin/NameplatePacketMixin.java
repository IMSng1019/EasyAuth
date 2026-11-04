package xyz.nikitacartes.easyauth.mixin;

//? if >= 1.20.3 && < 1.20.5 {
/*import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import xyz.nikitacartes.easyauth.integrations.NameplateDisplay;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class NameplatePacketMixin {
    @WrapMethod(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V")
    private void easyAuth$projectNameplates(Packet<?> packet, PacketSendListener callback, Operation<Void> original) {
        if ((Object) this instanceof ServerGamePacketListenerImpl listener) {
            var packets = NameplateDisplay.project(listener, packet);
            for (int index = 0; index < packets.size(); index++) {
                // Send the projection directly through the wrapped method, so generated
                // cosmetic packets never become inputs to the original-team mirror.
                original.call(packets.get(index), index == packets.size() - 1 ? callback : null);
            }
        } else {
            original.call(packet, callback);
        }
    }
}
*///?} else {
public abstract class NameplatePacketMixin {}
//?}
