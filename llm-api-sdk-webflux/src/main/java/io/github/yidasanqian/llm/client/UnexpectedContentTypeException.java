package io.github.yidasanqian.llm.client;

/** A successful streaming request returned a body that is not an SSE stream. */
public final class UnexpectedContentTypeException extends RuntimeException {
    public UnexpectedContentTypeException(String contentType) {
        super("Expected text/event-stream for a streaming response, got " + contentType);
    }
}
