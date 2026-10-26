package xyz.nikitacartes.easyauth.sources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded independent provider requests, selected by configured priority rather than response order. */
public final class SourceAuthenticator implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(SourceAuthenticator.class.getName());
    private static final int MAX_RESPONSE_BYTES = 65_536;
    private final Transport transport;
    private final ThreadPoolExecutor officialWorkers;
    private final ThreadPoolExecutor customWorkers;

    public SourceAuthenticator() {
        this(null);
    }

    SourceAuthenticator(Transport transport) {
        this.transport = transport;
        // Custom DNS/socket reads may ignore cancellation until their own timeout. They must
        // never occupy the capacity reserved for Mojang authentication on other connections.
        officialWorkers = workerPool(16, "official");
        customWorkers = workerPool(48, "custom");
    }

    private static ThreadPoolExecutor workerPool(int capacity, String authority) {
        AtomicInteger threadNumber = new AtomicInteger();
        // No unbounded request/thread queue under login floods. Rejected work is an unavailable source.
        return new ThreadPoolExecutor(0, capacity, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "EasyAuth-source-" + authority + "-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @FunctionalInterface
    interface Transport {
        Response get(URI uri, int timeoutMillis) throws IOException, InterruptedException;
    }

    record Response(int status, String body) { }

    private static final class Attempt {
        final SourcesConfig.Source source;
        final AtomicBoolean positive = new AtomicBoolean();
        Future<Optional<AuthenticatedIdentity>> future;

        Attempt(SourcesConfig.Source source) { this.source = source; }
    }

    public Optional<AuthenticatedIdentity> authenticate(SourcesConfig config, String username, String serverId,
                                                       InetAddress address) throws SourceAuthenticationException {
        if (username == null || username.isEmpty() || username.length() > 16 || hasControl(username)
                || serverId == null || !serverId.matches("-?[0-9a-fA-F]{1,40}")) {
            throw new SourceAuthenticationException("Invalid login name or server digest");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.overallTimeoutMillis());
        List<Attempt> attempts = config.sources().stream().filter(SourcesConfig.Source::enabled).map(Attempt::new).toList();
        int launched = 0;
        try {
            while (launched < attempts.size() && launched < config.parallelism()) {
                launch(attempts.get(launched++), username, serverId, address, deadline);
            }
            for (int current = 0; current < attempts.size(); current++) {
                Attempt attempt = attempts.get(current);
                if (attempt.future != null) {
                    try {
                        Optional<AuthenticatedIdentity> identity = attempt.future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                        if (identity.isPresent()) return identity;
                    } catch (TimeoutException e) {
                        attempt.future.cancel(true);
                        if (attempt.positive.get()) throw rejected(attempt.source, "authenticated response could not be resolved before the deadline", e);
                        unavailable(attempt.source, "request deadline exceeded");
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof SourceAuthenticationException rejected) throw rejected;
                        if (e.getCause() instanceof InterruptedException) {
                            if (attempt.positive.get()) throw rejected(attempt.source, "identity lookup was interrupted", e);
                            unavailable(attempt.source, "request interrupted");
                        } else {
                            // Unexpected provider/protocol bugs must never silently downgrade authentication.
                            throw rejected(attempt.source, "authentication failed unexpectedly", e.getCause());
                        }
                    }
                }
                if (launched < attempts.size() && System.nanoTime() < deadline) {
                    launch(attempts.get(launched++), username, serverId, address, deadline);
                }
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceAuthenticationException("Authentication was interrupted", e);
        } finally {
            for (Attempt attempt : attempts) if (attempt.future != null && !attempt.future.isDone()) attempt.future.cancel(true);
        }
    }

    private void launch(Attempt attempt, String username, String serverId, InetAddress address, long deadline) {
        try {
            ThreadPoolExecutor workers = attempt.source.type() == SourceType.OFFICIAL ? officialWorkers : customWorkers;
            attempt.future = workers.submit(() -> authenticateSource(attempt, username, serverId, address, deadline));
        } catch (RejectedExecutionException e) {
            unavailable(attempt.source, "authentication worker capacity reached");
        }
    }

    private Optional<AuthenticatedIdentity> authenticateSource(Attempt attempt, String username, String serverId,
                                                               InetAddress address, long deadline)
            throws SourceAuthenticationException, InterruptedException {
        SourcesConfig.Source source = attempt.source;
        String query = "?username=" + encode(username) + "&serverId=" + encode(serverId);
        if (address != null) query += "&ip=" + encode(address.getHostAddress());
        Response response;
        try {
            response = request(source, URI.create(source.hasJoinedUrl() + query), deadline, attempt.positive);
        } catch (IOException e) {
            if (attempt.positive.get()) throw rejected(source, "incomplete authenticated response", e);
            unavailable(source, e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (response.status() == 204 || response.status() == 404 || response.status() == 401 || response.status() == 403) return Optional.empty();
        if (response.status() != 200) {
            unavailable(source, "HTTP " + response.status());
            return Optional.empty();
        }
        attempt.positive.set(true);
        try {
            JsonObject profile = object(response.body());
            UUID uuid = uuid(requiredString(profile, "id"));
            String name = requiredString(profile, "name");
            if (name.isBlank() || name.length() > 16 || hasControl(name)) throw new IllegalArgumentException("invalid profile name");
            if (source.type() != SourceType.UNION && !name.equalsIgnoreCase(username)) throw new IllegalArgumentException("profile name does not match the authenticated login");
            List<AuthenticatedIdentity.PropertyData> properties = properties(profile);
            String subject = uuid.toString();
            if (source.mappedUrl() != null) {
                URI mappedUri = URI.create(source.mappedUrl().replace("{uuid}", uuid.toString().replace("-", "")));
                Response mappedResponse = request(source, mappedUri, deadline, attempt.positive);
                if (mappedResponse.status() != 200) throw new IllegalArgumentException("mapped identity lookup returned HTTP " + mappedResponse.status());
                JsonObject mapped = object(mappedResponse.body());
                if (!uuid(requiredString(mapped, "uuid")).equals(uuid)) throw new IllegalArgumentException("mapped UUID differs from the authenticated UUID");
                subject = scalar(mapped, source.subjectIdField());
                if (source.createdAtField() != null) {
                    String created = scalar(mapped, source.createdAtField());
                    if (!created.matches("[0-9]{1,19}")) throw new IllegalArgumentException("createdAtField must be a nonnegative Unix timestamp");
                    subject = subject.replace("%", "%25").replace("@", "%40") + "@" + created;
                }
            }
            return Optional.of(new AuthenticatedIdentity(source.id(), source.type(), subject, uuid, name, properties));
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            throw rejected(source, "invalid authenticated profile: " + e.getMessage(), e);
        }
    }

    private Response request(SourcesConfig.Source source, URI uri, long deadline, AtomicBoolean positive) throws IOException, InterruptedException {
        IOException last = null;
        for (int retry = 0; retry <= source.retries(); retry++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) throw new IOException("request deadline exceeded");
            boolean positiveBeforeAttempt = positive.get();
            try {
                int timeout = (int) Math.min(source.timeoutMillis(), remaining);
                Response response = transport == null ? httpGet(uri, timeout, positive) : transport.get(uri, timeout);
                if (response.status() == 200) positive.set(true);
                if (response.body() == null || response.body().length() > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("provider response exceeds 64 KiB");
                if (response.status() == 429 || response.status() >= 500) {
                    last = new IOException("HTTP " + response.status());
                    continue;
                }
                return response;
            } catch (IOException e) {
                // Once a provider claims this session, incomplete bodies may not downgrade it.
                if (!positiveBeforeAttempt && positive.get()) throw e;
                last = e;
            }
        }
        throw last == null ? new IOException("provider unavailable") : last;
    }

    private static Response httpGet(URI uri, int timeoutMillis, AtomicBoolean positive) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "EasyAuth-MultiSource/1");
        try {
            int status = connection.getResponseCode();
            // Error/negative response bodies cannot authenticate anyone and need not be buffered.
            if (status != 200) return new Response(status, "");
            // Publish at headers, before a stalled/truncated body can exhaust the overall deadline.
            positive.set(true);
            if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("provider response exceeds 64 KiB");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new IOException("response read deadline exceeded");
                    if (output.size() + count > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("provider response exceeds 64 KiB");
                    output.write(buffer, 0, count);
                }
                return new Response(status, output.toString(StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static JsonObject object(String body) {
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("expected a JSON object");
        return parsed.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("missing/invalid " + field);
        }
        return value.getAsString();
    }

    private static String scalar(JsonObject object, String path) {
        JsonElement value = object;
        for (String part : path.split("\\.")) {
            if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("missing mapped subject field " + path);
            value = value.getAsJsonObject().get(part);
        }
        if (value == null || !value.isJsonPrimitive() || value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("invalid mapped subject field " + path);
        String text = value.getAsString();
        if (text.isBlank() || text.length() > 256 || hasControl(text)) throw new IllegalArgumentException("invalid mapped subject field " + path);
        return text;
    }

    private static List<AuthenticatedIdentity.PropertyData> properties(JsonObject profile) {
        if (!profile.has("properties")) return List.of();
        if (!profile.get("properties").isJsonArray() || profile.getAsJsonArray("properties").size() > 16) throw new IllegalArgumentException("invalid profile properties");
        List<AuthenticatedIdentity.PropertyData> result = new ArrayList<>();
        for (JsonElement item : profile.getAsJsonArray("properties")) {
            if (!item.isJsonObject()) throw new IllegalArgumentException("invalid profile property");
            JsonObject property = item.getAsJsonObject();
            String name = requiredString(property, "name");
            String value = requiredString(property, "value");
            String signature = property.has("signature") ? requiredString(property, "signature") : null;
            if (name.length() > 64 || value.length() > 32768 || (signature != null && signature.length() > 8192)) throw new IllegalArgumentException("oversized profile property");
            result.add(new AuthenticatedIdentity.PropertyData(name, value, signature));
        }
        return List.copyOf(result);
    }

    static UUID uuid(String text) {
        if (text.matches("[0-9a-fA-F]{32}")) {
            text = text.substring(0, 8) + "-" + text.substring(8, 12) + "-" + text.substring(12, 16) + "-" + text.substring(16, 20) + "-" + text.substring(20);
        }
        if (!text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("invalid profile UUID");
        UUID uuid = UUID.fromString(text);
        if (uuid.getMostSignificantBits() == 0 && uuid.getLeastSignificantBits() == 0) throw new IllegalArgumentException("nil profile UUID");
        return uuid;
    }

    private static boolean hasControl(String value) { return value.codePoints().anyMatch(Character::isISOControl); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static SourceAuthenticationException rejected(SourcesConfig.Source source, String reason, Throwable cause) {
        LOG.log(System.Logger.Level.WARNING, "[multi-auth audit] Rejected source {0}: {1}", source.id(), reason);
        return new SourceAuthenticationException("Authentication source " + source.id() + " rejected: " + reason, cause);
    }

    private static void unavailable(SourcesConfig.Source source, String reason) {
        LOG.log(System.Logger.Level.WARNING, "Authentication source {0} unavailable: {1}", source.id(), reason);
    }

    @Override public void close() {
        officialWorkers.shutdownNow();
        customWorkers.shutdownNow();
    }
}
