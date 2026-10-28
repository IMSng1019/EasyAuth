package xyz.nikitacartes.easyauth.mixin;

//? if >= 1.20.3 && < 1.20.5 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Commands.class)
public abstract class CommandSuggestionsMixin {
    @SuppressWarnings("unchecked")
    @WrapOperation(method = "fillUsableCommands",
            at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/tree/CommandNode;createBuilder()Lcom/mojang/brigadier/builder/ArgumentBuilder;"))
    private ArgumentBuilder<?, ?> easyAuth$requestServerEntitySuggestions(CommandNode<?> node,
            Operation<ArgumentBuilder<?, ?>> original) {
        ArgumentBuilder<?, ?> builder = original.call(node);
        // Vanilla already checked this player's command permissions before creating the copy.
        // Change only the client copy, preserving any custom provider and the live dispatcher.
        if (builder instanceof RequiredArgumentBuilder<?, ?> argument
                && argument.getType() instanceof EntityArgument && argument.getSuggestionsProvider() == null) {
            ((RequiredArgumentBuilder<SharedSuggestionProvider, ?>) argument).suggests(SuggestionProviders.ASK_SERVER);
        }
        // All entity arguments use server completion so a later external login/config reload
        // works without resending everyone's command tree. Ordinary Tab use adds a request.
        return builder;
    }
}
*///?} else {
public abstract class CommandSuggestionsMixin {}
//?}
