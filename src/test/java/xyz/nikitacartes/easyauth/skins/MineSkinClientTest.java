package xyz.nikitacartes.easyauth.skins;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MineSkinClientTest {
    private static final String KEY = "fixture-secret-key";
    private static final String RESULT_ID = "123456781234423482341234567890ab";
    private static final String VALUE = Base64.getEncoder().encodeToString(
            "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/0123\",\"metadata\":{\"model\":\"slim\"}}}}"
                    .getBytes(StandardCharsets.UTF_8));
    private static final String SIGNATURE = Base64.getEncoder().encodeToString("fixture-signature".getBytes(StandardCharsets.UTF_8));
    private static final String SKIN = "{\"uuid\":\"" + RESULT_ID + "\",\"variant\":\"slim\",\"texture\":{\"data\":{\"value\":\""
            + VALUE + "\",\"signature\":\"" + SIGNATURE + "\"}}}";
    private static final SkinDescriptor INPUT = new SkinDescriptor(URI.create("https://skin.example.org/textures/one.png"), "slim");

    private static SkinBridgeConfig config(int generationMillis) {
        return new SkinBridgeConfig(true, 1000, 5000, generationMillis, 24, 64, 60);
    }

    private static String job(String status) {
        return "{\"success\":true,\"job\":{\"id\":\"opaque:job 42\",\"status\":\"" + status + "\"}}";
    }

    private static String completed() {
        return "{\"success\":true,\"job\":{\"id\":\"opaque:job 42\",\"status\":\"completed\"},\"skin\":" + SKIN + "}";
    }

    @Test void submitsUnlistedUrlToFixedHostWithoutPlayerNameAndPreservesSignedBytes() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(200, completed());
        TextureProperty result = fixture.client().generate(INPUT);
        assertEquals(VALUE, result.value());
        assertEquals(SIGNATURE, result.signature());
        assertEquals(1, fixture.requests.size());
        var request = fixture.requests.get(0);
        assertEquals("POST", request.method());
        assertEquals(URI.create("https://api.mineskin.org/v2/queue"), request.uri());
        assertEquals("Bearer " + KEY, request.headers().get("Authorization"));
        assertTrue(request.headers().get("User-Agent").startsWith("EasyAuth"));
        var body = JsonParser.parseString(request.body()).getAsJsonObject();
        assertEquals(INPUT.url().toString(), body.get("url").getAsString());
        assertEquals("slim", body.get("variant").getAsString());
        assertEquals("unlisted", body.get("visibility").getAsString());
        assertFalse(body.has("name"));
    }

    @Test void pollsWaitingAndActiveAtMostOncePerSecondUsingOpaqueJobId() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(202, job("waiting"));
        fixture.add(200, job("active"));
        fixture.add(200, completed());
        assertEquals(VALUE, fixture.client().generate(INPUT).value());
        assertEquals(List.of(1000L, 1000L), fixture.sleeps);
        assertEquals("/v2/queue/opaque%3Ajob%2042", fixture.requests.get(1).uri().getRawPath());
        assertEquals("GET", fixture.requests.get(1).method());
        assertEquals(List.of(0L, 1000L, 2000L), fixture.requestTimes);
    }

    @Test void completedResultCanFetchWrappedOrBareSkinResponses() throws Exception {
        for (String fetched : List.of("{\"success\":true,\"skin\":" + SKIN + "}", SKIN)) {
            Fixture fixture = new Fixture();
            fixture.add(200, "{\"success\":true,\"job\":{\"id\":\"job-42\",\"status\":\"completed\",\"result\":\"" + RESULT_ID + "\"}}");
            fixture.add(200, fetched);
            assertEquals(SIGNATURE, fixture.client().generate(INPUT).signature());
            assertEquals("https://api.mineskin.org/v2/skins/" + RESULT_ID, fixture.requests.get(1).uri().toString());
        }
    }

    @Test void completedResultCanUseShortOpaqueSkinId() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(200, "{\"success\":true,\"job\":{\"status\":\"completed\",\"result\":\"abc123_x-y\"}}");
        fixture.add(200, "{\"success\":true,\"skin\":" + SKIN + "}");
        assertEquals(VALUE, fixture.client().generate(INPUT).value());
        assertEquals("https://api.mineskin.org/v2/skins/abc123_x-y", fixture.requests.get(1).uri().toString());
    }

    @Test void cachedImmediateCompletionDoesNotRequireJobIdWhenSkinIsPresent() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(200, completed().replace("\"id\":\"opaque:job 42\",", ""));
        assertEquals(VALUE, fixture.client().generate(INPUT).value());
        assertEquals(1, fixture.requests.size());
    }

    @Test void failedJobWinsOverHttpSuccessAndEmbeddedTexture() {
        Fixture fixture = new Fixture();
        fixture.add(200, completed().replace("completed", "failed"));
        assertThrows(IOException.class, () -> fixture.client().generate(INPUT));
        assertEquals(1, fixture.requests.size());
    }

    @Test void unknownJobStatusIsBoundedByGenerationDeadline() {
        Fixture fixture = new Fixture();
        fixture.repeat = response(200, job("unknown"));
        assertThrows(IOException.class, () -> fixture.client(2500).generate(INPUT));
        assertTrue(fixture.requests.size() <= 3);
        assertTrue(fixture.time <= 2500);
        assertEquals(1, fixture.requests.stream().filter(request -> request.method().equals("POST")).count());
    }

    @Test void honorsLongerOfRetryAfterAndJsonRelativeDelay() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(new MineSkinClient.Response(429, Map.of("rEtRy-AfTeR", "2"),
                "{\"success\":false,\"rateLimit\":{\"next\":{\"relative\":2500}}}"));
        fixture.add(200, completed());
        fixture.client().generate(INPUT);
        assertEquals(List.of(2500L), fixture.sleeps);
        assertEquals(List.of(0L, 2500L), fixture.requestTimes);
    }

    @Test void honorsHttpDateRetryAfterAndAbsoluteMilliseconds() throws Exception {
        Fixture fixture = new Fixture();
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(fixture.epochMillis() + 4000).atZone(ZoneOffset.UTC));
        fixture.add(new MineSkinClient.Response(429, Map.of("Retry-After", date),
                "{\"rateLimit\":{\"next\":{\"absolute\":" + (fixture.epochMillis() + 3000) + "}}}"));
        fixture.add(200, completed());
        fixture.client().generate(INPUT);
        assertEquals(List.of(4000L), fixture.sleeps);
    }

    @Test void respectsRetryAfterOnNonJsonRateLimitResponse() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(new MineSkinClient.Response(429, Map.of("Retry-After", "3"), "<html>rate limited</html>"));
        fixture.add(200, completed());
        fixture.client().generate(INPUT);
        assertEquals(List.of(3000L), fixture.sleeps);
    }

    @Test void doesNotShortenRateLimitDelayToFitDeadline() {
        Fixture fixture = new Fixture();
        fixture.add(new MineSkinClient.Response(429, Map.of("Retry-After", "120"), "{}"));
        assertThrows(IOException.class, () -> fixture.client(2500).generate(INPUT));
        assertEquals(1, fixture.requests.size());
        assertTrue(fixture.sleeps.isEmpty());
    }

    @Test void generationRateLimitPersistsAcrossCallsButDoesNotDelayExistingJobPolls() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(202, job("waiting").replace("}}", "},\"rateLimit\":{\"next\":{\"relative\":5000}}}"));
        fixture.add(200, completed());
        fixture.add(200, completed());
        MineSkinClient client = fixture.client();
        client.generate(INPUT);
        client.generate(INPUT);
        assertEquals(List.of(0L, 1000L, 5000L), fixture.requestTimes);
    }

    @Test void rateLimitedPollingNeverSubmitsASecondGeneration() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(202, job("waiting"));
        fixture.add(new MineSkinClient.Response(429, Map.of("Retry-After", "2"), "{}"));
        fixture.add(200, completed());
        fixture.client().generate(INPUT);
        assertEquals(List.of(0L, 1000L, 3000L), fixture.requestTimes);
        assertEquals(1, fixture.requests.stream().filter(request -> request.method().equals("POST")).count());
    }

    @Test void rejectsMalformedJobsPropertiesAndUnsuccessfulBodies() {
        for (String malformed : List.of("{}", "[]", "not-json", job("new-status"),
                job("waiting").replace("\"id\":\"opaque:job 42\",", ""),
                completed().replace("\"success\":true", "\"success\":false"),
                completed().replace(SIGNATURE, ""),
                completed().replace("\"signature\":\"" + SIGNATURE + "\"", "\"signature\":null"),
                completed().replace("\"value\":\"" + VALUE + "\"", "\"value\":123"),
                job("completed"))) {
            Fixture fixture = new Fixture();
            fixture.add(200, malformed);
            assertThrows(IOException.class, () -> fixture.client().generate(INPUT), malformed);
        }
    }

    @Test void rejectsChangedJobIdentityAndInvalidResultId() {
        Fixture fixture = new Fixture();
        fixture.add(202, job("waiting"));
        fixture.add(200, completed().replace("opaque:job 42", "another-job"));
        assertThrows(IOException.class, () -> fixture.client().generate(INPUT));
        for (String injected : List.of("https://other.invalid/steal", "../other", ".", "..", "skin?query=1",
                "skin#fragment", "%2e%2e", "a".repeat(129))) {
            Fixture malicious = new Fixture();
            malicious.add(200, "{\"success\":true,\"job\":{\"status\":\"completed\",\"result\":\"" + injected + "\"}}");
            assertThrows(IOException.class, () -> malicious.client().generate(INPUT), injected);
            assertEquals(1, malicious.requests.size(), "Invalid result IDs must not trigger a second HTTP request");
        }
    }

    @Test void nonRetryableStatusAndTransportFailureDoNotLeakBodyOrKeyOrResubmit() {
        for (int status : List.of(302, 400, 401, 403, 404, 500)) {
            Fixture fixture = new Fixture();
            fixture.add(status, "{\"errors\":[{\"message\":\"" + KEY + "\"}]}");
            IOException failure = assertThrows(IOException.class, () -> fixture.client().generate(INPUT));
            assertFalse(failure.toString().contains(KEY));
            assertEquals(1, fixture.requests.size());
        }
        Fixture fixture = new Fixture();
        MineSkinClient client = new MineSkinClient(KEY, config(10000), request -> { throw new IOException(KEY); }, fixture, fixture);
        IOException failure = assertThrows(IOException.class, () -> client.generate(INPUT));
        assertFalse(failure.toString().contains(KEY));
        assertNull(failure.getCause(), "A transport exception could expose a secret through its message");
    }

    @Test void usesRemainingDeadlineForEachRequestAndRejectsOversizedBody() {
        Fixture fixture = new Fixture();
        fixture.add(202, job("waiting"));
        fixture.repeat = response(200, job("active"));
        assertThrows(IOException.class, () -> fixture.client(2500).generate(INPUT));
        assertEquals(2500, fixture.requests.get(0).timeoutMillis());
        assertEquals(1500, fixture.requests.get(1).timeoutMillis());
        Fixture huge = new Fixture();
        huge.add(200, " ".repeat(MineSkinClient.MAX_RESPONSE_BYTES + 1));
        assertThrows(IOException.class, () -> huge.client().generate(INPUT));
    }

    @Test void rejectsMissingOrHeaderInjectingApiKey() {
        for (String key : List.of("", " ", "key\r\nOther: value")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new MineSkinClient(key, config(10000)));
            assertFalse(failure.toString().contains("Other:"));
        }
    }

    @Test void acceptsConfiguredApiKeyLengthLimitAndRejectsOneCharacterOver() {
        // Constructor validation only: no request is made with either synthetic value.
        Fixture fixture = new Fixture();
        assertDoesNotThrow(() -> new MineSkinClient("k".repeat(4096), config(10000), fixture, fixture, fixture));
        assertThrows(IllegalArgumentException.class,
                () -> new MineSkinClient("k".repeat(4097), config(10000), fixture, fixture, fixture));
        assertTrue(fixture.requests.isEmpty());
    }

    @Test void interruptionStopsPollingWithoutResubmitting() {
        Fixture fixture = new Fixture();
        fixture.add(202, job("waiting"));
        MineSkinClient client = new MineSkinClient(KEY, config(10000), fixture, fixture,
                millis -> { throw new InterruptedException("test cancellation"); });
        assertThrows(InterruptedException.class, () -> client.generate(INPUT));
        assertEquals(1, fixture.requests.size());
    }

    @Test void actualHttpTransportDoesNotFollowRedirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/elsewhere", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            var response = new MineSkinClient.HttpTransport(1000).send(localRequest(server, "/start", 1000));
            assertEquals(302, response.status());
            assertEquals(0, redirected.get());
        } finally { server.stop(0); }
    }

    @Test void actualHttpTransportBoundsChunkedBodyAndStalledBodyTime() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger largeRequests = new AtomicInteger();
        AtomicInteger stalledRequests = new AtomicInteger();
        server.createContext("/large", exchange -> {
            largeRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            try { exchange.getResponseBody().write(new byte[MineSkinClient.MAX_RESPONSE_BYTES + 1]); }
            catch (IOException expectedCancellation) { }
            finally { exchange.close(); }
        });
        server.createContext("/stall", exchange -> {
            stalledRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var transport = new MineSkinClient.HttpTransport(1000);
            assertThrows(IOException.class, () -> transport.send(localRequest(server, "/large", 1000)));
            assertEquals(1, largeRequests.get(), "Oversized-body check must reach the HTTP fixture");
            long started = System.nanoTime();
            assertThrows(IOException.class, () -> transport.send(localRequest(server, "/stall", 400)));
            assertEquals(1, stalledRequests.get(), "Timeout check must exercise a stalled response body");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2500,
                    "Stalled response escaped its request deadline");
        } finally { release.countDown(); server.stop(0); }
    }

    private static MineSkinClient.Request localRequest(HttpServer server, String path, int timeout) {
        return new MineSkinClient.Request("GET", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path),
                null, Map.of("Authorization", "Bearer " + KEY), timeout);
    }

    private static MineSkinClient.Response response(int status, String body) {
        return new MineSkinClient.Response(status, Map.of(), body);
    }

    private static final class Fixture implements MineSkinClient.Transport, MineSkinClient.TimeSource, MineSkinClient.Sleeper {
        final ArrayDeque<MineSkinClient.Response> responses = new ArrayDeque<>();
        final List<MineSkinClient.Request> requests = new ArrayList<>();
        final List<Long> requestTimes = new ArrayList<>();
        final List<Long> sleeps = new ArrayList<>();
        MineSkinClient.Response repeat;
        long time;

        void add(int status, String body) { add(response(status, body)); }
        void add(MineSkinClient.Response response) { responses.add(response); }
        MineSkinClient client() { return client(10000); }
        MineSkinClient client(int generationMillis) { return new MineSkinClient(KEY, config(generationMillis), this, this, this); }
        @Override public MineSkinClient.Response send(MineSkinClient.Request request) throws IOException {
            requests.add(request);
            requestTimes.add(time);
            if (!responses.isEmpty()) return responses.remove();
            if (repeat != null) return repeat;
            throw new IOException("Unexpected extra HTTP request");
        }
        @Override public long monotonicMillis() { return time; }
        @Override public long epochMillis() { return 1_700_000_000_000L + time; }
        @Override public void sleep(long millis) { assertTrue(millis > 0); sleeps.add(millis); time += millis; }
    }
}
