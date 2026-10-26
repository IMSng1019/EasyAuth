package xyz.nikitacartes.easyauth.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.nikitacartes.easyauth.integrations.SourceNameDisplay;

@Mixin(Player.class)
public abstract class PlayerDisplayNameMixin {
    @ModifyReturnValue(method = "getDisplayName()Lnet/minecraft/network/chat/Component;", at = @At("RETURN"))
    private Component easyAuth$sourceChatName(Component original) {
        return (Object) this instanceof ServerPlayer player ? SourceNameDisplay.chatName(player, original) : original;
    }
}
