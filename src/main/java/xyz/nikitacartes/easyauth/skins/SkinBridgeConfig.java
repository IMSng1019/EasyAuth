package xyz.nikitacartes.easyauth.skins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;

public record SkinBridgeConfig(boolean enabled, int loginWaitMillis, int requestTimeoutMillis,
                               int generationTimeoutMillis, int refreshAfterHours,
                               int maxQueuedSkins, int failureRetrySeconds) {
    private static final String DEFAULT_JSON = """
            {
              "configVersion": 1,
              "enabled": true,
              "loginWaitMillis": 8000,
              "requestTimeoutMillis": 5000,
              "generationTimeoutMillis": 60000,
              "refreshAfterHours": 24,
              "maxQueuedSkins": 64,
              "failureRetrySeconds": 60
            }
            """;

    public SkinBridgeConfig {
        bound("loginWaitMillis", loginWaitMillis, 0, 30000);
        bound("requestTimeoutMillis", requestTimeoutMillis, 1, 60000);
        bound("generationTimeoutMillis", generationTimeoutMillis, 1, 300000);
        bound("refreshAfterHours", refreshAfterHours, 1, 8760);
        bound("maxQueuedSkins", maxQueuedSkins, 1, 1024);
        bound("failureRetrySeconds", failureRetrySeconds, 1, 86400);
    }

    public static SkinBridgeConfig defaults() {
        return new SkinBridgeConfig(true, 8000, 5000, 60000, 24, 64, 60);
    }

    public static SkinBridgeConfig load(Path file) throws IOException {
        if (!Files.exists(file)) {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            try {
                Files.writeString(file, DEFAULT_JSON, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException ignored) {
                // Validate a concurrently created file rather than replacing it.
            }
        }
        String json = readBounded(file, 65536);
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Skin bridge configuration must be an object");
            JsonObject obj = parsed.getAsJsonObject();
            Set<String> known = Set.of("configVersion", "enabled", "loginWaitMillis", "requestTimeoutMillis",
                    "generationTimeoutMillis", "refreshAfterHours", "maxQueuedSkins", "failureRetrySeconds");
            for (String key : obj.keySet()) {
                if (!known.contains(key)) throw new IllegalArgumentException("Unknown skin bridge configuration field");
            }
            if (integer(obj, "configVersion", 1) != 1) throw new IllegalArgumentException("Unsupported skin bridge configVersion");
            SkinBridgeConfig defaults = defaults();
            boolean enabled = defaults.enabled();
            if (obj.has("enabled")) {
                JsonElement value = obj.get("enabled");
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("enabled must be boolean");
                }
                enabled = value.getAsBoolean();
            }
            SkinBridgeConfig config = new SkinBridgeConfig(enabled,
                    integer(obj, "loginWaitMillis", defaults.loginWaitMillis()),
                    integer(obj, "requestTimeoutMillis", defaults.requestTimeoutMillis()),
                    integer(obj, "generationTimeoutMillis", defaults.generationTimeoutMillis()),
                    integer(obj, "refreshAfterHours", defaults.refreshAfterHours()),
                    integer(obj, "maxQueuedSkins", defaults.maxQueuedSkins()),
                    integer(obj, "failureRetrySeconds", defaults.failureRetrySeconds()));
            try {
                Files.createFile(file.toAbsolutePath().resolveSibling("mineskin.key"));
            } catch (FileAlreadyExistsException ignored) {
                // Atomic creation only: never truncate a credential or copy an environment key.
            } catch (IOException unavailable) {
                // A read-only config directory must not disable an environment-supplied key.
                System.getLogger(SkinBridgeConfig.class.getName()).log(System.Logger.Level.WARNING,
                        "Could not create empty mineskin.key; create it manually or use EASYAUTH_MINESKIN_API_KEY");
            }
            return config;
        } catch (com.google.gson.JsonParseException | IllegalStateException exception) {
            throw new IllegalArgumentException("Malformed skin bridge configuration");
        }
    }

    public static String readApiKey(Path directory) throws IOException {
        return readApiKey(directory, System.getenv());
    }

    static String readApiKey(Path directory, Map<String, String> environment) throws IOException {
        String key = environment.get("EASYAUTH_MINESKIN_API_KEY");
        if (key == null || key.isBlank()) {
            Path file = directory.resolve("mineskin.key");
            key = Files.exists(file) ? readBounded(file, 4096) : "";
        }
        if (key.length() > 4096) throw new IllegalArgumentException("MineSkin API key is too long");
        key = key.strip();
        if (!key.isEmpty() && !key.matches("[!-~]+")) {
            throw new IllegalArgumentException("MineSkin API key must be a single printable ASCII token");
        }
        return key;
    }

    private static String readBounded(Path file, int limit) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IllegalArgumentException("Skin bridge configuration file exceeds its size limit");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static void bound(String key, int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException(key + " is outside its supported bounds");
    }
}
