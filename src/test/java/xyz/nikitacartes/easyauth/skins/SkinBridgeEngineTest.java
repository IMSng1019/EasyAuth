package xyz.nikitacartes.easyauth.skins;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class SkinBridgeEngineTest {
    @TempDir Path temp;
    private static final Predicate<TextureProperty> TRUSTED = property -> "trusted".equals(property.signature());
    private static final TextureProperty GOOD = texture("a", "classic", "trusted");

    private static SkinBridgeConfig config(boolean enabled, int queue) {
        return new SkinBridgeConfig(enabled, 8000, 5000, 60000, 24, queue, 60);
    }

    private static SkinDescriptor skin(String name) {
        return new SkinDescriptor(URI.create("https://example.com/" + name + ".png"), "classic");
    }

    private static TextureProperty texture(String hashDigit, String variant, String signature) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/"
                + hashDigit.repeat(64) + "\",\"metadata\":{\"model\":\"" + variant + "\"}}}}";
        return new TextureProperty(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)), signature);
    }

    private SkinBridgeEngine engine(SkinGenerator generator) {
        return new SkinBridgeEngine(config(true, 4), temp.resolve("cache.json"), generator, TRUSTED);
    }

    @Test void deduplicatesImagesButCallerTimeoutAndCancellationDoNotPoisonSharedWork() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (SkinBridgeEngine engine = engine(input -> {
            calls.incrementAndGet();
            started.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) throw new IOException("test timed out");
            return GOOD;
        })) {
            var first = engine.select("source:alice", skin("same"));
            assertTrue(started.await(2, TimeUnit.SECONDS), "Generation did not start on the bridge worker");
            var second = engine.select("source:bob", skin("same"));
            var third = engine.select("source:charlie", skin("same"));
            first.refresh().completeOnTimeout(null, 1, TimeUnit.MILLISECONDS).get(2, TimeUnit.SECONDS);
            assertTrue(second.refresh().cancel(true));
            finish.countDown();
            assertEquals(GOOD, third.refresh().get(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals(GOOD, engine.lastKnown("source:alice").orElseThrow());
            assertEquals(GOOD, engine.lastKnown("source:bob").orElseThrow());
            assertEquals(GOOD, engine.lastKnown("source:charlie").orElseThrow());
        } finally {
            finish.countDown();
        }
    }

    @Test void freshCacheCompletesImmediatelyAndPersistsOnlyOpaqueKeys() throws Exception {
        String identity = "mua-union:private-subject-40926";
        AtomicInteger calls = new AtomicInteger();
        Path cache = temp.resolve("cache.json");
        try (SkinBridgeEngine engine = engine(input -> { calls.incrementAndGet(); return GOOD; })) {
            assertEquals(GOOD, engine.select(identity, skin("private-source-url")).refresh().get(2, TimeUnit.SECONDS));
            var cached = engine.select("another:identity", skin("private-source-url"));
            assertEquals(GOOD, cached.cached().orElseThrow());
            assertTrue(cached.refresh().isDone());
            assertEquals(GOOD, cached.refresh().get());
            assertEquals(1, calls.get());
        }
        String stored = Files.readString(cache);
        assertFalse(stored.contains(identity));
        assertFalse(stored.contains("another:identity"));
        assertFalse(stored.contains("private-source-url"));
        try (SkinBridgeEngine reopened = engine(input -> { fail("Fresh persistent cache must not generate"); return null; })) {
            assertEquals(GOOD, reopened.lastKnown(identity).orElseThrow());
            assertEquals(GOOD, reopened.lastKnown("another:identity").orElseThrow());
            assertEquals(GOOD, reopened.select(identity, skin("private-source-url")).refresh().get());
        }
    }

    @Test void expiredCacheIsAvailableWhileRefreshRunsAndNewFailureKeepsLastGood() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch failRefresh = new CountDownLatch(1);
        try (SkinBridgeEngine engine = new SkinBridgeEngine(config(true, 4), temp.resolve("cache.json"), input -> {
            if (calls.incrementAndGet() == 1) return GOOD;
            refreshStarted.countDown();
            failRefresh.await(5, TimeUnit.SECONDS);
            throw new IOException("temporary provider failure");
        }, TRUSTED, clock)) {
            engine.select("source:alice", skin("one")).refresh().get(2, TimeUnit.SECONDS);
            clock.advance(TimeUnit.HOURS.toMillis(25));
            var stale = engine.select("source:alice", skin("one"));
            assertEquals(GOOD, stale.cached().orElseThrow());
            assertTrue(refreshStarted.await(2, TimeUnit.SECONDS));
            assertFalse(stale.refresh().isDone());
            failRefresh.countDown();
            assertThrows(ExecutionException.class, () -> stale.refresh().get(2, TimeUnit.SECONDS));
            assertEquals(GOOD, engine.lastKnown("source:alice").orElseThrow());
            var changed = engine.select("source:alice", skin("changed"));
            assertEquals(GOOD, changed.cached().orElseThrow());
            assertThrows(ExecutionException.class, () -> changed.refresh().get(2, TimeUnit.SECONDS));
            assertEquals(GOOD, engine.lastKnown("source:alice").orElseThrow());
        } finally {
            failRefresh.countDown();
        }
    }

    @Test void failureBackoffSuppressesRetriesUntilTheClockAdvances() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger calls = new AtomicInteger();
        try (SkinBridgeEngine engine = new SkinBridgeEngine(config(true, 4), temp.resolve("cache.json"), input -> {
            if (calls.incrementAndGet() == 1) throw new IOException("rate limited");
            return GOOD;
        }, TRUSTED, clock)) {
            assertThrows(ExecutionException.class, () -> engine.select("alice", skin("one")).refresh().get(2, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> engine.select("bob", skin("one")).refresh().get(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            clock.advance(61_000);
            assertEquals(GOOD, engine.select("bob", skin("one")).refresh().get(2, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        }
    }

    @Test void lateOldGenerationCannotReplaceANewerSelectedIdentitySkin() throws Exception {
        TextureProperty newer = texture("b", "classic", "trusted");
        CountDownLatch oldStarted = new CountDownLatch(1);
        CountDownLatch finishOld = new CountDownLatch(1);
        try (SkinBridgeEngine engine = engine(input -> {
            if (input.url().getPath().equals("/old.png")) {
                oldStarted.countDown();
                if (!finishOld.await(5, TimeUnit.SECONDS)) throw new IOException("test timed out");
                return GOOD;
            }
            return newer;
        })) {
            engine.select("warmup", skin("newer")).refresh().get(2, TimeUnit.SECONDS);
            var old = engine.select("alice", skin("old"));
            assertTrue(oldStarted.await(2, TimeUnit.SECONDS));
            assertEquals(newer, engine.select("alice", skin("newer")).refresh().get(2, TimeUnit.SECONDS));
            finishOld.countDown();
            assertEquals(GOOD, old.refresh().get(2, TimeUnit.SECONDS));
            assertEquals(newer, engine.lastKnown("alice").orElseThrow(),
                    "An older request must not overwrite the identity's later selected skin");
            assertEquals(GOOD, engine.select("another-player", skin("old")).cached().orElseThrow(),
                    "The completed image can still be cached for other requests");
        } finally {
            finishOld.countDown();
        }
    }

    @Test void queueIsBoundedAndOnlyOneGenerationRunsAtATime() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (SkinBridgeEngine engine = new SkinBridgeEngine(config(true, 1), temp.resolve("cache.json"), input -> {
            calls.incrementAndGet();
            started.countDown();
            finish.await(5, TimeUnit.SECONDS);
            return GOOD;
        }, TRUSTED)) {
            var active = engine.select("alice", skin("one"));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = engine.select("bob", skin("two"));
            var rejected = engine.select("charlie", skin("three"));
            assertThrows(ExecutionException.class, () -> rejected.refresh().get(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            finish.countDown();
            assertEquals(GOOD, active.refresh().get(2, TimeUnit.SECONDS));
            assertEquals(GOOD, queued.refresh().get(2, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        } finally {
            finish.countDown();
        }
    }

    @Test void closeCompletesActiveAndQueuedRequestsAndRejectsLaterWork() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        SkinBridgeEngine engine = engine(input -> {
            started.countDown();
            new CountDownLatch(1).await();
            return GOOD;
        });
        try {
            var active = engine.select("alice", skin("one"));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = engine.select("bob", skin("two"));
            engine.close();
            assertTrue(active.refresh().isCompletedExceptionally());
            assertTrue(queued.refresh().isCompletedExceptionally());
            assertThrows(ExecutionException.class, () -> engine.select("charlie", skin("three")).refresh().get());
        } finally {
            engine.close();
        }
    }

    @Test void disabledEngineNeverGenerates() {
        AtomicInteger calls = new AtomicInteger();
        try (SkinBridgeEngine engine = new SkinBridgeEngine(config(false, 4), temp.resolve("cache.json"), input -> {
            calls.incrementAndGet(); return GOOD;
        }, TRUSTED)) {
            var selection = engine.select("alice", skin("one"));
            assertTrue(selection.cached().isEmpty());
            assertTrue(selection.refresh().isCompletedExceptionally());
            assertEquals(0, calls.get());
        }
    }

    @Test void rejectsUnsignedUntrustedMalformedAndWrongModelResults() throws Exception {
        for (TextureProperty bad : new TextureProperty[]{texture("b", "classic", null),
                texture("b", "classic", "forged"), new TextureProperty("not-base64", "trusted"),
                texture("b", "slim", "trusted")}) {
            AtomicInteger calls = new AtomicInteger();
            try (SkinBridgeEngine engine = engine(input -> { calls.incrementAndGet(); return bad; })) {
                assertThrows(ExecutionException.class, () -> engine.select("alice", skin("one")).refresh().get(2, TimeUnit.SECONDS));
                assertEquals(1, calls.get(), "Rejection must validate the generated result, not fail before invoking the generator");
                assertTrue(engine.lastKnown("alice").isEmpty());
            }
        }
    }

    @Test void cacheIsRevalidatedOnReloadBeforeUse() throws Exception {
        Path cache = temp.resolve("cache.json");
        try (SkinBridgeEngine engine = engine(input -> GOOD)) {
            engine.select("alice", skin("one")).refresh().get(2, TimeUnit.SECONDS);
        }
        Files.writeString(cache, Files.readString(cache).replace("trusted", "forged"));
        AtomicInteger validations = new AtomicInteger();
        try (SkinBridgeEngine reopened = new SkinBridgeEngine(config(true, 4), cache, input -> GOOD, property -> {
            validations.incrementAndGet();
            return TRUSTED.test(property);
        })) {
            assertEquals(0, validations.get(), "Constructing the cache must not trigger lazy public-key network requests");
            assertTrue(reopened.lastKnown("alice").isEmpty());
            assertTrue(validations.get() > 0, "Cached signatures must be checked before returning a skin");
            var selection = reopened.select("alice", skin("one"));
            assertTrue(selection.cached().isEmpty());
            assertEquals(GOOD, selection.refresh().get(2, TimeUnit.SECONDS));
        }
    }

    @Test void oversizedOrMalformedCacheDoesNotBlockCosmeticRecovery() throws Exception {
        Path cache = temp.resolve("cache.json");
        for (String content : new String[]{"{broken", " ".repeat(8 * 1024 * 1024 + 1)}) {
            Files.writeString(cache, content);
            try (SkinBridgeEngine engine = engine(input -> GOOD)) {
                assertTrue(engine.lastKnown("alice").isEmpty());
                assertEquals(GOOD, engine.select("alice", skin("one")).refresh().get(2, TimeUnit.SECONDS));
            }
            assertTrue(Files.size(cache) <= 8L * 1024 * 1024);
        }
    }

    private static final class MutableClock extends Clock {
        private long millis = 1_800_000_000_000L;
        synchronized void advance(long amount) { millis += amount; }
        @Override public synchronized long millis() { return millis; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
