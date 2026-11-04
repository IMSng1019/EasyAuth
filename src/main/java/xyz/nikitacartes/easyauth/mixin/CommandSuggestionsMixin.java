package xyz.nikitacartes.easyauth.mixin;

//? if >= 1.20.3 && < 1.20.5 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.nikitacartes.easyauth.integrations.TeleportSuggestions;
import java.util.Map;

@Mixin(Commands.class)
public abstract class CommandSuggestionsMixin {
    @SuppressWarnings("unchecked")
    @WrapOperation(method = "fillUsableCommands",
            at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/tree/CommandNode;createBuilder()Lcom/mojang/brigadier/builder/ArgumentBuilder;"))
    private ArgumentBuilder<?, ?> easyAuth$requestServerEntitySuggestions(CommandNode<?> node,
            Operation<ArgumentBuilder<?, ?>> original,
            @Local(argsOnly = true, ordinal = 0) CommandNode<CommandSourceStack> parent,
            @Local(argsOnly = true) CommandSourceStack source) {
        ArgumentBuilder<?, ?> builder = original.call(node);
        // Vanilla already checked this player's command permissions before creating the copy.
        // Change only the client copy, preserving any custom provider and the live dispatcher.
        if (builder instanceof RequiredArgumentBuilder<?, ?> argument
                && argument.getType() instanceof EntityArgument && argument.getSuggestionsProvider() == null) {
            ((RequiredArgumentBuilder<SharedSuggestionProvider, ?>) argument).suggests(SuggestionProviders.ASK_SERVER);
        }
        if (node == parent.getChild("targets") && TeleportSuggestions.isVanillaTeleport(parent, source)) {
            // The single-destination leaf also makes /tp <player> executable. Retain that
            // client syntax marker on the shared entity branch before omitting the leaf.
            builder.executes(context -> 0);
        }
        // All entity arguments use server completion so a later external login/config reload
        // works without resending everyone's command tree. Ordinary Tab use adds a request.
        return builder;
    }

    @WrapOperation(method = "fillUsableCommands",
            at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/tree/CommandNode;addChild(Lcom/mojang/brigadier/tree/CommandNode;)V"))
    private void easyAuth$shareTeleportEntityCompletion(CommandNode<?> clientParent, CommandNode<?> child,
            Operation<Void> original,
            @Local(argsOnly = true, ordinal = 0) CommandNode<CommandSourceStack> parent,
            @Local(argsOnly = true) CommandSourceStack source,
            @Local(argsOnly = true) Map<CommandNode<CommandSourceStack>, CommandNode<SharedSuggestionProvider>> copies) {
        if (TeleportSuggestions.isVanillaTeleport(parent, source) && copies.get(parent.getChild("destination")) == child) {
            // Vanilla clients have a single pending ASK_SERVER future. Two sibling entity
            // branches cancel each other's request, leaving Brigadier's merged future pending.
            // Keep the leaf in vanilla's copy map for redirects, but not as a sibling here.
            return;
        }
        original.call(clientParent, child);
    }

}
*///?} else {
public abstract class CommandSuggestionsMixin {}
//?}
