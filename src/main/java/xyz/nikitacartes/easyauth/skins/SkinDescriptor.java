package xyz.nikitacartes.easyauth.skins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

public record SkinDescriptor(URI url, String variant) {
    public SkinDescriptor {
        validatePublicUrl(url);
        if (!"classic".equals(variant) && !"slim".equals(variant)) {
            throw new IllegalArgumentException("Skin variant must be classic or slim");
        }
    }

    public static Optional<SkinDescriptor> from(TextureProperty property) {
        if (property == null) throw new IllegalArgumentException("Texture property is required");
        try {
            byte[] bytes = Base64.getDecoder().decode(property.value());
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Texture payload must be an object");
            JsonObject profile = parsed.getAsJsonObject();
            if (!profile.has("textures")) return Optional.empty();
            JsonObject textures = object(profile.get("textures"), "textures");
            if (!textures.has("SKIN")) return Optional.empty();
            JsonObject skin = object(textures.get("SKIN"), "SKIN");
            String variant = "classic";
            if (skin.has("metadata")) {
                JsonObject metadata = object(skin.get("metadata"), "metadata");
                if (metadata.has("model")) {
                    variant = string(metadata.get("model"), "model");
                    if (variant.equals("default")) variant = "classic";
                }
            }
            return Optional.of(new SkinDescriptor(URI.create(string(skin.get("url"), "url")), variant));
        } catch (com.google.gson.JsonParseException | IllegalStateException exception) {
            throw new IllegalArgumentException("Malformed texture payload");
        }
    }

    public String cacheKey() {
        try {
            String input = "easyauth-skin-v1\0" + url.toASCIIString() + "\0" + variant;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static JsonObject object(JsonElement value, String field) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException(field + " must be an object");
        return value.getAsJsonObject();
    }

    private static String string(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) throw new IllegalArgumentException(field + " must be a nonempty string");
        return value.getAsString();
    }

    private static void validatePublicUrl(URI uri) {
        if (uri == null || uri.toASCIIString().length() > 2048 || uri.getHost() == null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
            throw new IllegalArgumentException("Skin URL must be a bounded public HTTP(S) URL without credentials or fragment");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") || host.equals("local")) {
            throw new IllegalArgumentException("Skin URL must not use a local hostname");
        }
        if (host.indexOf(':') >= 0) {
            // A colon and this alphabet identify a literal IPv6 address, so this call never
            // resolves arbitrary hostnames. Images themselves are downloaded by MineSkin.
            if (!host.matches("[0-9a-f:.]+")) throw new IllegalArgumentException("Invalid skin IP literal");
            try {
                InetAddress address = InetAddress.getByName(host);
                byte[] raw = address.getAddress();
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()
                        || (raw.length == 16 && (raw[0] & 0xfe) == 0xfc)
                        || (raw.length == 4 && !publicIpv4(raw))) {
                    throw new IllegalArgumentException("Skin URL must not use a private IP literal");
                }
            } catch (UnknownHostException exception) {
                throw new IllegalArgumentException("Invalid skin IP literal");
            }
        } else if (host.matches("(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+)){0,3}")) {
            String[] parts = host.split("\\.", -1);
            if (parts.length != 4) throw new IllegalArgumentException("Skin IP literals must use dotted decimal notation");
            byte[] address = new byte[4];
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(parts[i]) > 255) {
                    throw new IllegalArgumentException("Invalid skin IP literal");
                }
                address[i] = (byte) Integer.parseInt(parts[i]);
            }
            if (!publicIpv4(address)) throw new IllegalArgumentException("Skin URL must not use a private IP literal");
        } else if (!host.contains(".")) {
            throw new IllegalArgumentException("Skin URL must use a public hostname");
        }
    }

    private static boolean publicIpv4(byte[] address) {
        int a = address[0] & 255, b = address[1] & 255, c = address[2] & 255;
        return a != 0 && a != 10 && a != 127 && a < 224
                && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31)
                && !(a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2))))
                && !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                && !(a == 203 && b == 0 && c == 113);
    }
}
