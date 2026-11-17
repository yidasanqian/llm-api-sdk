package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.protocol.SseEvent;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.function.Predicate;

/**
 * Extension contract for one client-to-upstream protocol pair.
 * Implementations must be reusable and must create fresh stream state per subscription.
 * They must not subscribe to the supplied source themselves or alter the original payload.
 */
public interface ProtocolAdapter {
    Map<String, Object> mapRequest(Map<String, Object> request, boolean stream);

    String mapResponse(String upstreamBody);

    /** Maps normal and error SSE events, with backpressure and cancellation propagated. */
    Flux<SseEvent<String>> mapStream(Flux<SseEvent<String>> source, String requestId);

    /** Returns a fresh per-attempt predicate using the same output rules as stream conversion. */
    Predicate<SseEvent<String>> streamReadiness(String requestId);

    String mapError(int statusCode, String body, String requestId);
}
