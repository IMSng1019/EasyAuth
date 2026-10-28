package xyz.nikitacartes.easyauth.sources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Validated immutable configuration. Reloaders must publish a complete new snapshot atomically. */
public final class SourcesConfig {
    public static final String OFFICIAL_HAS_JOINED = "https://sessionserver.mojang.com/session/minecraft/hasJoined";
    public static final String OFFICIAL_PROFILE = "https://sessionserver.mojang.com/session/minecraft/profile/{uuid}?unsigned=false";
    private static final String SESSION_PATH = "/sessionserver/session/minecraft/hasJoined";
    private static final String DEFAULT_JSON = """
            {
              "configVersion": 1,
              "parallelism": 4,
              "overallTimeoutMillis": 6000,
              "relaxCustomChatKeys": false,
              "newPlayerAuth": "AUTO",
              "offlineNames": [],
              "offlineNamePrefix": "",
              "floodgateNamePrefix": "",
              "sources": [
                {
                  "id": "official",
                  "type": "OFFICIAL",
                  "enabled": true,
                  "priority": 0,
                  "timeoutMillis": 2000,
                  "retries": 0,
                  "namePrefix": ""
                }
              ]
            }
            """;

    private final List<Source> sources;
    private final int parallelism;
    private final int overallTimeoutMillis;
    private final boolean relaxCustomChatKeys;
    private final NewPlayerAuth newPlayerAuth;
    private final List<Pattern> offlineNames;
    private final String offlineNamePrefix;
    private final String floodgateNamePrefix;
    private final List<NameNamespace> customNameNamespaces;

    private record NameNamespace(String sourceId, String lowerCasePrefix) { }

    /** Vanilla hello packets cannot distinguish a new offline player from a skin-site v3 player. */
    public enum NewPlayerAuth { AUTO, EXTERNAL }

    private SourcesConfig(List<Source> sources, int parallelism, int overallTimeoutMillis, boolean relaxCustomChatKeys,
                          NewPlayerAuth newPlayerAuth, List<Pattern> offlineNames,
                          String offlineNamePrefix, String floodgateNamePrefix, List<NameNamespace> customNameNamespaces) {
        this.sources = List.copyOf(sources);
        this.parallelism = parallelism;
        this.overallTimeoutMillis = overallTimeoutMillis;
        this.relaxCustomChatKeys = relaxCustomChatKeys;
        this.newPlayerAuth = newPlayerAuth;
        this.offlineNames = List.copyOf(offlineNames);
        this.offlineNamePrefix = offlineNamePrefix;
        this.floodgateNamePrefix = floodgateNamePrefix;
        this.customNameNamespaces = List.copyOf(customNameNamespaces);
    }

    public record Source(String id, SourceType type, boolean enabled, int priority,
                         URI hasJoinedUrl, String mappedUrl, String subjectIdField,
                         String createdAtField, int timeoutMillis, int retries, String namePrefix, String profileUrl) {
        public Source(String id, SourceType type, boolean enabled, int priority,
                      URI hasJoinedUrl, String mappedUrl, String subjectIdField,
                      String createdAtField, int timeoutMillis, int retries, String namePrefix) {
            this(id, type, enabled, priority, hasJoinedUrl, mappedUrl, subjectIdField,
                    createdAtField, timeoutMillis, retries, namePrefix, null);
        }
    }

    public static SourcesConfig defaults() {
        return parse(DEFAULT_JSON);
    }

    public static SourcesConfig load(Path file) throws IOException {
        if (!Files.exists(file)) {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try {
                Files.writeString(file, DEFAULT_JSON, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another initializer created the configuration; validate that actual file below.
            }
        }
        if (Files.size(file) > 1_048_576) throw new IllegalArgumentException("sources.json is larger than 1 MiB");
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid sources configuration " + file + ": " + e.getMessage(), e);
        }
    }

    private static SourcesConfig parse(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) throw invalid("root must be an object");
            JsonObject root = parsed.getAsJsonObject();
            known(root, Set.of("configVersion", "parallelism", "overallTimeoutMillis", "relaxCustomChatKeys", "newPlayerAuth",
                    "offlineNames", "offlineNamePrefix", "floodgateNamePrefix", "sources"), "root");
            if (integer(root, "configVersion", -1) != 1) throw invalid("configVersion must be 1");
            int parallelism = bounded(root, "parallelism", 4, 1, 16);
            int budget = bounded(root, "overallTimeoutMillis", 6000, 100, 30000);
            boolean relax = bool(root, "relaxCustomChatKeys", false);
            String newPolicy = optional(root, "newPlayerAuth");
            NewPlayerAuth newPlayerAuth;
            try {
                newPlayerAuth = newPolicy == null ? NewPlayerAuth.AUTO : NewPlayerAuth.valueOf(newPolicy);
            } catch (IllegalArgumentException e) {
                throw invalid("newPlayerAuth must be AUTO or EXTERNAL");
            }
            List<Pattern> offlineNames = offlineNames(root);
            String offlineNamePrefix = prefix(root, "offlineNamePrefix");
            String floodgateNamePrefix = prefix(root, "floodgateNamePrefix");
            if (!root.has("sources") || !root.get("sources").isJsonArray()) throw invalid("sources must be an array");
            JsonArray array = root.getAsJsonArray("sources");
            if (array.size() > 32) throw invalid("at most 32 sources may be configured");
            Set<String> ids = new HashSet<>();
            Set<Integer> priorities = new HashSet<>();
            List<Source> sources = new ArrayList<>();
            List<NameNamespace> namespaces = new ArrayList<>();
            int officialCount = 0;
            for (JsonElement element : array) {
                if (!element.isJsonObject()) throw invalid("each source must be an object");
                JsonObject obj = element.getAsJsonObject();
                known(obj, Set.of("id", "type", "enabled", "priority", "apiRoot", "hasJoinedUrl", "profileUrl", "mappedUrl",
                        "subjectIdField", "createdAtField", "timeoutMillis", "retries", "unionMode", "unionServers", "namePrefix"), "source");
                String id = required(obj, "id");
                if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) throw invalid("invalid source id: " + id);
                if (id.equalsIgnoreCase("offline") || id.equalsIgnoreCase("floodgate") || id.startsWith("legacy")) {
                    throw invalid("source id is reserved: " + id);
                }
                if (!ids.add(id.toLowerCase(Locale.ROOT))) throw invalid("duplicate source id: " + id);
                SourceType type;
                try {
                    type = SourceType.valueOf(required(obj, "type"));
                } catch (IllegalArgumentException e) {
                    throw invalid("source " + id + " type must be OFFICIAL, UNION or SKIN_SITE");
                }
                boolean enabled = bool(obj, "enabled", true);
                int priority = integer(obj, "priority", 0);
                if (!priorities.add(priority)) throw invalid("duplicate source priority: " + priority);
                int timeout = bounded(obj, "timeoutMillis", 1500, 100, 10000);
                int retries = bounded(obj, "retries", 0, 0, 3);
                String namePrefix = prefix(obj, "namePrefix");
                if (type != SourceType.OFFICIAL) {
                    String namespacePrefix;
                    try {
                        namespacePrefix = ExternalProfileNames.normalizePrefix(namePrefix).toLowerCase(Locale.ROOT);
                    } catch (IllegalArgumentException e) {
                        throw invalid("source " + id + " namePrefix: " + e.getMessage());
                    }
                    if (!namespacePrefix.isEmpty()) {
                        for (NameNamespace namespace : namespaces) {
                            if (namespace.lowerCasePrefix().startsWith(namespacePrefix)
                                    || namespacePrefix.startsWith(namespace.lowerCasePrefix())) {
                                throw invalid("namePrefix namespaces overlap between sources " + namespace.sourceId() + " and " + id);
                            }
                        }
                        namespaces.add(new NameNamespace(id, namespacePrefix));
                    }
                }
                String rootUrl = optional(obj, "apiRoot");
                String joinedUrl = optional(obj, "hasJoinedUrl");
                String profileUrl = optional(obj, "profileUrl");
                String mapping = optional(obj, "mappedUrl");
                String subject = optional(obj, "subjectIdField");
                String created = optional(obj, "createdAtField");
                String mode = optional(obj, "unionMode");
                if (mode == null) mode = "ALL";
                if (!Set.of("ALL", "ONLY", "EXCLUDES").contains(mode)) throw invalid("invalid unionMode for " + id);
                List<String> codes = unionCodes(obj);
                if (type != SourceType.UNION && (!mode.equals("ALL") || !codes.isEmpty())) {
                    throw invalid("unionMode/unionServers require UNION type: " + id);
                }
                if (mode.equals("ALL") && !codes.isEmpty()) throw invalid("unionServers require ONLY or EXCLUDES mode: " + id);
                if (!mode.equals("ALL") && codes.isEmpty()) throw invalid("unionServers is required for " + mode + ": " + id);
                if (type == SourceType.OFFICIAL) {
                    officialCount++;
                    if (rootUrl != null || (joinedUrl != null && !joinedUrl.equals(OFFICIAL_HAS_JOINED))
                            || (profileUrl != null && !profileUrl.equals(OFFICIAL_PROFILE))
                            || mapping != null || subject != null || created != null) {
                        throw invalid("OFFICIAL must use the fixed Mojang endpoint without a mapping: " + id);
                    }
                    joinedUrl = OFFICIAL_HAS_JOINED;
                    profileUrl = OFFICIAL_PROFILE;
                } else {
                    if ((rootUrl == null) == (joinedUrl == null)) throw invalid("exactly one of apiRoot or hasJoinedUrl is required: " + id);
                    if (rootUrl != null) {
                        validateUrl(rootUrl, false);
                        while (rootUrl.endsWith("/")) rootUrl = rootUrl.substring(0, rootUrl.length() - 1);
                        if (!mode.equals("ALL")) rootUrl += "/" + mode.toLowerCase(Locale.ROOT) + "/" + String.join("_", codes);
                        joinedUrl = rootUrl + SESSION_PATH;
                    } else if (!mode.equals("ALL")) {
                        throw invalid("use apiRoot with Union whitelist/excludes mode: " + id);
                    }
                    if (type == SourceType.UNION) {
                        if (mapping == null) throw invalid("UNION mappedUrl is required: " + id);
                        if (subject != null && !subject.equals("internal_id")) throw invalid("UNION subjectIdField must be internal_id: " + id);
                        if (created != null) throw invalid("UNION identity is internal_id; createdAtField is not allowed: " + id);
                        subject = "internal_id";
                    }
                    if (mapping != null) {
                        if (!mapping.contains("{uuid}")) throw invalid("mappedUrl must contain {uuid}: " + id);
                        validateUrl(mapping.replace("{uuid}", "123456781234423482341234567890ab"), false);
                        if (subject == null) throw invalid("subjectIdField is required with mappedUrl: " + id);
                    } else if (subject != null || created != null) {
                        throw invalid("subjectIdField/createdAtField require mappedUrl: " + id);
                    }
                }
                if (profileUrl == null && joinedUrl.endsWith("/session/minecraft/hasJoined")) {
                    profileUrl = joinedUrl.substring(0, joinedUrl.length() - "hasJoined".length())
                            + "profile/{uuid}?unsigned=false";
                }
                if (profileUrl != null) {
                    if (profileUrl.length() > 2048 || !profileUrl.contains("{uuid}")) {
                        throw invalid("profileUrl must be a bounded URL template containing {uuid}: " + id);
                    }
                    validateUrl(profileUrl.replace("{uuid}", "123456781234423482341234567890ab"), true);
                }
                if (subject != null) validateField(subject);
                if (created != null) validateField(created);
                sources.add(new Source(id, type, enabled, priority, validateUrl(joinedUrl, false), mapping, subject, created,
                        timeout, retries, namePrefix, profileUrl));
            }
            if (officialCount > 1) throw invalid("only one OFFICIAL source is allowed");
            sources.sort(Comparator.comparingInt(Source::priority));
            List<Source> enabled = sources.stream().filter(Source::enabled).toList();
            for (Source source : enabled) {
                if (source.type() == SourceType.OFFICIAL && source != enabled.get(0)) throw invalid("OFFICIAL must have the highest priority (smallest number)");
            }
            return new SourcesConfig(sources, parallelism, budget, relax, newPlayerAuth, offlineNames,
                    offlineNamePrefix, floodgateNamePrefix, namespaces);
        } catch (com.google.gson.JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            throw invalid("malformed JSON or field type: " + e.getMessage());
        }
    }

    private static List<Pattern> offlineNames(JsonObject root) {
        if (!root.has("offlineNames")) return List.of();
        JsonElement value = root.get("offlineNames");
        if (!value.isJsonArray() || value.getAsJsonArray().size() > 128) throw invalid("offlineNames must be an array of at most 128 name patterns");
        List<Pattern> result = new ArrayList<>();
        for (JsonElement entry : value.getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()
                    || entry.getAsString().isEmpty() || entry.getAsString().length() > 256) {
                throw invalid("offlineNames entries must be nonempty regex strings of at most 256 characters");
            }
            try {
                result.add(Pattern.compile(entry.getAsString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            } catch (PatternSyntaxException e) {
                throw invalid("invalid offlineNames regex: " + e.getDescription());
            }
        }
        return result;
    }

    private static List<String> unionCodes(JsonObject obj) {
        if (!obj.has("unionServers")) return List.of();
        if (!obj.get("unionServers").isJsonArray()) throw invalid("unionServers must be an array");
        List<String> codes = new ArrayList<>();
        for (JsonElement entry : obj.getAsJsonArray("unionServers")) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) throw invalid("unionServers must contain strings");
            String code = entry.getAsString();
            if (!code.matches("[A-Z0-9]{1,16}") || codes.contains(code)) throw invalid("invalid or duplicate Union server code: " + code);
            codes.add(code);
        }
        if (codes.size() > 128) throw invalid("too many Union server codes");
        return codes;
    }

    private static URI validateUrl(String value, boolean allowQuery) {
        try {
            URI uri = URI.create(value);
            if ((!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) || uri.getHost() == null
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))
                    || uri.getUserInfo() != null || uri.getFragment() != null || (!allowQuery && uri.getRawQuery() != null)) {
                throw invalid("URL must be an absolute HTTP(S) URL without credentials, query or fragment: " + value);
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw invalid("invalid URL: " + value);
        }
    }

    private static void validateField(String field) {
        if (!field.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){0,7}")) throw invalid("invalid mapped field path: " + field);
    }

    private static void known(JsonObject obj, Set<String> fields, String location) {
        for (String key : obj.keySet()) if (!fields.contains(key)) throw invalid("unknown " + location + " field: " + key);
    }

    /** Display text only: retain spacing and Unicode, without interpreting color or markup syntax. */
    private static String prefix(JsonObject obj, String key) {
        if (!obj.has(key)) return "";
        JsonElement value = obj.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(key + " must be a string");
        String text = value.getAsString();
        if (text.codePointCount(0, text.length()) > 64) throw invalid(key + " must be at most 64 Unicode code points");
        if (text.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE))) {
            throw invalid(key + " must not contain control characters or unpaired surrogates");
        }
        return text;
    }

    private static String optional(JsonObject obj, String key) {
        if (!obj.has(key)) return null;
        JsonElement value = obj.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            throw invalid(key + " must be a nonempty string");
        }
        return value.getAsString();
    }

    private static String required(JsonObject obj, String key) {
        String value = optional(obj, key);
        if (value == null) throw invalid("missing required field: " + key);
        return value;
    }

    private static boolean bool(JsonObject obj, String key, boolean fallback) {
        if (!obj.has(key)) return fallback;
        JsonElement value = obj.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw invalid(key + " must be boolean");
        return value.getAsBoolean();
    }

    private static int integer(JsonObject obj, String key, int fallback) {
        if (!obj.has(key)) return fallback;
        JsonElement value = obj.get(key);
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid(key + " must be an integer");
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw invalid(key + " must be an integer");
        }
    }

    private static int bounded(JsonObject obj, String key, int fallback, int min, int max) {
        int value = integer(obj, key, fallback);
        if (value < min || value > max) throw invalid(key + " must be between " + min + " and " + max);
        return value;
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    public List<Source> sources() { return sources; }
    public int parallelism() { return parallelism; }
    public int overallTimeoutMillis() { return overallTimeoutMillis; }
    public boolean relaxCustomChatKeys() { return relaxCustomChatKeys; }
    public NewPlayerAuth newPlayerAuth() { return newPlayerAuth; }
    public boolean isExplicitOfflineName(String name) { return offlineNames.stream().anyMatch(pattern -> pattern.matcher(name).matches()); }
    public boolean hasCustomSources() { return sources.stream().anyMatch(source -> source.enabled() && source.type() != SourceType.OFFICIAL); }
    public String offlineNamePrefix() { return offlineNamePrefix; }
    public String floodgateNamePrefix() { return floodgateNamePrefix; }

    /** Include disabled sources: an established connection retains the source that verified it. */
    public String namePrefix(String sourceId) {
        return sources.stream().filter(source -> source.id().equals(sourceId)).map(Source::namePrefix).findFirst().orElse("");
    }

    public String officialNamePrefix() {
        return sources.stream().filter(source -> source.type() == SourceType.OFFICIAL).map(Source::namePrefix).findFirst().orElse("");
    }

    /** Namespaces are reservations, not authentication evidence; disabled sources retain ownership. */
    public String reservedNameSource(String localName) {
        if (localName == null) return null;
        String lowerCaseName = localName.toLowerCase(Locale.ROOT);
        for (NameNamespace namespace : customNameNamespaces) {
            if (lowerCaseName.startsWith(namespace.lowerCasePrefix())) return namespace.sourceId();
        }
        return null;
    }

    public boolean isReservedCustomName(String localName) { return reservedNameSource(localName) != null; }

    /** Name prefix settings do not activate an authentication source. */
    public boolean usesLegacyAuthentication() {
        if (parallelism != 4 || overallTimeoutMillis != 6000 || relaxCustomChatKeys
                || newPlayerAuth != NewPlayerAuth.AUTO || !offlineNames.isEmpty()) return false;
        List<Source> enabled = sources.stream().filter(Source::enabled).toList();
        if (enabled.size() != 1) return false;
        Source official = enabled.get(0);
        return official.type() == SourceType.OFFICIAL && official.id().equals("official")
                && official.priority() == 0 && official.timeoutMillis() == 2000 && official.retries() == 0;
    }
}
