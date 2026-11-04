package xyz.nikitacartes.easyauth.integrations;

//? if >= 1.20.3 && < 1.20.5 {
/*import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ExternalNameSuggestions {
    private ExternalNameSuggestions() {}

    public static Iterable<String> addQuotedNames(Iterable<String> candidates, SuggestionsBuilder builder,
                                                 SharedSuggestionProvider source) {
        List<String> unchanged = new ArrayList<>();
        String remaining = builder.getRemainingLowerCase();
        for (String name : candidates) {
            String quoted = StringArgumentType.escapeIfRequired(name);
            String prefix = "";
            if (source instanceof CommandSourceStack commandSource) {
                var player = commandSource.getServer().getPlayerList().getPlayerByName(name);
                if (player != null) prefix = SourceNameDisplay.prefix(player);
            }
            if (quoted.equals(name) && prefix.isEmpty()
                    && !remaining.startsWith("\"") && !remaining.startsWith("'")) {
                unchanged.add(name);
                continue;
            }
            // Only decorate vanilla's candidates, including offline-authenticated players.
            // Display prefixes are searchable labels, never part of the command target or UUID.
            String displayName = prefix + name;
            if (matches(name, remaining) || (!prefix.isEmpty() && matches(displayName, remaining))) {
                builder.suggest(quoted, prefix.isEmpty() ? null : Component.literal(displayName));
            }
        }
        return unchanged;
    }

    private static boolean matches(String name, String remaining) {
        if (remaining.startsWith("\"") || remaining.startsWith("'")) {
            String quote = remaining.substring(0, 1);
            String comparable = quote + name.replace("\\", "\\\\").replace(quote, "\\" + quote) + quote;
            return comparable.toLowerCase(Locale.ROOT).startsWith(remaining);
        }
        return SharedSuggestionProvider.matchesSubStr(remaining, name.toLowerCase(Locale.ROOT));
    }
}
*///?} else {
public final class ExternalNameSuggestions { private ExternalNameSuggestions() {} }
//?}
