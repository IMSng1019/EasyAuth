package xyz.nikitacartes.easyauth.mixin;

//? if >= 1.20.3 && < 1.20.5 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.nikitacartes.easyauth.integrations.TeleportSuggestions;
import java.util.concurrent.CompletableFuture;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class TeleportSuggestionsMixin {
    @WrapOperation(method = "handleCustomCommandSuggestions",
            at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/CommandDispatcher;getCompletionSuggestions(Lcom/mojang/brigadier/ParseResults;)Ljava/util/concurrent/CompletableFuture;", remap = false))
    private CompletableFuture<Suggestions> easyAuth$completeTeleportArguments(CommandDispatcher<CommandSourceStack> dispatcher,
            ParseResults<CommandSourceStack> parsed, Operation<CompletableFuture<Suggestions>> original) {
        return original.call(dispatcher, TeleportSuggestions.forCompletion(dispatcher, parsed));
    }
}
*///?} else {
public abstract class TeleportSuggestionsMixin {}
//?}
