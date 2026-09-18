package dev.jpi.ai;

import java.io.IOException;
import java.net.http.HttpTimeoutException;

/**
 * Maps raw failure evidence — an HTTP status with body, or a transport exception —
 * onto {@link ErrorKind}. Lives at the provider boundary, where that evidence is
 * still in hand; downstream only ever sees the classified kind.
 */
public final class ErrorClassifier {

    /**
     * Classifies a non-2xx HTTP response; returns null for 2xx (nothing failed).
     * Context-overflow detection inspects the body because providers report it as a
     * plain 400 with provider-specific wording.
     */
    public static ErrorKind classify(Integer statusCode, String body) {
        if (statusCode == null || statusCode < 400) {
            return null;
        }
        if (statusCode == 401 || statusCode == 403) {
            return ErrorKind.AUTH;
        }
        if (statusCode == 429) {
            return ErrorKind.RATE_LIMIT;
        }
        if (statusCode >= 500) {
            return ErrorKind.SERVER;
        }
        if (statusCode == 400 && isContextOverflow(body)) {
            return ErrorKind.CONTEXT_OVERFLOW;
        }
        return ErrorKind.UNKNOWN;
    }

    /** Classifies a transport-level failure (timeouts and IO errors are NETWORK). */
    public static ErrorKind classify(Throwable error) {
        if (error == null) {
            return null;
        }
        if (error instanceof HttpTimeoutException || error instanceof IOException) {
            return ErrorKind.NETWORK;
        }
        return ErrorKind.UNKNOWN;
    }

    /** Whether an automated caller should schedule another attempt for this kind. */
    public static boolean isRetryable(ErrorKind kind) {
        return kind == ErrorKind.RATE_LIMIT || kind == ErrorKind.SERVER || kind == ErrorKind.NETWORK;
    }

    private static boolean isContextOverflow(String body) {
        if (body == null) {
            return false;
        }
        String lowered = body.toLowerCase();
        return lowered.contains("prompt is too long")
                || lowered.contains("context length")
                || lowered.contains("context_length_exceeded");
    }

    private ErrorClassifier() {
    }
}
