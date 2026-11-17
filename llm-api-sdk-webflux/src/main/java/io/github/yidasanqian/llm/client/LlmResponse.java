package io.github.yidasanqian.llm.client;

import io.github.yidasanqian.llm.usage.Usage;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Original HTTP body and upstream usage. Non-2xx responses are returned without losing their body. */
public record LlmResponse(int statusCode, Map<String, List<String>> headers, String body, Optional<Usage> usage) {
    public LlmResponse { headers = ResponseHeaders.copy(headers); }
    public boolean successful() { return statusCode >= 200 && statusCode < 300; }
    @Override public String toString() { return "LlmResponse[statusCode=" + statusCode + "]"; }
}
