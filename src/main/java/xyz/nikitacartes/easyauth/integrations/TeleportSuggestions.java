package xyz.nikitacartes.easyauth.integrations;

//? if >= 1.20.3 && < 1.20.5 {
/*import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import java.util.Map;

public final class TeleportSuggestions {
    private TeleportSuggestions() {}

    public static boolean isVanillaTeleport(CommandNode<CommandSourceStack> parent, CommandSourceStack source) {
        if (parent == null || parent != source.getServer().getCommands().getDispatcher().getRoot().getChild("teleport")
                || parent.getChildren().size() != 3) return false;
        CommandNode<CommandSourceStack> destination = parent.getChild("destination");
        CommandNode<CommandSourceStack> targets = parent.getChild("targets");
        return plainEntity(destination, "single") && plainEntity(targets, "multiple")
                && destination.getChildren().isEmpty() && destination.getCommand() != null
                && targets.getCommand() == null && targets.getChildren().size() == 2
                && plainEntity(targets.getChild("destination"), "single")
                && plainCoordinates(parent.getChild("location")) && plainCoordinates(targets.getChild("location"))
                && parent.canUse(source) && destination.canUse(source) && targets.canUse(source);
    }

    public static ParseResults<CommandSourceStack> forCompletion(CommandDispatcher<CommandSourceStack> dispatcher,
                                                                ParseResults<CommandSourceStack> parsed) {
        var last = parsed.getContext().getLastChild();
        var nodes = last.getNodes();
        CommandNode<CommandSourceStack> teleport = dispatcher.getRoot().getChild("teleport");
        if (nodes.isEmpty() || !isVanillaTeleport(teleport, last.getSource())) return parsed;
        var argument = nodes.get(nodes.size() - 1);
        String input = parsed.getReader().getString();
        int end = argument.getRange().getEnd();
        if (argument.getNode() != teleport.getChild("destination") || end >= input.length() || input.charAt(end) != ' ') {
            return parsed;
        }
        // Brigadier can prefer the executable single-destination leaf when the second
        // argument is empty or incomplete. Rebuild only the suggestion context using the
        // original targets parser; execution continues to use the original parse/tree.
        StringReader reader = new StringReader(input);
        reader.setCursor(argument.getRange().getStart());
        var context = new CommandContextBuilder<>(dispatcher, last.getSource(), teleport, reader.getCursor());
        try {
            teleport.getChild("targets").parse(reader, context);
            return new ParseResults<>(context, reader, Map.of());
        } catch (CommandSyntaxException invalidTarget) {
            return parsed;
        }
    }

    private static boolean plainCoordinates(CommandNode<?> node) {
        return node instanceof ArgumentCommandNode<?, ?> argument && argument.getType() instanceof Vec3Argument
                && node.getRedirect() == null && argument.getCustomSuggestions() == null;
    }

    private static boolean plainEntity(CommandNode<?> node, String amount) {
        if (!(node instanceof ArgumentCommandNode<?, ?> argument) || !(argument.getType() instanceof EntityArgument entity)
                || node.getRedirect() != null || argument.getCustomSuggestions() != null) return false;
        EntityArgument.Info info = new EntityArgument.Info();
        JsonObject descriptor = new JsonObject();
        info.serializeToJson(info.unpack(entity), descriptor);
        return amount.equals(descriptor.get("amount").getAsString())
                && "entities".equals(descriptor.get("type").getAsString());
    }
}
*///?} else {
public final class TeleportSuggestions { private TeleportSuggestions() {} }
//?}
