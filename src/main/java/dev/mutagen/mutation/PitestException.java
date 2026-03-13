package dev.mutagen.mutation;

/** Thrown when the Pitest subprocess exits with a non-zero status or produces no report. */
public class PitestException extends RuntimeException {

    private final int exitCode;

    public PitestException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public PitestException(String message, Throwable cause) {
        super(message, cause);
        this.exitCode = -1;
    }

    public int getExitCode() { return exitCode; }
}
