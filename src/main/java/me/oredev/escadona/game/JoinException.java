package me.oredev.escadona.game;

public final class JoinException extends IllegalStateException {
    private final JoinFailureReason reason;

    public JoinException(JoinFailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public JoinException(JoinFailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public JoinFailureReason reason() { return reason; }
}
