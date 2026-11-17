package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.protocol.Protocol;
import io.github.yidasanqian.llm.protocol.SseEvent;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Converts client requests to an upstream protocol and maps responses back.
 * Consume authoritative usage from upstream payloads before conversion.
 * Same-protocol calls preserve native payloads, including errors and unknown fields.
 * Instances are reusable; adapters must create stream state per subscription.
 */
public final class ProtocolBridge {
    private static final ProtocolAdapterRegistry DEFAULT_ADAPTERS = ProtocolAdapterRegistry.builtins();
    private final Protocol clientProtocol;
    private final Protocol upstreamProtocol;
    private final ProtocolAdapter adapter;

    public ProtocolBridge(Protocol clientProtocol, Protocol upstreamProtocol) {
        this(clientProtocol, upstreamProtocol, DEFAULT_ADAPTERS);
    }

    public ProtocolBridge(Protocol clientProtocol, Protocol upstreamProtocol, ProtocolAdapterRegistry registry) {
        this.clientProtocol = Objects.requireNonNull(clientProtocol, "clientProtocol");
        this.upstreamProtocol = Objects.requireNonNull(upstreamProtocol, "upstreamProtocol");
        Objects.requireNonNull(registry, "registry");
        adapter = clientProtocol.equals(upstreamProtocol) ? null : registry.require(clientProtocol, upstreamProtocol);
    }

    /** Maps the payload; the transport must then assign the actual upstream model. */
    public Map<String, Object> mapRequest(Map<String, Object> request, boolean stream) {
        Objects.requireNonNull(request, "request");
        return adapter == null ? new LinkedHashMap<>(request) : adapter.mapRequest(request, stream);
    }

    public String mapResponse(String responseBody) {
        return adapter == null ? responseBody : adapter.mapResponse(responseBody);
    }

    public Flux<SseEvent<String>> mapStream(Flux<SseEvent<String>> upstream, String requestId) {
        Objects.requireNonNull(upstream, "upstream");
        return adapter == null ? upstream : adapter.mapStream(upstream, requestId);
    }

    /** Obtain a fresh predicate for each route attempt; predicates may maintain conversion state. */
    public Predicate<SseEvent<String>> streamReadiness(String requestId) {
        return adapter == null ? event -> true : adapter.streamReadiness(requestId);
    }

    public String mapError(int statusCode, String body, String requestId) {
        return adapter == null ? body : adapter.mapError(statusCode, body, requestId);
    }

    public Protocol clientProtocol() { return clientProtocol; }
    public Protocol upstreamProtocol() { return upstreamProtocol; }
}
