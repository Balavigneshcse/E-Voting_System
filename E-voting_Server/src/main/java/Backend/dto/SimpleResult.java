package Backend.dto;

/** Minimal success/failure reply for operations with nothing else to report. */
public record SimpleResult(boolean success, String message) {

    public static SimpleResult ok(String message)   { return new SimpleResult(true, message); }
    public static SimpleResult fail(String message) { return new SimpleResult(false, message); }
}
