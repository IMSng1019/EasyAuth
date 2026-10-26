package xyz.nikitacartes.easyauth.sources;

/** A permanent identity conflict: reject the login rather than falling back to a weaker source. */
public final class IdentityConflictException extends RuntimeException {
    private final String code;

    public IdentityConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
