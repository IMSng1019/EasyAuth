package xyz.nikitacartes.easyauth.skins;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkinBridgeConfigTest {
    @TempDir Path temp;

    private SkinBridgeConfig load(String json) throws Exception {
        Path file = temp.resolve("skin-bridge.json");
        Files.writeString(file, json);
        return SkinBridgeConfig.load(file);
    }

    @Test void createsEmptyKeyFileWithoutOverwritingAnExistingCredential() throws Exception {
        Path file = temp.resolve("nested/EasyAuth/skin-bridge.json");
        Path keyFile = file.resolveSibling("mineskin.key");
        assertEquals(SkinBridgeConfig.defaults(), SkinBridgeConfig.load(file));
        assertEquals(8000, SkinBridgeConfig.load(file).loginWaitMillis());
        assertFalse(Files.readString(file).contains("apiKey"));
        assertTrue(Files.isRegularFile(keyFile));
        assertEquals(0, Files.size(keyFile));
        assertEquals("", SkinBridgeConfig.readApiKey(file.getParent(), Map.of()));
        byte[] credential = " fixture-existing-key\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(keyFile, credential);
        SkinBridgeConfig.load(file);
        assertArrayEquals(credential, Files.readAllBytes(keyFile));
        Files.delete(keyFile);
        SkinBridgeConfig.load(file);
        assertEquals(0, Files.size(keyFile));
    }

    @Test void validatesFieldTypesUnknownKeysAndResourceBounds() throws Exception {
        assertFalse(load("{\"enabled\":false,\"loginWaitMillis\":0}").enabled());
        for (String bad : new String[]{"[]", "{\"apiKey\":\"not-allowed-here\"}", "{\"enabled\":\"true\"}",
                "{\"configVersion\":2}", "{\"loginWaitMillis\":30001}", "{\"requestTimeoutMillis\":0}",
                "{\"generationTimeoutMillis\":300001}", "{\"maxQueuedSkins\":0}", "{\"maxQueuedSkins\":1.5}",
                "{\"refreshAfterHours\":0}", "{\"failureRetrySeconds\":-1}"}) {
            assertThrows(IllegalArgumentException.class, () -> load(bad), bad);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new SkinBridgeConfig(true, -1, 5000, 60000, 24, 64, 60));
    }

    @Test void keyEnvironmentOverridesSeparateFileAndEmptyEnvironmentFallsBack() throws Exception {
        SkinBridgeConfig.load(temp.resolve("skin-bridge.json"));
        assertEquals("fixture-env-key", SkinBridgeConfig.readApiKey(temp, Map.of("EASYAUTH_MINESKIN_API_KEY", "fixture-env-key")));
        assertEquals(0, Files.size(temp.resolve("mineskin.key")));
        Files.writeString(temp.resolve("mineskin.key"), " fixture-file-key\n");
        assertEquals("fixture-env-key", SkinBridgeConfig.readApiKey(temp, Map.of("EASYAUTH_MINESKIN_API_KEY", "fixture-env-key")));
        assertEquals("fixture-file-key", SkinBridgeConfig.readApiKey(temp, Map.of("EASYAUTH_MINESKIN_API_KEY", " ")));
        Files.writeString(temp.resolve("mineskin.key"), "\n");
        assertEquals("", SkinBridgeConfig.readApiKey(temp, Map.of()));
        Exception invalid = assertThrows(IllegalArgumentException.class,
                () -> SkinBridgeConfig.readApiKey(temp, Map.of("EASYAUTH_MINESKIN_API_KEY", "private-marker\r\ninvalid")));
        assertFalse(invalid.getMessage().contains("private-marker"));
    }

    @Test void oversizedConfigurationAndKeyAreRejectedBeforeLoading() throws Exception {
        Files.writeString(temp.resolve("skin-bridge.json"), " ".repeat(65537));
        assertThrows(IllegalArgumentException.class, () -> SkinBridgeConfig.load(temp.resolve("skin-bridge.json")));
        Files.writeString(temp.resolve("mineskin.key"), "a".repeat(4097));
        assertThrows(IllegalArgumentException.class, () -> SkinBridgeConfig.readApiKey(temp, Map.of()));
    }
}
