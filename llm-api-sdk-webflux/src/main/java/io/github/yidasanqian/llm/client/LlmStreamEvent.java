package io.github.yidasanqian.llm.client;

import io.github.yidasanqian.llm.protocol.SseEvent;
import java.util.List;
import java.util.Map;

/** Headers are transport metadata, not a client-protocol output signal for failover decisions. */
public sealed interface LlmStreamEvent {
    record Headers(int statusCode, Map<String, List<String>> headers) implements LlmStreamEvent {
        public Headers { headers = ResponseHeaders.copy(headers); }
    }
    record Data(SseEvent<String> event) implements LlmStreamEvent { }
    record Failure(int statusCode, Map<String, List<String>> headers, String body) implements LlmStreamEvent {
        public Failure { headers = ResponseHeaders.copy(headers); }
        @Override public String toString() { return "Failure[statusCode=" + statusCode + "]"; }
    }
}
