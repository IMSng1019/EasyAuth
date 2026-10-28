package xyz.nikitacartes.easyauth.skins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Bounded cosmetic work and persistent signed textures; never owns a live player/profile. */
public final class SkinBridgeEngine implements AutoCloseable {
    public record Selection(Optional<TextureProperty> cached, CompletableFuture<TextureProperty> refresh) {}

    private static final int MAX_CACHE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_IDENTITIES = 10_000;
    private static final System.Logger LOGGER = System.getLogger(SkinBridgeEngine.class.getName());
    private final SkinBridgeConfig config;
    private final Path file;
    private final SkinGenerator generator;
    private final Predicate<TextureProperty> validator;
    private final Clock clock;
    private final ThreadPoolExecutor executor;
    private final LinkedHashMap<String, CachedSkin> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<String, String> identities = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<String, String> latestRequested = new LinkedHashMap<>();
    private final Map<String, Job> pending = new LinkedHashMap<>();
    private final LinkedHashSet<Job> unfinished = new LinkedHashSet<>();
    private final LinkedHashMap<String, Long> retryAfter = new LinkedHashMap<>();
    private boolean closed;

    private record CachedSkin(TextureProperty property, String variant, long generatedAt) {}

    private static final class Job {
        final CompletableFuture<TextureProperty> result = new CompletableFuture<>();
        final LinkedHashSet<String> identities = new LinkedHashSet<>();
        void remember(String identity) {
            identities.add(identity);
            if (identities.size() > MAX_IDENTITIES) identities.remove(identities.iterator().next());
        }
    }

    public SkinBridgeEngine(SkinBridgeConfig config, Path file, SkinGenerator generator,
                            Predicate<TextureProperty> validator) {
        this(config, file, generator, validator, Clock.systemUTC());
    }

    SkinBridgeEngine(SkinBridgeConfig config, Path file, SkinGenerator generator,
                     Predicate<TextureProperty> validator, Clock clock) {
        this.config = Objects.requireNonNull(config);
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        this.generator = Objects.requireNonNull(generator);
        this.validator = Objects.requireNonNull(validator);
        this.clock = Objects.requireNonNull(clock);
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.maxQueuedSkins()), task -> {
                    Thread thread = new Thread(task, "EasyAuth-skin-bridge");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        load();
    }

    public synchronized Selection select(String identityKey, SkinDescriptor input) {
        String identity = hashIdentity(identityKey);
        Optional<TextureProperty> previous = lastKnownHashed(identity);
        if (closed) return failed(previous, "Skin bridge is closed");
        if (!config.enabled()) return failed(previous, "Skin bridge is disabled");
        Objects.requireNonNull(input);
        String key = input.cacheKey();
        latestRequested.remove(identity);
        latestRequested.put(identity, key);
        while (latestRequested.size() > MAX_IDENTITIES) latestRequested.remove(latestRequested.keySet().iterator().next());
        CachedSkin cached = checkedEntry(key);
        if (cached != null && !cached.variant().equals(input.variant())) cached = null;
        if (cached != null) {
            associate(identity, key);
            previous = Optional.of(cached.property());
            long age = clock.millis() - cached.generatedAt();
            if (age >= 0 && age < TimeUnit.HOURS.toMillis(config.refreshAfterHours())) {
                return new Selection(previous, CompletableFuture.completedFuture(cached.property()));
            }
        }
        Job existing = pending.get(key);
        if (existing != null) {
            existing.remember(identity);
            return selection(previous, existing);
        }
        long now = clock.millis();
        retryAfter.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (retryAfter.containsKey(key)) return failed(previous, "Skin generation is backing off after a failure");
        Job job = new Job();
        job.remember(identity);
        pending.put(key, job);
        unfinished.add(job);
        try {
            executor.execute(() -> generate(key, input, job));
        } catch (RejectedExecutionException full) {
            pending.remove(key);
            unfinished.remove(job);
            job.result.completeExceptionally(new RejectedExecutionException("Skin generation queue is full"));
        }
        return selection(previous, job);
    }

    public synchronized Optional<TextureProperty> lastKnown(String identityKey) {
        return lastKnownHashed(hashIdentity(identityKey));
    }

    private Optional<TextureProperty> lastKnownHashed(String identity) {
        String key = identities.get(identity);
        if (key == null) return Optional.empty();
        CachedSkin cached = checkedEntry(key);
        if (cached == null) {
            identities.remove(identity);
            return Optional.empty();
        }
        return Optional.of(cached.property());
    }

    private CachedSkin checkedEntry(String key) {
        CachedSkin cached = entries.get(key);
        if (cached != null && acceptedVariant(cached.property()).filter(cached.variant()::equals).isEmpty()) {
            removeEntry(key);
            return null;
        }
        return cached;
    }

    private void associate(String identity, String key) {
        String previous = identities.put(identity, key);
        trimCounts();
        if (!key.equals(previous)) persist();
    }

    private void generate(String key, SkinDescriptor input, Job job) {
        try {
            TextureProperty property = generator.generate(input);
            if (acceptedVariant(property).filter(input.variant()::equals).isEmpty()) {
                throw new IllegalArgumentException("Generated skin failed signature, texture or model validation");
            }
            synchronized (this) {
                if (closed || pending.get(key) != job) return;
                entries.put(key, new CachedSkin(property, input.variant(), clock.millis()));
                // An old slow request may finish after the same identity selected a newer
                // image (including a cache hit). Cache its image without reverting that choice.
                for (String identity : job.identities) {
                    if (key.equals(latestRequested.get(identity))) identities.put(identity, key);
                }
                retryAfter.remove(key);
                trimCounts();
                persist();
                pending.remove(key, job);
            }
            // unfinished retains the request until completion, so close() can terminate it
            // even after persistence. Removing pending first makes retries deterministic.
            job.result.complete(property);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            synchronized (this) {
                if (!closed && pending.get(key) == job) {
                    retryAfter.put(key, clock.millis() + TimeUnit.SECONDS.toMillis(config.failureRetrySeconds()));
                    while (retryAfter.size() > MAX_ENTRIES) retryAfter.remove(retryAfter.keySet().iterator().next());
                    pending.remove(key, job);
                }
            }
            job.result.completeExceptionally(failure);
        } finally {
            synchronized (this) {
                pending.remove(key, job);
                unfinished.remove(job);
            }
        }
    }

    private Optional<String> acceptedVariant(TextureProperty property) {
        Optional<String> variant = structuralVariant(property);
        if (variant.isEmpty()) return Optional.empty();
        try {
            return validator.test(property) ? variant : Optional.empty();
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static Optional<String> structuralVariant(TextureProperty property) {
        if (property == null || property.value() == null || property.value().isBlank() || property.value().length() > 32767
                || property.signature() == null || property.signature().isBlank() || property.signature().length() > 4096) {
            return Optional.empty();
        }
        try {
            return SkinDescriptor.from(property).map(SkinDescriptor::variant);
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static Selection selection(Optional<TextureProperty> cached, Job job) {
        // Never expose the shared future: completeOnTimeout, cancel, or complete on one
        // login's dependent future must not poison other logins or background caching.
        return new Selection(cached, job.result.thenApply(property -> property));
    }

    private static Selection failed(Optional<TextureProperty> cached, String message) {
        return new Selection(cached, CompletableFuture.failedFuture(new IllegalStateException(message)));
    }

    private static String hashIdentity(String identity) {
        if (identity == null || identity.isEmpty() || identity.length() > 4096) {
            throw new IllegalArgumentException("Skin identity key is missing or too large");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void trimCounts() {
        while (entries.size() > MAX_ENTRIES) removeEntry(entries.keySet().iterator().next());
        while (identities.size() > MAX_IDENTITIES) identities.remove(identities.keySet().iterator().next());
    }

    private void removeEntry(String key) {
        entries.remove(key);
        identities.values().removeIf(key::equals);
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > MAX_CACHE_BYTES) throw new IOException("Cache is too large");
            // Bounded read also handles a file growing between stat and open.
            byte[] bytes;
            try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_CACHE_BYTES + 1); }
            if (bytes.length > MAX_CACHE_BYTES) throw new IOException("Cache is too large");
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) throw new IOException("Unknown skin cache version");
            JsonObject skins = root.getAsJsonObject("skins");
            JsonObject profiles = root.getAsJsonObject("identities");
            if (skins.size() > MAX_ENTRIES || profiles.size() > MAX_IDENTITIES) throw new IOException("Too many cached entries");
            for (Map.Entry<String, JsonElement> entry : skins.entrySet()) {
                if (!opaqueKey(entry.getKey()) || !entry.getValue().isJsonObject()) continue;
                try {
                    JsonObject data = entry.getValue().getAsJsonObject();
                    TextureProperty property = new TextureProperty(data.get("value").getAsString(), data.get("signature").getAsString());
                    String variant = data.get("variant").getAsString();
                    long generatedAt = data.get("generatedAt").getAsLong();
                    // Startup/reload may run on the server thread. Crypto verification can
                    // lazily fetch service keys, so checkedEntry performs it on lookup before
                    // returning any cached value; loading only validates bounded structure.
                    if (generatedAt < 0 || generatedAt > clock.millis() + TimeUnit.MINUTES.toMillis(5)
                            || structuralVariant(property).filter(variant::equals).isEmpty()) continue;
                    entries.put(entry.getKey(), new CachedSkin(property, variant, generatedAt));
                } catch (RuntimeException invalidEntry) {
                    // One invalid cache record must not discard unrelated valid last-good skins.
                }
            }
            for (Map.Entry<String, JsonElement> entry : profiles.entrySet()) {
                if (!opaqueKey(entry.getKey()) || !entry.getValue().isJsonPrimitive()
                        || !entry.getValue().getAsJsonPrimitive().isString()) continue;
                String key = entry.getValue().getAsString();
                if (opaqueKey(key) && entries.containsKey(key)) identities.put(entry.getKey(), key);
            }
        } catch (IOException | RuntimeException invalidCache) {
            entries.clear();
            identities.clear();
            LOGGER.log(System.Logger.Level.WARNING, "Ignoring an unreadable or invalid skin bridge cache; it will be regenerated");
        }
    }

    private static boolean opaqueKey(String key) {
        return key != null && key.matches("[0-9a-f]{64}");
    }

    private void persist() {
        Path temporary = null;
        try {
            byte[] bytes;
            while (true) {
                try {
                    bytes = encode();
                    break;
                } catch (CacheTooLargeException full) {
                    if (!entries.isEmpty()) removeEntry(entries.keySet().iterator().next());
                    else if (!identities.isEmpty()) identities.remove(identities.keySet().iterator().next());
                    else throw full;
                }
            }
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "skin-cache-", ".tmp");
            Files.write(temporary, bytes);
            // Do not fall back to non-atomic replacement: keeping the previous valid file is
            // preferable to corrupting last-good data if a filesystem lacks atomic rename.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not persist skin bridge cache; verified textures remain available in memory");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            }
        }
    }

    private byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        var limited = new FilterOutputStream(bytes) {
            private int count;
            @Override public void write(int value) throws IOException {
                if (++count > MAX_CACHE_BYTES) throw new CacheTooLargeException();
                out.write(value);
            }
            @Override public void write(byte[] buffer, int offset, int length) throws IOException {
                if (length > MAX_CACHE_BYTES - count) throw new CacheTooLargeException();
                count += length;
                out.write(buffer, offset, length);
            }
        };
        try (JsonWriter writer = new JsonWriter(new OutputStreamWriter(limited, StandardCharsets.UTF_8))) {
            writer.beginObject().name("version").value(1).name("skins").beginObject();
            for (Map.Entry<String, CachedSkin> entry : entries.entrySet()) {
                CachedSkin skin = entry.getValue();
                writer.name(entry.getKey()).beginObject().name("value").value(skin.property().value())
                        .name("signature").value(skin.property().signature()).name("variant").value(skin.variant())
                        .name("generatedAt").value(skin.generatedAt()).endObject();
            }
            writer.endObject().name("identities").beginObject();
            for (Map.Entry<String, String> identity : identities.entrySet()) writer.name(identity.getKey()).value(identity.getValue());
            writer.endObject().endObject();
        }
        return bytes.toByteArray();
    }

    private static final class CacheTooLargeException extends IOException {}

    @Override public void close() {
        ArrayList<Job> outstanding;
        synchronized (this) {
            if (closed) return;
            closed = true;
            outstanding = new ArrayList<>(unfinished);
            pending.clear();
            unfinished.clear();
            latestRequested.clear();
        }
        executor.shutdownNow();
        for (Job job : outstanding) job.result.completeExceptionally(new IllegalStateException("Skin bridge is closed"));
    }
}
