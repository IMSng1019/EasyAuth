package xyz.nikitacartes.easyauth.skins;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class SkinDescriptorTest {
    private static TextureProperty property(String json) {
        return new TextureProperty(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)), null);
    }

    @Test void extractsStandardAndSlimSkinsWithoutRequiringInputSignatures() {
        var classic = SkinDescriptor.from(property("{\"textures\":{\"SKIN\":{\"url\":\"https://skin.example.test/a.png\"}}}")).orElseThrow();
        var slim = SkinDescriptor.from(property("{\"textures\":{\"SKIN\":{\"url\":\"https://skin.example.test/a.png\",\"metadata\":{\"model\":\"slim\"}}}}"))
                .orElseThrow();
        assertEquals(URI.create("https://skin.example.test/a.png"), classic.url());
        assertEquals("classic", classic.variant());
        assertEquals("slim", slim.variant());
        assertNotEquals(classic.cacheKey(), slim.cacheKey());
    }

    @Test void cacheKeyIgnoresTimestampsAndProfileIdentityButTracksImageAndModel() {
        var first = SkinDescriptor.from(property("{\"timestamp\":1,\"profileId\":\"one\",\"textures\":{\"SKIN\":{\"url\":\"https://skin.example.test/a.png\"}}}")).orElseThrow();
        var later = SkinDescriptor.from(property("{\"timestamp\":2,\"profileId\":\"two\",\"textures\":{\"SKIN\":{\"url\":\"https://skin.example.test/a.png\"}}}")).orElseThrow();
        assertEquals(first.cacheKey(), later.cacheKey());
        assertTrue(first.cacheKey().matches("[a-f0-9]{64}"));
        assertNotEquals(first.cacheKey(), new SkinDescriptor(URI.create("https://skin.example.test/b.png"), "classic").cacheKey());
    }

    @Test void absentSkinIsDistinctFromMalformedTextureData() {
        assertTrue(SkinDescriptor.from(property("{}")).isEmpty());
        assertTrue(SkinDescriptor.from(property("{\"textures\":{\"CAPE\":{\"url\":\"https://skin.example.test/cape.png\"}}}")).isEmpty());
        for (String malformed : new String[]{"[]", "{\"textures\":[]}", "{\"textures\":{\"SKIN\":{}}}",
                "{\"textures\":{\"SKIN\":{\"url\":4}}}",
                "{\"textures\":{\"SKIN\":{\"url\":\"https://skin.example.test/a.png\",\"metadata\":{\"model\":\"unknown\"}}}}"}) {
            assertThrows(IllegalArgumentException.class, () -> SkinDescriptor.from(property(malformed)), malformed);
        }
        assertThrows(IllegalArgumentException.class, () -> SkinDescriptor.from(new TextureProperty("not base64", null)));
        assertThrows(IllegalArgumentException.class, () -> SkinDescriptor.from(new TextureProperty("A".repeat(32769), null)));
    }

    @Test void publicImageUrlsCannotNameLocalResourcesOrPrivateLiteralAddresses() {
        for (String url : new String[]{"file:///a.png", "data:image/png;base64,AAAA", "ftp://example.test/a.png",
                "https://user:password@example.test/a.png", "https://example.test/a.png#fragment", "http://localhost/a.png",
                "http://LOCALHOST./a.png", "http://machine.local/a.png", "http://127.0.0.1/a.png", "http://10.1.2.3/a.png",
                "http://172.16.0.1/a.png", "http://192.168.0.1/a.png", "http://169.254.169.254/a.png", "http://100.64.0.1/a.png",
                "http://0.0.0.0/a.png", "http://224.0.0.1/a.png", "http://127.1/a.png", "http://2130706433/a.png",
                "http://0x7f000001/a.png", "http://[::1]/a.png", "http://[fc00::1]/a.png", "http://[fe80::1]/a.png",
                "http://[::ffff:127.0.0.1]/a.png", "https://example.test:70000/a.png"}) {
            assertThrows(IllegalArgumentException.class, () -> new SkinDescriptor(URI.create(url), "classic"), url);
        }
        assertDoesNotThrow(() -> new SkinDescriptor(URI.create("http://textures.minecraft.net/texture/abc"), "classic"));
        assertDoesNotThrow(() -> new SkinDescriptor(URI.create("https://skin.example.test/a.png?version=2"), "slim"));
        assertDoesNotThrow(() -> new SkinDescriptor(URI.create("https://1.1.1.1/a.png"), "classic"));
        assertThrows(IllegalArgumentException.class, () -> new SkinDescriptor(URI.create("https://skin.example.test/a.png"), "unknown"));
    }
}
