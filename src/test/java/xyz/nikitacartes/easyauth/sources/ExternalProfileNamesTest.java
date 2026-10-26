package xyz.nikitacartes.easyauth.sources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExternalProfileNamesTest {
    @Test void trimsPrefixEdgesButRetainsPrintableBracketNamespace() {
        assertEquals("[MUA]", ExternalProfileNames.normalizePrefix(" [MUA] "));
        assertEquals("[MUA]LXW1019", ExternalProfileNames.localName("[MUA] ", "LXW1019"));
        assertEquals("Alice", ExternalProfileNames.localName("  ", "Alice"));
    }

    @Test void validatesTheFullLocalNameInsteadOfTruncatingIt() {
        assertEquals("[M]abcdefghijklm", ExternalProfileNames.localName("[M]", "abcdefghijklm"));
        assertThrows(IllegalArgumentException.class, () -> ExternalProfileNames.localName("[MUA]", "abcdefghijklmnop"));
        assertThrows(IllegalArgumentException.class, () -> ExternalProfileNames.localName("", ""));
        assertThrows(IllegalArgumentException.class, () -> ExternalProfileNames.localName("", "Ali ce"));
        assertThrows(IllegalArgumentException.class, () -> ExternalProfileNames.localName("", "玩家"));
    }

    @Test void prefixValidationMatchesMinecraftPrintableAscii() {
        assertEquals("", ExternalProfileNames.normalizePrefix(""));
        assertEquals("a".repeat(15), ExternalProfileNames.normalizePrefix("a".repeat(15)));
        for (String invalid : new String[]{"a".repeat(16), "[高校]", "[M UA]", "M\tUA", "M\u007fUA"}) {
            assertThrows(IllegalArgumentException.class, () -> ExternalProfileNames.normalizePrefix(invalid));
        }
    }
}
