package io.github.yidasanqian.llm.client;

/** Transport termination only: COMPLETED does not mean model-level success or authorize billing. */
public enum CallOutcome {
    COMPLETED, HTTP_ERROR, ERROR, CANCELLED, CONTINUATION_TIMEOUT
}
