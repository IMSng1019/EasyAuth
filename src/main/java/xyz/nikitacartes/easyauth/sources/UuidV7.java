package xyz.nikitacartes.easyauth.sources;

import java.security.SecureRandom;
import java.util.UUID;

/** RFC 9562 UUIDv7: 48-bit Unix milliseconds and 74 cryptographically random bits. */
public final class UuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static UUID generate() {
        long timestamp = System.currentTimeMillis();
        long most = ((timestamp & 0x0000ffffffffffffL) << 16) | 0x7000L | RANDOM.nextInt(0x1000);
        long least = (RANDOM.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(most, least);
    }
}
