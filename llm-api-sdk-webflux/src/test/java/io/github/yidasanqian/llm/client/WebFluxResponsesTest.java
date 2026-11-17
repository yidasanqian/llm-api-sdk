package io.github.yidasanqian.llm.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WebFluxResponsesTest {
    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void preservesJsonStatusHeadersAndRawBody() {
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) ->
                response.status(201).header("Content-Type", "application/json").header("x-request-id", "json-1")
                        .sendString(Mono.just("{\"output\":\"hello\"}"))).bindNow();
        try {
            var entity = WebFluxResponses.json(WebClient.create().get().uri(uri(server))).block(WAIT);
            assertThat(entity.getStatusCode().value()).isEqualTo(201);
            assertThat(entity.getHeaders().getFirst("x-request-id")).isEqualTo("json-1");
            assertThat(entity.getBody()).isEqualTo("{\"output\":\"hello\"}");
        } finally {
            server.disposeNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesWebClientHttpErrorsForBothDecoders(boolean streaming) {
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) ->
                response.status(429).header("Content-Type", "application/json").header("retry-after", "2")
                        .sendString(Mono.just("{\"error\":\"rate limited\"}"))).bindNow();
        try {
            var request = WebClient.create().get().uri(uri(server));
            Mono<?> result = streaming ? WebFluxResponses.sse(request) : WebFluxResponses.json(request);
            StepVerifier.create(result).expectErrorSatisfies(failure -> {
                assertThat(failure).isInstanceOf(WebClientResponseException.class);
                var error = (WebClientResponseException) failure;
                assertThat(error.getStatusCode().value()).isEqualTo(429);
                assertThat(error.getHeaders().getFirst("retry-after")).isEqualTo("2");
                assertThat(error.getResponseBodyAsString()).isEqualTo("{\"error\":\"rate limited\"}");
            }).verify(WAIT);
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void returningHeadersKeepsTheBodyAvailableForLaterConsumption() {
        AtomicInteger calls = new AtomicInteger();
        Sinks.One<String> tail = Sinks.one();
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            calls.incrementAndGet();
            return response.header("Content-Type", "text/event-stream").header("x-request-id", "sse-1")
                    .sendString(Flux.concat(Mono.just("id: 7\nevent: message\ndata: first\n\n"), tail.asMono()));
        }).bindNow();
        try {
            var entity = WebFluxResponses.sse(WebClient.create().get().uri(uri(server))).block(WAIT);
            assertThat(entity.getHeaders().getFirst("x-request-id")).isEqualTo("sse-1");
            StepVerifier.create(entity.getBody())
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("7");
                        assertThat(event.event()).isEqualTo("message");
                        assertThat(event.data()).isEqualTo("first");
                    })
                    .then(() -> tail.tryEmitValue("data: final\n\n"))
                    .assertNext(event -> assertThat(event.data()).isEqualTo("final"))
                    .expectComplete().verify(WAIT);
            assertThat(calls).hasValue(1);
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void cancellingTheBodyReleasesTheConnectionForTheNextRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch cancelled = new CountDownLatch(1);
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            if (calls.incrementAndGet() == 1) {
                return response.header("Content-Type", "text/event-stream").sendString(Flux.concat(
                        Mono.just("data: first\n\n"), Flux.<String>never()).doOnCancel(cancelled::countDown));
            }
            return response.header("Content-Type", "application/json").sendString(Mono.just("{}"));
        }).bindNow();
        ConnectionProvider pool = ConnectionProvider.builder("response-decoder-test").maxConnections(1)
                .pendingAcquireTimeout(WAIT).build();
        try {
            WebClient client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(HttpClient.create(pool))).build();
            var entity = WebFluxResponses.sse(client.get().uri(uri(server))).block(WAIT);
            StepVerifier.create(entity.getBody()).expectNextCount(1).thenCancel().verify(WAIT);
            assertThat(cancelled.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            assertThat(WebFluxResponses.json(client.get().uri(uri(server))).block(WAIT).getBody()).isEqualTo("{}");
            assertThat(calls).hasValue(2);
        } finally {
            pool.disposeLater().block(WAIT);
            server.disposeNow();
        }
    }

    private static String uri(DisposableServer server) {
        return "http://127.0.0.1:" + server.port() + "/response";
    }
}
