package xyz.nikitacartes.easyauth.mixin;

//? if >= 1.20.3 && < 1.20.5 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.StringReader;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.nikitacartes.easyauth.sources.MultiAuth;

@Mixin(EntitySelectorParser.class)
public abstract class EntitySelectorParserMixin {
    @WrapOperation(method = "parseNameOrUUID",
            at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/StringReader;readString()Ljava/lang/String;"))
    private String easyAuth$readAllocatedPlayerName(StringReader reader, Operation<String> original) {
        // Preserve quoted names and vanilla token parsing. Only an exact allocated external
        // identity can extend this one player-name argument; selector options are untouched.
        if (!reader.canRead() || StringReader.isQuotedStringStart(reader.peek())) return original.call(reader);
        String input = reader.getString();
        int start = reader.getCursor();
        int end = start;
        boolean needsCompatibility = false;
        while (end < input.length() && !Character.isWhitespace(input.charAt(end))) {
            if (end - start == 16) return original.call(reader);
            needsCompatibility |= !StringReader.isAllowedInUnquotedString(input.charAt(end));
            end++;
        }
        if (needsCompatibility) {
            String name = input.substring(start, end);
            if (MultiAuth.protectedName(name)) {
                reader.setCursor(end);
                return name;
            }
        }
        return original.call(reader);
    }
}
*///?} else {
public abstract class EntitySelectorParserMixin {}
//?}
