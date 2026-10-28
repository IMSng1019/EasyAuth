package xyz.nikitacartes.easyauth.skins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import xyz.nikitacartes.easyauth.sources.AuthenticatedIdentity;
import xyz.nikitacartes.easyauth.sources.SourceType;
import xyz.nikitacartes.easyauth.sources.SourcesConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class SourceProfileLookup {
    private static final int MAX_RESPONSE_BYTES = 65536;

    private SourceProfileLookup() {}

    public static Optional<TextureProperty> lookup(AuthenticatedIdentity identity, SourcesConfig.Source source,
                                                    int timeoutMillis) throws IOException {
        if (identity == null || source == null || !identity.sourceId().equals(source.id()) || identity.type() != source.type()) {
            throw new IOException("Texture source does not match the authenticated identity");
        }
        for (AuthenticatedIdentity.PropertyData property : identity.properties()) {
            if (property.name().equals("textures")) {
                return Optional.of(new TextureProperty(property.value(), property.signature()));
            }
        }
        String template = source.profileUrl();
        if (template == null) return Optional.empty();
        if (source.type() == SourceType.OFFICIAL && !SourcesConfig.OFFICIAL_PROFILE.equals(template)) {
            throw new IOException("Official texture lookup must use the Mojang endpoint");
        }
        if (timeoutMillis < 1 || timeoutMillis > 60000) throw new IOException("Invalid profile lookup timeout");
        try {
            URI uri = URI.create(template.replace("{uuid}", identity.providerUuid().toString().replace("-", "")));
            if (template.length() > 2048 || !template.contains("{uuid}") || uri.getHost() == null
                    || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
                throw new IOException("Invalid authenticated profile lookup endpoint");
            }
            JsonElement parsed = JsonParser.parseString(request(uri, timeoutMillis));
            if (!parsed.isJsonObject()) throw new IOException("Profile lookup did not return an object");
            JsonObject profile = parsed.getAsJsonObject();
            if (!strictUuid(string(profile.get("id"), "id")).equals(identity.providerUuid())) {
                throw new IOException("Texture response UUID differs from the verified provider UUID");
            }
            if (!profile.has("properties")) return Optional.empty();
            JsonElement properties = profile.get("properties");
            if (!properties.isJsonArray() || properties.getAsJsonArray().size() > 16) {
                throw new IOException("Invalid texture profile properties");
            }
            TextureProperty texture = null;
            for (JsonElement item : properties.getAsJsonArray()) {
                if (!item.isJsonObject()) throw new IOException("Invalid texture profile property");
                JsonObject property = item.getAsJsonObject();
                String name = string(property.get("name"), "property name");
                String value = string(property.get("value"), "property value");
                String signature = property.has("signature") ? string(property.get("signature"), "property signature") : null;
                if (name.length() > 64 || value.length() > 32768 || (signature != null && signature.length() > 8192)) {
                    throw new IOException("Oversized texture profile property");
                }
                if (name.equals("textures")) {
                    if (texture != null) throw new IOException("Ambiguous duplicate texture properties");
                    texture = new TextureProperty(value, signature);
                }
            }
            return Optional.ofNullable(texture);
        } catch (com.google.gson.JsonParseException | IllegalArgumentException | IllegalStateException exception) {
            throw new IOException("Malformed authenticated texture profile response");
        }
    }

    private static String request(URI uri, int timeoutMillis) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "EasyAuth-SkinBridge/1");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Source profile lookup returned HTTP " + status);
            if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) throw new IOException("Source profile response exceeds 64 KiB");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
                        throw new IOException("Source profile lookup deadline exceeded");
                    }
                    if (output.size() + count > MAX_RESPONSE_BYTES) throw new IOException("Source profile response exceeds 64 KiB");
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String string(JsonElement value, String label) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            throw new IOException("Missing or invalid profile " + label);
        }
        return value.getAsString();
    }

    private static UUID strictUuid(String value) throws IOException {
        if (value.matches("[a-fA-F0-9]{32}")) {
            value = value.substring(0, 8) + "-" + value.substring(8, 12) + "-" + value.substring(12, 16)
                    + "-" + value.substring(16, 20) + "-" + value.substring(20);
        }
        if (!value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) {
            throw new IOException("Invalid texture profile UUID");
        }
        return UUID.fromString(value);
    }
}
