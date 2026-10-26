package xyz.nikitacartes.easyauth.sources;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Authenticated identity is evidence for one connection only, never for a client-supplied UUID. */
class ConnectionProofsTest {
    private final ConnectionProofs<Object> proofs = new ConnectionProofs<>();
    private final Object connection = new Object();
    private final UUID id = UUID.randomUUID();
    private final ConnectionProofs.Proof proof = new ConnectionProofs.Proof(id, "Alice", true, true,
            "skin-college", SourceType.SKIN_SITE);

    @Test
    void sameUuidAndNameOnAnotherConnectionAreNotAuthentication() {
        proofs.put(connection, proof);
        assertNull(proofs.get(new Object(), id, "Alice"));
        assertSame(proof, proofs.get(connection, id, "Alice"));
    }

    @Test
    void proofMustMatchTheExactFinalProfile() {
        proofs.put(connection, proof);
        assertNull(proofs.get(connection, UUID.randomUUID(), "Alice"));
        assertNull(proofs.get(connection, id, "ALICE"));
        assertSame(proof, proofs.get(connection, id, "Alice"));
    }

    @Test
    void proofIsTransferredToAPlayerOnlyOnce() {
        proofs.put(connection, proof);
        assertSame(proof, proofs.consume(connection, id, "Alice"));
        assertNull(proofs.consume(connection, id, "Alice"));
        assertEquals("skin-college", proof.sourceId());
        assertEquals(SourceType.SKIN_SITE, proof.sourceType());
    }

    @Test
    void mismatchedTransferCannotLaterReplayTheOriginalProof() {
        proofs.put(connection, proof);
        assertNull(proofs.consume(connection, UUID.randomUUID(), "Alice"));
        assertNull(proofs.consume(connection, id, "Alice"));
    }

    @Test
    void disconnectionRevokesProof() {
        proofs.put(connection, proof);
        proofs.remove(connection);
        assertNull(proofs.get(connection, id, "Alice"));
    }

    @Test
    void skinSitesRetainTheirOwnVerifiedSourceIds() {
        Object secondConnection = new Object();
        ConnectionProofs.Proof second = new ConnectionProofs.Proof(id, "Alice", true, false,
                "skin-personal", SourceType.SKIN_SITE);
        proofs.put(connection, proof);
        proofs.put(secondConnection, second);
        assertEquals("skin-college", proofs.consume(connection, id, "Alice").sourceId());
        assertEquals("skin-personal", proofs.consume(secondConnection, id, "Alice").sourceId());
    }

    @Test
    void officialProofRetainsConfiguredIdWithoutCustomTrust() {
        ConnectionProofs.Proof official = new ConnectionProofs.Proof(id, "Alice", false, false,
                "mojang-primary", SourceType.OFFICIAL);
        proofs.put(connection, official);
        ConnectionProofs.Proof consumed = proofs.consume(connection, id, "Alice");
        assertEquals("mojang-primary", consumed.sourceId());
        assertEquals(SourceType.OFFICIAL, consumed.sourceType());
        assertFalse(consumed.custom());
        assertThrows(IllegalArgumentException.class, () -> new ConnectionProofs.Proof(id, "Alice", true,
                false, "mojang-primary", SourceType.OFFICIAL));
    }
}
