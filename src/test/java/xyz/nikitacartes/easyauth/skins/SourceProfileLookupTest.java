package xyz.nikitacartes.easyauth.skins;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import xyz.nikitacartes.easyauth.sources.AuthenticatedIdentity;
import xyz.nikitacartes.easyauth.sources.SourceType;
import xyz.nikitacartes.easyauth.sources.SourcesConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SourceProfileLookupTest {
    private static final UUID PROVIDER_UUID = UUID.fromString("12345678-1234-4234-8234-1234567890ab");

    private static AuthenticatedIdentity identity(List<AuthenticatedIdentity.PropertyData> properties) {
        return new AuthenticatedIdentity("campus", SourceType.SKIN_SITE, "stable-subject", PROVIDER_UUID, "ProviderName", properties);
    }

    private static SourcesConfig.Source source(String template) {
        return new SourcesConfig.Source("campus", SourceType.SKIN_SITE, true, 1,
                URI.create("http://127.0.0.1/unused/hasJoined"), null, null, null, 1000, 0, "[Skin]", template);
    }

    @Test void existingTextureSkipsLookupAndPreservesItsBytes() throws Exception {
        var property = new AuthenticatedIdentity.PropertyData("textures", "original", "original-signature");
        assertEquals(new TextureProperty("original", "original-signature"),
                SourceProfileLookup.lookup(identity(List.of(property)), source("http://127.0.0.1:1/{uuid}"), 100).orElseThrow());
        assertTrue(SourceProfileLookup.lookup(identity(List.of()), source(null), 100).isEmpty());
    }

    @Test void missingTextureUsesVerifiedProviderUuidAndLeavesIdentityUntouched() throws Exception {
        AtomicReference<String> requested = new AtomicReference<>();
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/profile/", exchange -> {
            requested.set(exchange.getRequestURI().toString());
            byte[] body = ("{\"id\":\"" + PROVIDER_UUID + "\",\"name\":\"ProviderName\",\"properties\":["
                    + "{\"name\":\"textures\",\"value\":\"texture-value\",\"signature\":\"texture-signature\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fixture.start();
        try {
            var original = identity(List.of(new AuthenticatedIdentity.PropertyData("other", "untouched", null)));
            var result = SourceProfileLookup.lookup(original,
                    source("http://127.0.0.1:" + fixture.getAddress().getPort() + "/profile/{uuid}?unsigned=false"), 1000);
            assertEquals(new TextureProperty("texture-value", "texture-signature"), result.orElseThrow());
            assertEquals("/profile/123456781234423482341234567890ab?unsigned=false", requested.get());
            assertEquals(PROVIDER_UUID, original.providerUuid());
            assertEquals("stable-subject", original.subjectId());
            assertEquals("ProviderName", original.providerName());
            assertEquals("other", original.properties().get(0).name());
            assertEquals(1, original.properties().size());
        } finally {
            fixture.stop(0);
        }
    }

    @Test void cosmeticFailureRejectsMismatchedProfilesOversizedBodiesAndRedirects() throws Exception {
        AtomicReference<String> response = new AtomicReference<>();
        AtomicInteger status = new AtomicInteger(200);
        AtomicInteger redirectHits = new AtomicInteger();
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/profile/", exchange -> {
            exchange.getResponseHeaders().add("Location", "/redirected");
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fixture.createContext("/redirected", exchange -> {
            redirectHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        fixture.start();
        try {
            var source = source("http://127.0.0.1:" + fixture.getAddress().getPort() + "/profile/{uuid}");
            for (String invalid : new String[]{"{\"id\":\"" + UUID.randomUUID() + "\"}", "{\"name\":\"ProviderName\"}",
                    "{\"id\":\"" + PROVIDER_UUID + "\",\"properties\":{}}", "x".repeat(65537)}) {
                response.set(invalid);
                assertThrows(IOException.class, () -> SourceProfileLookup.lookup(identity(List.of()), source, 1000));
            }
            response.set("{\"id\":\"" + PROVIDER_UUID + "\"}");
            assertTrue(SourceProfileLookup.lookup(identity(List.of()), source, 1000).isEmpty());
            status.set(302);
            assertThrows(IOException.class, () -> SourceProfileLookup.lookup(identity(List.of()), source, 1000));
            assertEquals(0, redirectHits.get());
        } finally {
            fixture.stop(0);
        }
    }
}
