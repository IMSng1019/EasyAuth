package xyz.nikitacartes.easyauth.skins;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import xyz.nikitacartes.easyauth.sources.AuthenticatedIdentity;
import xyz.nikitacartes.easyauth.sources.SourceType;
import xyz.nikitacartes.easyauth.sources.SourcesConfig;
import xyz.nikitacartes.easyauth.utils.EasyLogger;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

//? if >= 1.20.3 && < 1.20.5 {
/*import com.mojang.authlib.SignatureState;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.minecraft.MinecraftSessionService;
*///?}

/** Applies cosmetic properties before login success; never owns a live player entity. */
public final class ServerSkinBridge implements AutoCloseable {
    private final SkinBridgeConfig config;
    private final SkinBridgeEngine engine;
    private final Predicate<TextureProperty> validator;
    private final ThreadPoolExecutor preparations;
    private final Semaphore waitingLogins;
    private final ConcurrentHashMap<CompletableFuture<GameProfile>, GameProfile> logins = new ConcurrentHashMap<>();
    private final AtomicLong nextWarning = new AtomicLong();
    private volatile boolean closed;
    private boolean apiKeyConfigured = true;

    ServerSkinBridge(SkinBridgeConfig config, SkinBridgeEngine engine, Predicate<TextureProperty> validator) {
        this.config = config;
        this.engine = engine;
        this.validator = validator;
        this.waitingLogins = new Semaphore(config.maxQueuedSkins() + 2);
        this.preparations = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.maxQueuedSkins()), task -> {
                    Thread thread = new Thread(task, "EasyAuth-skin-profile");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public static ServerSkinBridge open(MinecraftServer server, Path directory) throws IOException {
        //? if >= 1.20.3 && < 1.20.5 {
        /*SkinBridgeConfig config = SkinBridgeConfig.load(directory.resolve("skin-bridge.json"));
        String key = config.enabled() ? SkinBridgeConfig.readApiKey(directory) : "";
        Predicate<TextureProperty> validator = texture -> usableMojangTexture(server.getSessionService(), texture);
        SkinGenerator generator = key.isEmpty() ? input -> {
            throw new IOException("MineSkin API key is not configured");
        } : new MineSkinClient(key, config);
        SkinBridgeEngine engine = new SkinBridgeEngine(config, directory.resolve("skin-cache.json"), generator, validator);
        ServerSkinBridge bridge = new ServerSkinBridge(config, engine, validator);
        bridge.apiKeyConfigured = !key.isEmpty();
        return bridge;
        *///?} else {
        return null;
        //?}
    }

    public String status() {
        return "enabled=" + config.enabled() + ", MineSkin key=" + (apiKeyConfigured ? "configured" : "missing (cache only)");
    }

    public CompletableFuture<GameProfile> prepare(AuthenticatedIdentity identity, SourcesConfig.Source source,
                                                  GameProfile profile, long authenticationStartedNanos) {
        //? if >= 1.20.3 && < 1.20.5 {
        /*if (closed || !config.enabled() || source == null) return CompletableFuture.completedFuture(profile);
        // The regular official login is completely independent of conversion capacity and keys.
        if (identity.type() == SourceType.OFFICIAL && !profile.getProperties().get("textures").isEmpty()) {
            return CompletableFuture.completedFuture(profile);
        }
        if (!waitingLogins.tryAcquire()) return CompletableFuture.completedFuture(profile);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - authenticationStartedNanos);
        long waitMillis = Math.min(config.loginWaitMillis(), Math.max(0, 25_000 - elapsed));
        String identityKey = identity.sourceId() + "\n" + identity.type() + "\n" + identity.subjectId()
                + "\n" + source.hasJoinedUrl() + "\n" + source.profileUrl();
        AtomicReference<TextureProperty> fallback = new AtomicReference<>();
        CompletableFuture<GameProfile> result = new CompletableFuture<>();
        logins.put(result, profile);
        result.whenComplete((ignored, failure) -> {
            logins.remove(result);
            waitingLogins.release();
        });
        try {
            CompletableFuture<TextureProperty> work = CompletableFuture.supplyAsync(() -> {
                if (closed) return CompletableFuture.<TextureProperty>completedFuture(null);
                if (identity.type() != SourceType.OFFICIAL) fallback.set(engine.lastKnown(identityKey).orElse(null));
                try {
                    var input = SourceProfileLookup.lookup(identity, source, config.requestTimeoutMillis());
                    if (input.isEmpty()) return CompletableFuture.completedFuture(fallback.get());
                    TextureProperty original = input.get();
                    if (validator.test(original)) return CompletableFuture.completedFuture(original);
                    if (identity.type() == SourceType.OFFICIAL) return CompletableFuture.<TextureProperty>completedFuture(null);
                    var descriptor = SkinDescriptor.from(original);
                    if (descriptor.isEmpty()) return CompletableFuture.<TextureProperty>completedFuture(null);
                    SkinBridgeEngine.Selection selected = engine.select(identityKey, descriptor.get());
                    selected.cached().ifPresent(fallback::set);
                    // Known skins are immediately usable; refresh can finish for a later login.
                    if (selected.cached().isPresent()) return CompletableFuture.completedFuture(selected.cached().get());
                    return selected.refresh();
                } catch (Exception cosmeticFailure) {
                    warnOnce();
                    return CompletableFuture.completedFuture(fallback.get());
                }
            }, preparations).thenCompose(future -> future);
            // This dependent stage owns the login deadline. It must never timeout/cancel the
            // engine's shared image-generation job or mutate the profile after publication.
            work.thenApply(texture -> texture).completeOnTimeout(null, waitMillis, TimeUnit.MILLISECONDS)
                    .whenComplete((texture, failure) -> {
                        if (failure != null) warnOnce();
                        TextureProperty chosen = texture != null ? texture : fallback.get();
                        try {
                            result.complete(chosen == null ? profile : copyWithTexture(profile, chosen));
                        } catch (RuntimeException cosmeticFailure) {
                            result.complete(profile);
                            warnOnce();
                        }
                    });
        } catch (RejectedExecutionException capacity) {
            result.complete(profile);
            warnOnce();
        }
        if (closed) result.complete(profile);
        return result;
        *///?} else {
        return CompletableFuture.completedFuture(profile);
        //?}
    }

    //? if >= 1.20.3 && < 1.20.5 {
    /*static boolean usableMojangTexture(MinecraftSessionService session,
                                       TextureProperty texture) {
        try {
            if (texture.signature() == null || texture.signature().isBlank()) return false;
            var skin = SkinDescriptor.from(texture);
            if (skin.isEmpty() || !skin.get().url().getHost().equalsIgnoreCase("textures.minecraft.net")) return false;
            var unpacked = session.unpackTextures(new Property("textures", texture.value(), texture.signature()));
            return unpacked.skin() != null && unpacked.signatureState() == SignatureState.SIGNED;
        } catch (RuntimeException malformedOrUnavailable) {
            return false;
        }
    }

    private static GameProfile copyWithTexture(GameProfile original, TextureProperty texture) {
        GameProfile result = new GameProfile(original.getId(), original.getName());
        result.getProperties().putAll(original.getProperties());
        result.getProperties().removeAll("textures");
        result.getProperties().put("textures", new Property("textures", texture.value(), texture.signature()));
        return result;
    }
    *///?}

    private void warnOnce() {
        long now = System.nanoTime();
        long previous = nextWarning.get();
        if (now >= previous && nextWarning.compareAndSet(previous, now + TimeUnit.SECONDS.toNanos(30))) {
            EasyLogger.LogWarn("[skin-bridge] Skin unavailable; keeping existing/cached textures. Check MineSkin key and connectivity. Account authentication is unchanged.");
        }
    }

    @Override public void close() {
        closed = true;
        preparations.shutdownNow();
        engine.close();
        logins.forEach(CompletableFuture::complete);
        logins.clear();
    }
}
