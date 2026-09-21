package dev.jpi.ai.providers;

/**
 * Thrown by a provider read loop when the stream's cancellation token fires
 * mid-read; the adapter maps it to an ABORTED terminal — data, never an escaped
 * error (pi: {@code iterateSseMessages} throws "Request was aborted").
 */
final class RequestAbortedException extends RuntimeException {
}
