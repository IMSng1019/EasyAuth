package xyz.nikitacartes.easyauth.sources;

import com.google.gson.JsonObject;
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

    @Test void profileLookupEndpointsFollowOnlyStandardSessionPaths() throws Exception {
        assertEquals(SourcesConfig.OFFICIAL_PROFILE, SourcesConfig.defaults().sources().get(0).profileUrl());
        assertEquals("https://skin.test/api/sessionserver/session/minecraft/profile/{uuid}?unsigned=false",
                load("[{\"id\":\"skin\",\"type\":\"SKIN_SITE\",\"apiRoot\":\"https://skin.test/api\"}]").sources().get(0).profileUrl());
        assertEquals("https://skin.test/api/sessionserver/session/minecraft/profile/{uuid}?unsigned=false",
                load("[{\"id\":\"skin\",\"type\":\"SKIN_SITE\",\"hasJoinedUrl\":\"https://skin.test/api/sessionserver/session/minecraft/hasJoined\"}]").sources().get(0).profileUrl());
        assertNull(load("[{\"id\":\"skin\",\"type\":\"SKIN_SITE\",\"hasJoinedUrl\":\"https://skin.test/custom-login\"}]").sources().get(0).profileUrl());
        assertEquals("https://skin.test/custom-profile/{uuid}?unsigned=false",
                load("[{\"id\":\"skin\",\"type\":\"SKIN_SITE\",\"hasJoinedUrl\":\"https://skin.test/custom-login\",\"profileUrl\":\"https://skin.test/custom-profile/{uuid}?unsigned=false\"}]").sources().get(0).profileUrl());
    }

    @Test void profileLookupCannotRedirectOfficialIdentityOrUseMalformedTemplates() {
        assertThrows(IllegalArgumentException.class,
                () -> load("[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"profileUrl\":\"https://skin.test/profile/{uuid}\"}]"));
        for (String url : new String[]{"https://skin.test/profile", "file:///profile/{uuid}",
                "https://secret@skin.test/profile/{uuid}", "https://skin.test/profile/{uuid}#fragment",
                "https://skin.test/profile/{name}"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> load("[{\"id\":\"skin\",\"type\":\"SKIN_SITE\",\"apiRoot\":\"https://skin.test/api\",\"profileUrl\":\"" + url + "\"}]"), url);
        }
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

    private SourcesConfig loadPrefixes(String rootFields, String sources) throws Exception {
        Path file = temp.resolve("sources.json");
        Files.writeString(file, "{\"configVersion\":1," + rootFields + "\"sources\":" + sources + "}");
        return SourcesConfig.load(file);
    }

    private SourcesConfig loadPrefix(String key, String value) throws Exception {
        JsonObject field = new JsonObject();
        field.addProperty(key, value);
        String json = field.toString();
        String contents = json.substring(1, json.length() - 1);
        if (key.equals("namePrefix")) {
            return load("[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"timeoutMillis\":2000," + contents + "}]");
        }
        return loadPrefixes(contents + ",", "[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"timeoutMillis\":2000}]");
    }

    @Test void omittedNamePrefixesRemainEmptyForEveryChannel() throws Exception {
        SourcesConfig config = load("[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"timeoutMillis\":2000}]");
        assertEquals("", config.sources().get(0).namePrefix());
        assertEquals("", config.namePrefix("official"));
        assertEquals("", config.namePrefix("missing"));
        assertEquals("", config.officialNamePrefix());
        assertEquals("", config.offlineNamePrefix());
        assertEquals("", config.floodgateNamePrefix());
        assertTrue(config.usesLegacyAuthentication());
        assertEquals("", SourcesConfig.defaults().officialNamePrefix());
    }

    @Test void explicitEmptyNamePrefixesAreValid() throws Exception {
        SourcesConfig config = loadPrefixes("\"offlineNamePrefix\":\"\",\"floodgateNamePrefix\":\"\",",
                "[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"timeoutMillis\":2000,\"namePrefix\":\"\"}]");
        assertEquals("", config.officialNamePrefix());
        assertEquals("", config.offlineNamePrefix());
        assertEquals("", config.floodgateNamePrefix());
        assertTrue(config.usesLegacyAuthentication());
    }

    @Test void unicodeAndSpacingArePreservedWithoutFormattingParsing() throws Exception {
        SourcesConfig config = loadPrefixes("\"offlineNamePrefix\":\"  离线  \",\"floodgateNamePrefix\":\"[基岩🎮] \",",
                "[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"timeoutMillis\":2000,\"namePrefix\":\"<red>[正版]</red> \"}]");
        assertEquals("<red>[正版]</red> ", config.namePrefix("official"));
        assertEquals("  离线  ", config.offlineNamePrefix());
        assertEquals("[基岩🎮] ", config.floodgateNamePrefix());
        assertTrue(config.usesLegacyAuthentication(), "Display-only configuration must not enable managed authentication");
        assertFalse(config.hasCustomSources());
    }

    @Test void prefixesAreResolvedBySourceIdIncludingDisabledSources() throws Exception {
        SourcesConfig config = load("[{\"id\":\"mojang\",\"type\":\"OFFICIAL\",\"priority\":0,\"namePrefix\":\"[M] \"},"
                + "{\"id\":\"campus-a\",\"type\":\"SKIN_SITE\",\"priority\":1,\"apiRoot\":\"https://a.test\",\"namePrefix\":\"[A] \"},"
                + "{\"id\":\"campus-b\",\"type\":\"SKIN_SITE\",\"priority\":2,\"enabled\":false,\"apiRoot\":\"https://b.test\",\"namePrefix\":\"[B] \"}]");
        assertEquals("[M] ", config.officialNamePrefix());
        assertEquals("[A] ", config.namePrefix("campus-a"));
        assertEquals("[B] ", config.namePrefix("campus-b"));
        assertEquals("", config.namePrefix("unknown"));
        assertEquals("", load("[]").officialNamePrefix());
    }

    @Test void disabledSourcePrefixDoesNotChangeLegacyAuthentication() throws Exception {
        SourcesConfig config = load("[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"priority\":0,\"timeoutMillis\":2000,\"namePrefix\":\"[正版] \"},"
                + "{\"id\":\"campus\",\"type\":\"SKIN_SITE\",\"priority\":1,\"enabled\":false,\"apiRoot\":\"https://campus.test\",\"namePrefix\":\"[CAMPUS] \"}]");
        assertEquals("[CAMPUS] ", config.namePrefix("campus"));
        assertTrue(config.usesLegacyAuthentication());
    }

    @Test void namePrefixLengthCountsUnicodeCodePoints() throws Exception {
        String maximum = "🎮".repeat(64);
        for (String key : new String[]{"namePrefix", "offlineNamePrefix", "floodgateNamePrefix"}) {
            SourcesConfig config = loadPrefix(key, maximum);
            String value = switch (key) {
                case "namePrefix" -> config.officialNamePrefix();
                case "offlineNamePrefix" -> config.offlineNamePrefix();
                default -> config.floodgateNamePrefix();
            };
            assertEquals(maximum, value);
            assertThrows(IllegalArgumentException.class, () -> loadPrefix(key, maximum + "a"));
        }
    }

    @Test void namePrefixesRejectControlsAndUnpairedSurrogates() {
        for (String key : new String[]{"namePrefix", "offlineNamePrefix", "floodgateNamePrefix"}) {
            for (String value : new String[]{"line\\n", "tab\\t", "null\\u0000", "control\\u0085", "high\\uD800", "low\\uDC00"}) {
                String field = "\"" + key + "\":\"" + value + "\"";
                if (key.equals("namePrefix")) {
                    assertThrows(IllegalArgumentException.class, () -> load("[{\"id\":\"official\",\"type\":\"OFFICIAL\"," + field + "}]"));
                } else {
                    assertThrows(IllegalArgumentException.class, () -> loadPrefixes(field + ",", "[]"));
                }
            }
        }
    }

    @Test void namePrefixesRejectNonStringValues() {
        for (String value : new String[]{"42", "true", "null", "{}", "[]"}) {
            assertThrows(IllegalArgumentException.class, () -> load("[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"namePrefix\":" + value + "}]"));
            assertThrows(IllegalArgumentException.class, () -> loadPrefixes("\"offlineNamePrefix\":" + value + ",", "[]"));
            assertThrows(IllegalArgumentException.class, () -> loadPrefixes("\"floodgateNamePrefix\":" + value + ",", "[]"));
        }
    }

    private SourcesConfig loadCustomPrefix(SourceType type, String prefix, boolean enabled) throws Exception {
        JsonObject source = new JsonObject();
        source.addProperty("id", "campus");
        source.addProperty("type", type.name());
        source.addProperty("apiRoot", "https://campus.test/yggdrasil");
        source.addProperty("enabled", enabled);
        source.addProperty("namePrefix", prefix);
        if (type == SourceType.UNION) source.addProperty("mappedUrl", "https://campus.test/mapped/{uuid}");
        return load("[" + source + "]");
    }

    @Test void customPrefixesAcceptBracketsAndRetainOriginalDisplaySpacing() throws Exception {
        for (SourceType type : new SourceType[]{SourceType.UNION, SourceType.SKIN_SITE}) {
            assertEquals("[MUA] ", loadCustomPrefix(type, "[MUA] ", true).namePrefix("campus"));
            assertEquals("", loadCustomPrefix(type, "", true).namePrefix("campus"));
            assertEquals("A".repeat(15), loadCustomPrefix(type, "A".repeat(15), true).namePrefix("campus"));
        }
    }

    @Test void customPrefixesRejectInvalidActualNameNamespacesEvenWhenDisabled() {
        for (SourceType type : new SourceType[]{SourceType.UNION, SourceType.SKIN_SITE}) {
            for (boolean enabled : new boolean[]{true, false}) {
                for (String value : new String[]{"[高校]", "[A B]", "A".repeat(16), "[A]\nB", "[A]\u007f"}) {
                    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                            () -> loadCustomPrefix(type, value, enabled), type + " enabled=" + enabled);
                    assertTrue(error.getMessage().contains("namePrefix"), error.getMessage());
                }
            }
        }
    }

    @Test void customNamespacesReserveNamesIncludingDisabledSourcesWithoutActivatingAuthentication() throws Exception {
        SourcesConfig config = load("[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"priority\":0,\"timeoutMillis\":2000,\"namePrefix\":\"[MUA] \"},"
                + "{\"id\":\"campus\",\"type\":\"UNION\",\"priority\":1,\"enabled\":false,\"apiRoot\":\"https://campus.test\",\"mappedUrl\":\"https://campus.test/mapped/{uuid}\",\"namePrefix\":\"[MUA] \"}]");
        assertEquals("campus", config.reservedNameSource("[MUA]LXW1019"));
        assertEquals("campus", config.reservedNameSource("[mua]LXW1019"));
        assertTrue(config.isReservedCustomName("[MUA]"));
        assertNull(config.reservedNameSource("LXW1019"));
        assertNull(config.reservedNameSource("Other[MUA]Name"));
        assertFalse(config.isReservedCustomName("[OTHER]Name"));
        assertTrue(config.usesLegacyAuthentication());
    }

    @Test void customNamespacesCannotOverlapAcrossSources() {
        for (String second : new String[]{"[MUA]", "[mua]", "[MUA]X", "[M"}) {
            assertThrows(IllegalArgumentException.class, () -> load("["
                    + "{\"id\":\"first\",\"type\":\"SKIN_SITE\",\"priority\":1,\"apiRoot\":\"https://a.test\",\"namePrefix\":\"[MUA] \"},"
                    + "{\"id\":\"second\",\"type\":\"SKIN_SITE\",\"priority\":2,\"enabled\":false,\"apiRoot\":\"https://b.test\",\"namePrefix\":\"" + second + "\"}]"));
        }
    }

    @Test void emptyCustomPrefixesAndOfficialDisplayLabelsDoNotReserveNamespaces() throws Exception {
        SourcesConfig config = load("["
                + "{\"id\":\"official\",\"type\":\"OFFICIAL\",\"priority\":0,\"namePrefix\":\"[VIP] \"},"
                + "{\"id\":\"first\",\"type\":\"SKIN_SITE\",\"priority\":1,\"apiRoot\":\"https://a.test\",\"namePrefix\":\"\"},"
                + "{\"id\":\"second\",\"type\":\"SKIN_SITE\",\"priority\":2,\"apiRoot\":\"https://b.test\",\"namePrefix\":\"   \"}]");
        assertNull(config.reservedNameSource("[VIP]Alice"));
        assertNull(config.reservedNameSource("Alice"));
        assertFalse(config.isReservedCustomName(""));
    }
}
