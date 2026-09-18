package dev.jpi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpTimeoutException;

import dev.jpi.Fixtures;

import org.junit.jupiter.api.Test;

/** The failure vocabulary: HTTP status/body and transport failures map onto one small enum. */
class ErrorClassifierTest {

    private static final Model MODEL = Fixtures.model();

    @Test
    void mapsHttpStatusAndBodyToKind() {
        assertEquals(ErrorKind.AUTH, ErrorClassifier.classify(401, ""));
        assertEquals(ErrorKind.AUTH, ErrorClassifier.classify(403, "forbidden"));
        assertEquals(ErrorKind.RATE_LIMIT, ErrorClassifier.classify(429, "rate limit exceeded"));
        assertEquals(ErrorKind.SERVER, ErrorClassifier.classify(500, "internal error"));
        assertEquals(ErrorKind.SERVER, ErrorClassifier.classify(529, "overloaded"));
        assertEquals(ErrorKind.CONTEXT_OVERFLOW,
                ErrorClassifier.classify(400, "prompt is too long: 300000 tokens > 200000 maximum"));
        assertEquals(ErrorKind.CONTEXT_OVERFLOW,
                ErrorClassifier.classify(400, "This model's maximum context length is 8192 tokens"));
        assertEquals(ErrorKind.UNKNOWN, ErrorClassifier.classify(400, "invalid: messages: empty array"));
        assertEquals(ErrorKind.UNKNOWN, ErrorClassifier.classify(418, "teapot"));
        assertNull(ErrorClassifier.classify(200, "should not be classified"));
    }

    @Test
    void mapsTransportFailuresToNetwork() {
        assertEquals(ErrorKind.NETWORK, ErrorClassifier.classify(new HttpTimeoutException("request timed out")));
        assertEquals(ErrorKind.NETWORK, ErrorClassifier.classify(new IOException("connection reset")));
        assertEquals(ErrorKind.UNKNOWN, ErrorClassifier.classify(new IllegalStateException("bug")));
        assertNull(ErrorClassifier.classify((Throwable) null));
    }

    @Test
    void onlyTransientKindsAreRetryable() {
        assertTrue(ErrorClassifier.isRetryable(ErrorKind.RATE_LIMIT));
        assertTrue(ErrorClassifier.isRetryable(ErrorKind.SERVER));
        assertTrue(ErrorClassifier.isRetryable(ErrorKind.NETWORK));
        assertFalse(ErrorClassifier.isRetryable(ErrorKind.AUTH));
        assertFalse(ErrorClassifier.isRetryable(ErrorKind.CONTEXT_OVERFLOW));
        assertFalse(ErrorClassifier.isRetryable(ErrorKind.UNKNOWN));
    }

    @Test
    void errorMessageCarriesClassification() {
        AssistantMessage ok = AssistantMessage.pending(MODEL);
        assertNull(ok.diagnostics());

        AssistantMessage failed = ok
                .withStopReason(StopReason.ERROR)
                .withErrorMessage("HTTP 429: rate limit exceeded")
                .withDiagnostics(ErrorKind.RATE_LIMIT);
        assertEquals(ErrorKind.RATE_LIMIT, failed.diagnostics());
        assertEquals("HTTP 429: rate limit exceeded", failed.errorMessage());
        assertEquals(StopReason.ERROR, failed.stopReason());
    }
}
