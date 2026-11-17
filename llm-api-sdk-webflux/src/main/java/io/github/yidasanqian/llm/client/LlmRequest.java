package io.github.yidasanqian.llm.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Native JSON payload; nested values must not be mutated while a call is in flight. */
public record LlmRequest(LlmEndpoint endpoint, Map<String, Object> body, CallOptions options) {
    public LlmRequest {
        Objects.requireNonNull(endpoint, "endpoint");
        body = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(body, "body")));
        options = Objects.requireNonNull(options, "options");
    }
    public LlmRequest(LlmEndpoint endpoint, Map<String, Object> body) { this(endpoint, body, CallOptions.defaults()); }
    @Override public String toString() { return "LlmRequest[endpoint=" + endpoint + "]"; }
}
