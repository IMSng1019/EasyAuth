package xyz.nikitacartes.easyauth.mixin;

//? if >= 1.20.3 && < 1.20.5 {
/*import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.nikitacartes.easyauth.diagnostics.TabListDiagnostics;

@Mixin(PacketEncoder.class)
public abstract class TabListEncoderDiagnosticsMixin {
    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At("RETURN"))
    private void easyAuth$observeEncodedTab(ChannelHandlerContext context, Packet<?> packet, ByteBuf bytes, CallbackInfo ci) {
        if (!TabListDiagnostics.active()) return;
        if (!(packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
                || packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket)) return;
        try {
            // Read an independent view of the bytes actually produced by the encoder.
            // Serializers can rewrite values without modifying the original packet.
            FriendlyByteBuf copy = new FriendlyByteBuf(bytes.duplicate());
            var codec = context.channel().attr(Connection.ATTRIBUTE_CLIENTBOUND_PROTOCOL).get();
            Packet<?> encoded = codec.createPacket(copy.readVarInt(), copy);
            if (copy.isReadable() || !(encoded instanceof net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
                    || encoded instanceof net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket)) {
                TabListDiagnostics.encodingObservationFailed();
                return;
            }
            TabListDiagnostics.observe(context.pipeline().get(Connection.class), encoded, true);
        } catch (RuntimeException observationFailure) {
            TabListDiagnostics.encodingObservationFailed();
        }
    }
}
*///?} else {
public abstract class TabListEncoderDiagnosticsMixin {}
//?}
