package xyz.nikitacartes.easyauth.sources;

/** A positive provider response could not be trusted; the connection must not fall back to a password identity. */
public final class SourceAuthenticationException extends Exception {
    public SourceAuthenticationException(String message) {
        super(message);
    }

    public SourceAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
