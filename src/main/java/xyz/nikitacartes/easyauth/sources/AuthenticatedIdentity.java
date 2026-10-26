package xyz.nikitacartes.easyauth.sources;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A session proof returned by a configured provider, before local identity allocation. */
public record AuthenticatedIdentity(String sourceId, SourceType type, String subjectId,
                                    UUID providerUuid, String providerName, List<PropertyData> properties) {
    public AuthenticatedIdentity {
        Objects.requireNonNull(sourceId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(subjectId);
        Objects.requireNonNull(providerUuid);
        Objects.requireNonNull(providerName);
        properties = List.copyOf(properties);
    }

    /** Signatures and values are copied verbatim, including the original texture profile ID. */
    public record PropertyData(String name, String value, String signature) {
        public PropertyData {
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);
        }
    }
}
