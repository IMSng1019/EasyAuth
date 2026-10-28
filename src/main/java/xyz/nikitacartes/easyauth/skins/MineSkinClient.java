package xyz.nikitacartes.easyauth.skins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded MineSkin v2 queue client; callers validate returned texture signatures. */
public final class MineSkinClient implements SkinGenerator {
    static final int MAX_RESPONSE_BYTES = 131_072;
    private static final String API = "https://api.mineskin.org/v2/";
    private static final long POLL_INTERVAL_MILLIS = 1000;
    private static final TimeSource SYSTEM_TIME = new TimeSource() {
        @Override public long monotonicMillis() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
        @Override public long epochMillis() { return System.currentTimeMillis(); }
    };

    private final SkinBridgeConfig config;
    private final Transport transport;
    private final TimeSource time;
    private final Sleeper sleeper;
    private final Map<String, String> headers;
    private final AtomicLong nextSubmission = new AtomicLong(Long.MIN_VALUE);

    @FunctionalInterface
    interface Transport {
        Response send(Request request) throws IOException, InterruptedException;
    }

    interface TimeSource {
        long monotonicMillis();
        long epochMillis();
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    record Request(String method, URI uri, String body, Map<String, String> headers, int timeoutMillis) {
        // Never expose Authorization (including through an exception or debug formatter).
        @Override public String toString() { return "MineSkin request " + method + " " + uri; }
    }
    record Response(int status, Map<String, String> headers, String body) {}

    public MineSkinClient(String apiKey, SkinBridgeConfig config) {
        this(apiKey, config, new HttpTransport(config.requestTimeoutMillis()), SYSTEM_TIME, Thread::sleep);
    }

    MineSkinClient(String apiKey, SkinBridgeConfig config, Transport transport, TimeSource time, Sleeper sleeper) {
        if (apiKey == null || apiKey.isEmpty() || apiKey.length() > 4096
                || apiKey.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw new IllegalArgumentException("MineSkin API key must be a nonempty header-safe token");
        }
        this.config = Objects.requireNonNull(config);
        this.transport = Objects.requireNonNull(transport);
        this.time = Objects.requireNonNull(time);
        this.sleeper = Objects.requireNonNull(sleeper);
        this.headers = Map.of("Authorization", "Bearer " + apiKey,
                "User-Agent", "EasyAuth-SkinBridge/1", "Accept", "application/json");
    }

    @Override
    public TextureProperty generate(SkinDescriptor descriptor) throws Exception {
        Objects.requireNonNull(descriptor);
        long deadline = addSaturated(time.monotonicMillis(), config.generationTimeoutMillis());
        JsonObject body = new JsonObject();
        body.addProperty("url", descriptor.url().toString());
        body.addProperty("variant", descriptor.variant());
        body.addProperty("visibility", "unlisted");

        JsonObject reply;
        while (true) {
            waitUntil(nextSubmission.get(), deadline);
            Response response = request("POST", URI.create(API + "queue"), body.toString(), deadline);
            JsonObject optionalBody = optionalObject(response.body());
            long nextDelay = requestedDelay(response, optionalBody);
            if (nextDelay > 0) {
                nextSubmission.accumulateAndGet(addSaturated(time.monotonicMillis(), nextDelay), Math::max);
            }
            if (response.status() == 429) {
                // A rejected request can be retried; an ambiguous transport/5xx failure cannot.
                nextSubmission.accumulateAndGet(addSaturated(time.monotonicMillis(),
                        Math.max(POLL_INTERVAL_MILLIS, nextDelay)), Math::max);
                continue;
            }
            requireSuccessStatus(response, true);
            reply = requireObject(optionalBody);
            requireSuccessBody(reply);
            break;
        }

        String expectedJobId = null;
        while (true) {
            JsonObject job = objectField(reply, "job");
            String status = stringField(job, "status", 32);
            String id = optionalString(job, "id", 256);
            if (id != null && id.codePoints().anyMatch(Character::isISOControl)) throw invalidResponse();
            if (expectedJobId != null && id != null && !expectedJobId.equals(id)) throw invalidResponse();
            if (status.equals("failed")) throw new IOException("MineSkin generation failed");
            if (status.equals("completed")) {
                if (reply.has("skin") && !reply.get("skin").isJsonNull()) return texture(objectField(reply, "skin"));
                String result = stringField(job, "result", 128);
                // MineSkin can identify a stored skin with a UUID or a short opaque token.
                // Keep it one safe path segment; never accept a URL, dot segment or query.
                if (!result.matches("[A-Za-z0-9_-]{1,128}")) {
                    throw invalidResponse();
                }
                JsonObject skinReply = getWithRateLimit(URI.create(API + "skins/" + result), deadline);
                // The SDK uses {skin: ...}; the published OpenAPI also describes bare SkinInfo.
                return texture(skinReply.has("skin") ? objectField(skinReply, "skin") : skinReply);
            }
            if (!status.equals("waiting") && !status.equals("active") && !status.equals("unknown")) throw invalidResponse();
            if (id == null) throw invalidResponse();
            expectedJobId = id;
            sleep(POLL_INTERVAL_MILLIS, deadline);
            String segment = URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
            reply = getWithRateLimit(URI.create(API + "queue/" + segment), deadline);
        }
    }

    private JsonObject getWithRateLimit(URI uri, long deadline) throws IOException, InterruptedException {
        while (true) {
            Response response = request("GET", uri, null, deadline);
            JsonObject body = optionalObject(response.body());
            if (response.status() == 429) {
                sleep(Math.max(POLL_INTERVAL_MILLIS, requestedDelay(response, body)), deadline);
                continue;
            }
            requireSuccessStatus(response, true);
            body = requireObject(body);
            requireSuccessBody(body);
            return body;
        }
    }

    private Response request(String method, URI uri, String body, long deadline) throws IOException, InterruptedException {
        int timeout = (int) Math.min(config.requestTimeoutMillis(), remaining(deadline));
        Response response;
        try {
            response = transport.send(new Request(method, uri, body, headers, timeout));
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (IOException | RuntimeException failure) {
            // Transport exceptions may include request/header text. Do not retain their cause.
            throw new IOException("MineSkin HTTP request failed");
        }
        remaining(deadline);
        if (response == null || response.body() == null || response.body().length() > MAX_RESPONSE_BYTES
                || response.body().getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            throw new IOException("MineSkin response exceeded its body limit");
        }
        return response;
    }

    private long remaining(long deadline) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("MineSkin request interrupted");
        long remaining = deadline - time.monotonicMillis();
        if (remaining <= 0) throw new IOException("MineSkin generation deadline exceeded");
        return remaining;
    }

    private void waitUntil(long instant, long deadline) throws IOException, InterruptedException {
        // Recheck after an early/spurious sleep and when another request extends the global limit.
        while (instant > time.monotonicMillis()) {
            sleep(instant - time.monotonicMillis(), deadline);
            instant = nextSubmission.get();
        }
        remaining(deadline);
    }

    private void sleep(long millis, long deadline) throws IOException, InterruptedException {
        if (millis >= remaining(deadline)) throw new IOException("MineSkin wait exceeds generation deadline");
        long until = addSaturated(time.monotonicMillis(), millis);
        while (time.monotonicMillis() < until) {
            sleeper.sleep(Math.min(until - time.monotonicMillis(), remaining(deadline)));
        }
        remaining(deadline);
    }

    private long requestedDelay(Response response, JsonObject body) {
        long delay = 0;
        if (response.headers() != null) {
            for (var header : response.headers().entrySet()) {
                if (header.getKey().equalsIgnoreCase("Retry-After")) {
                    delay = Math.max(delay, retryAfter(header.getValue()));
                }
            }
        }
        JsonObject rateLimit = optionalObjectField(body, "rateLimit");
        JsonObject next = optionalObjectField(rateLimit, "next");
        if (next != null) {
            delay = Math.max(delay, nonnegativeNumber(next.get("relative")));
            long absolute = nonnegativeNumber(next.get("absolute"));
            if (absolute > time.epochMillis()) delay = Math.max(delay, absolute - time.epochMillis());
        }
        return delay;
    }

    private long retryAfter(String value) {
        if (value == null) return 0;
        try {
            BigDecimal seconds = new BigDecimal(value.strip());
            if (seconds.signum() < 0) return 0;
            return saturatedMillis(seconds.multiply(BigDecimal.valueOf(1000)));
        } catch (NumberFormatException invalidSeconds) {
            try {
                long instant = ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                return instant > time.epochMillis() ? instant - time.epochMillis() : 0;
            } catch (DateTimeParseException | ArithmeticException invalidDate) {
                return 0;
            }
        }
    }

    private static long nonnegativeNumber(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return 0;
        try {
            BigDecimal number = value.getAsBigDecimal();
            return number.signum() < 0 ? 0 : saturatedMillis(number);
        } catch (NumberFormatException invalidNumber) {
            return 0;
        }
    }

    private static long saturatedMillis(BigDecimal value) {
        if (value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) >= 0) return Long.MAX_VALUE;
        return value.setScale(0, RoundingMode.CEILING).longValue();
    }

    private static long addSaturated(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static void requireSuccessStatus(Response response, boolean allowAccepted) throws IOException {
        if (response.status() != 200 && !(allowAccepted && response.status() == 202)) {
            throw new IOException("MineSkin returned HTTP " + response.status());
        }
    }

    private static void requireSuccessBody(JsonObject body) throws IOException {
        if (!body.has("success")) return;
        JsonElement success = body.get("success");
        if (!success.isJsonPrimitive() || !success.getAsJsonPrimitive().isBoolean() || !success.getAsBoolean()) {
            throw new IOException("MineSkin reported an unsuccessful response");
        }
    }

    private static JsonObject optionalObject(String body) {
        try {
            JsonElement value = JsonParser.parseString(body);
            return value.isJsonObject() ? value.getAsJsonObject() : null;
        } catch (RuntimeException invalidJson) {
            return null;
        }
    }

    private static JsonObject requireObject(JsonObject object) throws IOException {
        if (object == null) throw invalidResponse();
        return object;
    }

    private static JsonObject optionalObjectField(JsonObject object, String field) {
        if (object == null) return null;
        JsonElement value = object.get(field);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonObject objectField(JsonObject object, String field) throws IOException {
        return requireObject(optionalObjectField(object, field));
    }

    private static String optionalString(JsonObject object, String field, int maxLength) throws IOException {
        return !object.has(field) || object.get(field).isJsonNull() ? null : stringField(object, field, maxLength);
    }

    private static String stringField(JsonObject object, String field, int maxLength) throws IOException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalidResponse();
        String text = value.getAsString();
        if (text.isBlank() || text.length() > maxLength) throw invalidResponse();
        return text;
    }

    private static TextureProperty texture(JsonObject skin) throws IOException {
        JsonObject data = objectField(objectField(skin, "texture"), "data");
        try {
            return new TextureProperty(stringField(data, "value", 32768), stringField(data, "signature", 8192));
        } catch (IllegalArgumentException invalidProperty) {
            throw invalidResponse();
        }
    }

    private static IOException invalidResponse() {
        return new IOException("MineSkin returned an invalid response");
    }

    static final class HttpTransport implements Transport {
        private final HttpClient client;

        HttpTransport(int connectTimeoutMillis) {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        }

        @Override
        public Response send(Request request) throws IOException, InterruptedException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                    .timeout(Duration.ofMillis(request.timeoutMillis()));
            request.headers().forEach(builder::header);
            if (request.body() == null) builder.GET();
            else builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8));
            CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(builder.build(), ignored -> new LimitedBodySubscriber());
            try {
                // Covers headers AND a stalled body; ofInputStream alone would stop timing at headers.
                HttpResponse<byte[]> response = pending.get(request.timeoutMillis(), TimeUnit.MILLISECONDS);
                Map<String, String> responseHeaders = new HashMap<>();
                response.headers().map().forEach((name, values) -> {
                    if (!values.isEmpty()) responseHeaders.put(name, values.get(0));
                });
                return new Response(response.statusCode(), Map.copyOf(responseHeaders),
                        new String(response.body(), StandardCharsets.UTF_8));
            } catch (TimeoutException timeout) {
                pending.cancel(true);
                throw new IOException("MineSkin HTTP response timed out");
            } catch (ExecutionException failure) {
                pending.cancel(true);
                throw new IOException("MineSkin HTTP response failed");
            } catch (InterruptedException interrupted) {
                pending.cancel(true);
                throw interrupted;
            }
        }
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return body; }

        @Override public void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null) { incoming.cancel(); return; }
            subscription = incoming;
            incoming.request(1);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("MineSkin response exceeded its body limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable failure) {
            body.completeExceptionally(new IOException("MineSkin response body failed"));
        }

        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
