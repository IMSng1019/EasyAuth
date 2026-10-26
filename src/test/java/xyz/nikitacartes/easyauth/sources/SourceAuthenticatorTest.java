package xyz.nikitacartes.easyauth.sources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.*;

class SourceAuthenticatorTest {
    @TempDir Path temp;
    private static final String UUID_TEXT = "123456781234423482341234567890ab";
    private static final String PROFILE = "{\"id\":\"" + UUID_TEXT + "\",\"name\":\"Player\",\"properties\":[{\"name\":\"textures\",\"value\":\"original-value\",\"signature\":\"original-signature\"}]}";

    private SourcesConfig config(String sources) throws Exception {
        Path file = temp.resolve("sources.json");
        Files.writeString(file, "{\"configVersion\":1,\"parallelism\":3,\"overallTimeoutMillis\":1500,\"sources\":" + sources + "}");
        return SourcesConfig.load(file);
    }
    private String skin(String id, int priority) {
        return "{\"id\":\"" + id + "\",\"type\":\"SKIN_SITE\",\"priority\":" + priority + ",\"apiRoot\":\"https://" + id + ".test/api\",\"timeoutMillis\":200}";
    }
    private String union() {
        return "{\"id\":\"mua\",\"type\":\"UNION\",\"priority\":1,\"apiRoot\":\"https://union.test/yggdrasil\",\"mappedUrl\":\"https://union.test/profile/mapped/byuuid/{uuid}\"}";
    }

    @Test void forwardsSignedServerDigestAndEncodedNameAndProperties() throws Exception {
        AtomicReference<URI> requested = new AtomicReference<>();
        try (SourceAuthenticator auth = new SourceAuthenticator((uri, timeout) -> {
            requested.set(uri);
            return new SourceAuthenticator.Response(200, PROFILE.replace("Player", "A+B"));
        })) {
            AuthenticatedIdentity identity = auth.authenticate(config("[" + skin("skin", 1) + "]"), "A+B", "-0aBc123", InetAddress.getByName("127.0.0.1")).orElseThrow();
            Map<String, String> query = Stream.of(requested.get().getRawQuery().split("&"))
                    .map(part -> part.split("=", 2)).collect(Collectors.toMap(pair -> pair[0], pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
            assertEquals("A+B", query.get("username"));
            assertEquals("-0aBc123", query.get("serverId"));
            assertEquals("127.0.0.1", query.get("ip"));
            assertEquals("original-value", identity.properties().get(0).value());
            assertEquals("original-signature", identity.properties().get(0).signature());
            assertEquals(UUID.fromString("12345678-1234-4234-8234-1234567890ab"), identity.providerUuid());
        }
    }

    @Test void unionUsesStableInternalIdAndAllowsCanonicalRename() throws Exception {
        AtomicReference<URI> mappedRequest = new AtomicReference<>();
        try (SourceAuthenticator auth = new SourceAuthenticator((uri, timeout) -> {
            if (uri.getPath().contains("/mapped/")) {
                mappedRequest.set(uri);
                return new SourceAuthenticator.Response(200, "{\"internal_id\":42,\"uuid\":\"" + UUID_TEXT + "\",\"name\":\"Canonical_MUA\"}");
            }
            return new SourceAuthenticator.Response(200, PROFILE.replace("Player", "Canonical_MUA"));
        })) {
            AuthenticatedIdentity identity = auth.authenticate(config("[" + union() + "]"), "Original", "-123", null).orElseThrow();
            assertEquals("42", identity.subjectId());
            assertEquals("Canonical_MUA", identity.providerName());
            assertTrue(mappedRequest.get().getPath().endsWith(UUID_TEXT));
        }
    }

    @Test void unionRejectsMappedUuidMismatchAndMissingInternalId() throws Exception {
        for (String mapped : new String[]{"{\"internal_id\":42,\"uuid\":\"00000000000040008000000000000001\"}", "{\"uuid\":\"" + UUID_TEXT + "\"}"}) {
            try (SourceAuthenticator auth = new SourceAuthenticator((uri, timeout) -> new SourceAuthenticator.Response(200, uri.getPath().contains("/mapped/") ? mapped : PROFILE))) {
                assertThrows(SourceAuthenticationException.class, () -> auth.authenticate(config("[" + union() + "]"), "Player", "123", null));
            }
        }
    }

    @Test void officialSuccessDoesNotWaitForSlowLowerPrioritySource() throws Exception {
        CountDownLatch lowerStarted = new CountDownLatch(1);
        try (SourceAuthenticator auth = new SourceAuthenticator((uri, timeout) -> {
            if (uri.getHost().equals("sessionserver.mojang.com")) {
                assertTrue(lowerStarted.await(1, TimeUnit.SECONDS));
                return new SourceAuthenticator.Response(200, PROFILE);
            }
            lowerStarted.countDown();
            Thread.sleep(10_000);
            return new SourceAuthenticator.Response(204, "");
        })) {
            long started = System.nanoTime();
            AuthenticatedIdentity identity = auth.authenticate(config("[{\"id\":\"official\",\"type\":\"OFFICIAL\",\"priority\":0}," + skin("slow", 1) + "]"), "Player", "123", null).orElseThrow();
            assertEquals(SourceType.OFFICIAL, identity.type());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000);
        }
    }

    @Test void priorityWinsOverNetworkCompletionOrder() throws Exception {
        try (SourceAuthenticator auth = new SourceAuthenticator((uri, timeout) -> {
            if (uri.getHost().equals("first.test")) Thread.sleep(100);
            return new SourceAuthenticator.Response(200, PROFILE);
        })) {
            assertEquals("first", auth.authenticate(config("[" + skin("first", 1) + "," + skin("second", 2) + "]"), "Player", "123", null).orElseThrow().sourceId());
        }
    }

    @Test void transientFailureRetriesButNoMatchDoesNotRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        String source = skin("skin", 1).replace("\"timeoutMillis\":200", "\"timeoutMillis\":200,\"retries\":1");
        try (SourceAuthenticator auth = new SourceAuthenticator((uri, timeout) -> {
            if (attempts.incrementAndGet() == 1) throw new IOException("fixture offline");
            return new SourceAuthenticator.Response(200, PROFILE);
        })) {
            assertTrue(auth.authenticate(config("[" + source + "]"), "Player", "123", null).isPresent());
            assertEquals(2, attempts.get());
        }
        attempts.set(0);
        try (SourceAuthenticator auth = new SourceAuthenticator((uri, timeout) -> {
            attempts.incrementAndGet();
            return new SourceAuthenticator.Response(204, "");
        })) {
            assertTrue(auth.authenticate(config("[" + source + "]"), "Player", "123", null).isEmpty());
            assertEquals(1, attempts.get());
        }
    }

    @Test void malformedSuccessCannotDowngradeToAnotherSourceOrOffline() throws Exception {
        try (SourceAuthenticator auth = new SourceAuthenticator((uri, timeout) -> new SourceAuthenticator.Response(200, uri.getHost().equals("first.test") ? "{\"id\":\"broken\"}" : PROFILE))) {
            assertThrows(SourceAuthenticationException.class, () -> auth.authenticate(config("[" + skin("first", 1) + "," + skin("second", 2) + "]"), "Player", "123", null));
        }
    }

    @Test void skinMappedSubjectCombinesStableIdWithCreationTimestamp() throws Exception {
        String source = skin("skin", 1).replace("\"timeoutMillis\":200", "\"timeoutMillis\":200,\"mappedUrl\":\"https://skin.test/mapped/{uuid}\",\"subjectIdField\":\"role.id\",\"createdAtField\":\"role.createdAt\"");
        try (SourceAuthenticator auth = new SourceAuthenticator((uri, timeout) -> new SourceAuthenticator.Response(200,
                uri.getPath().contains("/mapped/") ? "{\"uuid\":\"" + UUID_TEXT + "\",\"role\":{\"id\":\"7\",\"createdAt\":1700000000000}}" : PROFILE))) {
            assertEquals("7@1700000000000", auth.authenticate(config("[" + source + "]"), "Player", "123", null).orElseThrow().subjectId());
        }
    }

    @Test void unionRetriesTemporaryMappedLookupFailureWithoutChangingTheProof() throws Exception {
        AtomicInteger mappingAttempts = new AtomicInteger();
        String source = union().replace("\"priority\":1", "\"priority\":1,\"retries\":1");
        try (SourceAuthenticator auth = new SourceAuthenticator((uri, timeout) -> {
            if (uri.getPath().contains("/mapped/")) {
                if (mappingAttempts.incrementAndGet() == 1) throw new IOException("temporary mapped API failure");
                return new SourceAuthenticator.Response(200, "{\"internal_id\":42,\"uuid\":\"" + UUID_TEXT + "\"}");
            }
            return new SourceAuthenticator.Response(200, PROFILE);
        })) {
            assertEquals("42", auth.authenticate(config("[" + source + "]"), "Player", "123", null).orElseThrow().subjectId());
            assertEquals(2, mappingAttempts.get());
        }
    }

    @Test void totalBudgetBoundsAnUnavailableSource() throws Exception {
        try (SourceAuthenticator auth = new SourceAuthenticator((uri, timeout) -> {
            Thread.sleep(10_000);
            throw new IOException("unavailable");
        })) {
            long started = System.nanoTime();
            assertTrue(auth.authenticate(config("[" + skin("skin", 1) + "]"), "Player", "123", null).isEmpty());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2500);
        }
    }

    @Test void realHttpRequestPreservesQueryAndSignedTexture() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/hasJoined", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = PROFILE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (SourceAuthenticator auth = new SourceAuthenticator()) {
            String source = "{\"id\":\"fixture\",\"type\":\"SKIN_SITE\",\"hasJoinedUrl\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/hasJoined\"}";
            AuthenticatedIdentity identity = auth.authenticate(config("[" + source + "]"), "Player", "-aBc123", null).orElseThrow();
            assertEquals("username=Player&serverId=-aBc123", query.get());
            assertEquals("original-signature", identity.properties().get(0).signature());
        } finally {
            server.stop(0);
        }
    }

    @Test void partialPositiveHttpResponseMustRejectInsteadOfOfflineFallback() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/hasJoined", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try (SourceAuthenticator auth = new SourceAuthenticator()) {
            String source = "{\"id\":\"fixture\",\"type\":\"SKIN_SITE\",\"timeoutMillis\":100,\"hasJoinedUrl\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/hasJoined\"}";
            assertThrows(SourceAuthenticationException.class, () -> auth.authenticate(config("[" + source + "]"), "Player", "123", null));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test void saturatedCustomWorkersCannotConsumeOfficialCapacity() throws Exception {
        CountDownLatch customStarted = new CountDownLatch(64);
        CountDownLatch releaseCustom = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(4);
        try (SourceAuthenticator auth = new SourceAuthenticator((uri, timeout) -> {
            if (uri.getHost().equals("sessionserver.mojang.com")) return new SourceAuthenticator.Response(200, PROFILE);
            customStarted.countDown();
            boolean released = false;
            while (!released) {
                try { released = releaseCustom.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* Simulate stalled DNS/read that does not honor interruption. */ }
            }
            return new SourceAuthenticator.Response(204, "");
        })) {
            String sources = java.util.stream.IntStream.range(0, 16).mapToObj(i -> skin("custom" + i, i)).collect(Collectors.joining(","));
            Path file = temp.resolve("saturated.json");
            Files.writeString(file, "{\"configVersion\":1,\"parallelism\":16,\"overallTimeoutMillis\":3000,\"sources\":[" + sources + "]}");
            SourcesConfig custom = SourcesConfig.load(file);
            for (int i = 0; i < 4; i++) callers.submit(() -> auth.authenticate(custom, "Player", "123", null));
            // Old shared capacity reaches 64; isolated capacity deliberately rejects overflow.
            customStarted.await(1, TimeUnit.SECONDS);
            SourcesConfig official = config("[{\"id\":\"official\",\"type\":\"OFFICIAL\"}]");
            assertEquals(SourceType.OFFICIAL, auth.authenticate(official, "Player", "123", null).orElseThrow().type());
        } finally {
            releaseCustom.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
