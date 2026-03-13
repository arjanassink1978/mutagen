package dev.mutagen.git;

/** Thrown when a Git platform API call fails (GitLab or GitHub). */
public class GitException extends RuntimeException {

    public GitException(String message) {
        super(message);
    }

    public GitException(String message, Throwable cause) {
        super(message, cause);
    }
}
