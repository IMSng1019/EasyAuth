package xyz.nikitacartes.easyauth.integrations;

//? if >= 1.20.3 && < 1.20.5 {
/*import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.SharedSuggestionProvider;
import xyz.nikitacartes.easyauth.sources.MultiAuth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ExternalNameSuggestions {
    private ExternalNameSuggestions() {}

    public static Iterable<String> addQuotedNames(Iterable<String> candidates, SuggestionsBuilder builder) {
        List<String> unchanged = new ArrayList<>();
        String remaining = builder.getRemainingLowerCase();
        for (String name : candidates) {
            String quoted = StringArgumentType.escapeIfRequired(name);
            if (quoted.equals(name) || !MultiAuth.protectedName(name)) {
                unchanged.add(name);
                continue;
            }
            // Work only with the candidates vanilla supplied: never add offline ledger names.
            // Replace the whole argument even if the user has already entered an opening quote.
            String comparable = remaining.startsWith("'")
                    ? "'" + name.replace("\\", "\\\\").replace("'", "\\'") + "'" : quoted;
            boolean matches = remaining.startsWith("\"") || remaining.startsWith("'")
                    ? comparable.toLowerCase(Locale.ROOT).startsWith(remaining)
                    : SharedSuggestionProvider.matchesSubStr(remaining, name.toLowerCase(Locale.ROOT));
            if (matches) builder.suggest(quoted);
        }
        return unchanged;
    }
}
*///?} else {
public final class ExternalNameSuggestions { private ExternalNameSuggestions() {} }
//?}
