package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.error.LlmApiError;

/** A protocol error observed before the first downstream event; a host may fail over. */
public final class BridgeStreamException extends RuntimeException {
    private final LlmApiError error;

    public BridgeStreamException(LlmApiError error) {
        super(error.message());
        this.error = error;
    }

    public LlmApiError error() { return error; }
}
