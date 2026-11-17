package io.github.yidasanqian.llm.client;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Response decoders for applications that own their WebClient request construction. */
public final class WebFluxResponses {
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() { };

    private WebFluxResponses() { }

    /** Decode a JSON response as raw text, retaining WebClient's default HTTP error handling. */
    public static Mono<ResponseEntity<String>> json(WebClient.RequestHeadersSpec<?> request) {
        return request.retrieve().toEntity(String.class);
    }

    /**
     * Return response headers and a live SSE body, retaining WebClient's default HTTP error handling.
     * The caller must subscribe to the body once and consume it or cancel its subscription to release
     * the connection. Receiving the entity does not consume the body. Content-type policy belongs
     * to the caller; this method uses WebClient's configured message readers.
     */
    public static Mono<ResponseEntity<Flux<ServerSentEvent<String>>>> sse(WebClient.RequestHeadersSpec<?> request) {
        return request.retrieve().toEntityFlux(SSE_TYPE);
    }
}
