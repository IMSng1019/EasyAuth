package xyz.nikitacartes.easyauth.mixin;

//? if >= 1.20.3 && < 1.20.5 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.arguments.EntityArgument;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.nikitacartes.easyauth.integrations.ExternalNameSuggestions;
import java.util.concurrent.CompletableFuture;

@Mixin(EntityArgument.class)
public abstract class EntityArgumentSuggestionsMixin {
    // This consumer is used only for player names/selected entities. The parser's selector
    // names, options and coordinates keep their existing suggestion providers.
    @WrapOperation(method = "method_9311",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/commands/SharedSuggestionProvider;suggest(Ljava/lang/Iterable;Lcom/mojang/brigadier/suggestion/SuggestionsBuilder;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Suggestions> easyAuth$quoteAllocatedNames(Iterable<String> candidates,
            SuggestionsBuilder builder, Operation<CompletableFuture<Suggestions>> original) {
        return original.call(ExternalNameSuggestions.addQuotedNames(candidates, builder), builder);
    }
}
*///?} else {
public abstract class EntityArgumentSuggestionsMixin {}
//?}
