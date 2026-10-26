package xyz.nikitacartes.easyauth.sources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SourcesConfigTest {
    @TempDir Path temp;

    private SourcesConfig load(String sources) throws Exception {
        Path file = temp.resolve("sources.json");
        Files.writeString(file, "{\"configVersion\":1,\"sources\":" + sources + "}");
        return SourcesConfig.load(file);
    }

    @Test void createsSafeDefaultWhenMissing() throws Exception {
        Path file = temp.resolve("sources.json");
        SourcesConfig config = SourcesConfig.load(file);
        assertFalse(config.hasCustomSources());
        assertFalse(config.relaxCustomChatKeys());
        assertEquals(SourceType.OFFICIAL, config.sources().get(0).type());
        assertEquals(SourcesConfig.OFFICIAL_HAS_JOINED, config.sources().get(0).hasJoinedUrl().toString());
        assertTrue(Files.exists(file));
    }

    @Test void rejectsDuplicateIdsAndPriorities() {
        assertThrows(IllegalArgumentException.class, () -> load("[{\"id\":\"same\",\"type\":\"OFFICIAL\",\"priority\":0},{\"id\":\"same\",\"type\":\"SKIN_SITE\",\"priority\":1,\"apiRoot\":\"https://skin.test/api\"}]"));
        assertThrows(IllegalArgumentException.class, () -> load("[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"priority\":0},{\"id\":\"skin\",\"type\":\"SKIN_SITE\",\"priority\":0,\"apiRoot\":\"https://skin.test/api\"}]"));
    }

    @Test void rejectsOfficialEndpointOverrideOrLowerPriority() {
        assertThrows(IllegalArgumentException.class, () -> load("[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"hasJoinedUrl\":\"https://attacker.test/hasJoined\"}]"));
        assertThrows(IllegalArgumentException.class, () -> load("[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"priority\":2},{\"id\":\"skin\",\"type\":\"SKIN_SITE\",\"priority\":1,\"apiRoot\":\"https://skin.test\"}]"));
    }

    @Test void rejectsInvalidUrlsMissingUnionMappingAndUnknownFields() {
        assertThrows(IllegalArgumentException.class, () -> load("[{\"id\":\"skin\",\"type\":\"SKIN_SITE\",\"apiRoot\":\"file:///tmp/identity\"}]"));
        assertThrows(IllegalArgumentException.class, () -> load("[{\"id\":\"union\",\"type\":\"UNION\",\"apiRoot\":\"https://union.test\"}]"));
        assertThrows(IllegalArgumentException.class, () -> load("[{\"id\":\"skin\",\"type\":\"SKIN_SITE\",\"apiRoot\":\"https://skin.test\",\"timeotMillis\":3}]"));
        assertThrows(IllegalArgumentException.class, () -> load("[{\"id\":\"skin\",\"type\":\"SKIN_SITE\",\"apiRoot\":\"https://skin.test:99999/api\"}]"));
    }

    @Test void composesUnionScopeAndPreservesMappingTemplate() throws Exception {
        SourcesConfig config = load("[{\"id\":\"union\",\"type\":\"UNION\",\"apiRoot\":\"https://skin.mualliance.ltd/api/union/yggdrasil/\",\"mappedUrl\":\"https://skin.mualliance.ltd/api/union/profile/mapped/byuuid/{uuid}\",\"unionMode\":\"ONLY\",\"unionServers\":[\"SJMC\",\"MUA\"]}]");
        assertTrue(config.hasCustomSources());
        assertEquals("https://skin.mualliance.ltd/api/union/yggdrasil/only/SJMC_MUA/sessionserver/session/minecraft/hasJoined", config.sources().get(0).hasJoinedUrl().toString());
        assertTrue(config.sources().get(0).mappedUrl().endsWith("/{uuid}"));
    }

    @Test void rejectsUnboundedConfiguration() throws Exception {
        Path file = temp.resolve("sources.json");
        Files.writeString(file, "{\"configVersion\":1,\"parallelism\":999,\"sources\":[]}");
        assertThrows(IllegalArgumentException.class, () -> SourcesConfig.load(file));
        assertThrows(IllegalArgumentException.class, () -> load("[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"retries\":99}]"));
    }

    @Test void newPlayerHandshakePolicyIsExplicitAndSupportsOfflineNames() throws Exception {
        Path file = temp.resolve("sources.json");
        Files.writeString(file, "{\"configVersion\":1,\"newPlayerAuth\":\"EXTERNAL\",\"offlineNames\":[\"Local_.*\",\"Alice\"],\"sources\":[]}");
        SourcesConfig config = SourcesConfig.load(file);
        assertEquals("EXTERNAL", config.newPlayerAuth().name());
        assertTrue(config.isExplicitOfflineName("Local_123"));
        assertTrue(config.isExplicitOfflineName("alice"));
        assertFalse(config.isExplicitOfflineName("UnionStudent"));
        assertFalse(config.isExplicitOfflineName("OtherAlice"));
        assertEquals("AUTO", SourcesConfig.defaults().newPlayerAuth().name());
    }

    @Test void newPlayerPolicyRejectsInvalidRegexAndUnknownPolicy() throws Exception {
        Path file = temp.resolve("sources.json");
        Files.writeString(file, "{\"configVersion\":1,\"newPlayerAuth\":\"EXTERNAL\",\"offlineNames\":[\"[\"],\"sources\":[]}");
        assertThrows(IllegalArgumentException.class, () -> SourcesConfig.load(file));
        Files.writeString(file, "{\"configVersion\":1,\"newPlayerAuth\":\"IMPLICIT\",\"sources\":[]}");
        assertThrows(IllegalArgumentException.class, () -> SourcesConfig.load(file));
    }

    @Test void onlyUnchangedEffectiveDefaultMayUseLegacyAuthentication() throws Exception {
        assertTrue(SourcesConfig.defaults().usesLegacyAuthentication());
        assertFalse(load("[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"enabled\":false}]").usesLegacyAuthentication());
        assertFalse(load("[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"timeoutMillis\":1000}]").usesLegacyAuthentication());
        assertTrue(load("[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"timeoutMillis\":2000,\"priority\":0},{\"id\":\"skin\",\"type\":\"SKIN_SITE\",\"enabled\":false,\"priority\":1,\"apiRoot\":\"https://skin.test\"}]").usesLegacyAuthentication());
    }
}
