package xyz.nikitacartes.easyauth.sources;

/** Local names allocated for external identities, independent of the upstream authenticated name. */
public final class ExternalProfileNames {
    private ExternalProfileNames() {}

    public static String normalizePrefix(String configuredPrefix) {
        if (configuredPrefix == null) throw new IllegalArgumentException("External namePrefix must be a string");
        String prefix = configuredPrefix.strip();
        if (prefix.length() > 15 || prefix.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw new IllegalArgumentException("External namePrefix must contain at most 15 printable ASCII characters without spaces");
        }
        return prefix;
    }

    public static String localName(String configuredPrefix, String providerName) {
        if (providerName == null) throw new IllegalArgumentException("External provider name is missing");
        String name = normalizePrefix(configuredPrefix) + providerName;
        if (name.isEmpty() || name.length() > 16 || name.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw new IllegalArgumentException("External local player name (namePrefix + provider name) must contain 1 to 16 printable ASCII characters without spaces; shorten namePrefix or the provider name");
        }
        return name;
    }
}
