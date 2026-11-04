package xyz.nikitacartes.easyauth.mixin;

//? if >= 1.20.3 && < 1.20.5 {
/*import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.util.Collection;
import java.util.Optional;

@Mixin(ClientboundSetPlayerTeamPacket.class)
public interface PlayerTeamPacketInvoker {
    @Invoker("<init>")
    static ClientboundSetPlayerTeamPacket easyAuth$create(String name, int method,
            Optional<ClientboundSetPlayerTeamPacket.Parameters> parameters, Collection<String> players) {
        throw new AssertionError();
    }
}
*///?} else {
public interface PlayerTeamPacketInvoker {}
//?}
