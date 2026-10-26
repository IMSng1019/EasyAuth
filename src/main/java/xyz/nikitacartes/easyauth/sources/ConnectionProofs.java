package xyz.nikitacartes.easyauth.sources;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;

/** Ephemeral evidence produced by a completed handshake, never a name/UUID login cache. */
public final class ConnectionProofs<C> {
    private final Map<C, Proof> connections = new WeakHashMap<>();

    public record Proof(UUID uuid, String name, boolean custom, boolean relaxChatKeys) {
        public Proof {
            Objects.requireNonNull(uuid);
            Objects.requireNonNull(name);
        }

        boolean matches(UUID id, String profileName) {
            return uuid.equals(id) && name.equals(profileName);
        }
    }

    public synchronized void put(C connection, Proof proof) {
        connections.put(Objects.requireNonNull(connection), Objects.requireNonNull(proof));
    }

    public synchronized Proof get(C connection, UUID id, String name) {
        Proof proof = connections.get(connection);
        return proof != null && proof.matches(id, name) ? proof : null;
    }

    public synchronized Proof consume(C connection, UUID id, String name) {
        Proof proof = connections.remove(connection);
        return proof != null && proof.matches(id, name) ? proof : null;
    }

    public synchronized void remove(C connection) {
        connections.remove(connection);
    }
}
