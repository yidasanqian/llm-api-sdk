package io.github.yidasanqian.llm.client;

import io.github.yidasanqian.llm.protocol.Protocol;
import io.github.yidasanqian.llm.usage.Usage;
import io.github.yidasanqian.llm.usage.UsageParser;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.BaseSubscriber;
import org.reactivestreams.Subscription;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.yidasanqian.llm.usage.UsageDimensions.OUTPUT_TOKENS;
import static org.assertj.core.api.Assertions.assertThat;

class LlmClientTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String USAGE = "{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15}}";

    @ParameterizedTest
    @MethodSource("protocols")
    void executesNativeJsonWithoutSpringContainer(Protocol protocol) {
        AtomicReference<String> payload = new AtomicReference<>();
        AtomicReference<String> authentication = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> {
                    calls.incrementAndGet();
                    authentication.set(request.requestHeaders().get(protocol.equals(Protocol.ANTHROPIC_MESSAGES) ? "x-api-key"
                            : protocol.equals(Protocol.GEMINI) ? "x-goog-api-key" : "Authorization"));
                    return request.receive().aggregate().asString().flatMap(body -> {
                        payload.set(body);
                        return response.header("Content-Type", "application/json")
                                .header("x-request-id", "upstream-1").sendString(Mono.just(USAGE)).then();
                    });
                }).bindNow();
        try (LlmClient client = LlmClient.builder().build()) {
            LlmEndpoint endpoint = LlmEndpoint.builder(protocol, uri(server, "/native"))
                    .apiKey("test-secret").model("upstream-model").build();
            LlmResponse result = client.execute(new LlmRequest(endpoint, Map.of("input", "hello"))).block(WAIT);
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.headers().get("x-request-id")).containsExactly("upstream-1");
            assertThat(result.body()).isEqualTo(USAGE);
            assertThat(calls).hasValue(1);
            assertThat(authentication.get()).isEqualTo(protocol.equals(Protocol.ANTHROPIC_MESSAGES) || protocol.equals(Protocol.GEMINI)
                    ? "test-secret" : "Bearer test-secret");
            assertThat(payload.get()).contains("hello");
            assertThat(endpoint.toString()).doesNotContain("test-secret");
            if (!protocol.equals(Protocol.GEMINI)) assertThat(payload.get()).contains("upstream-model");
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void decodesFragmentedSseAndWaitsForRawHandlerBeforeForwarding() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger observed = new AtomicInteger();
        AtomicReference<CallCompletion> completion = new AtomicReference<>();
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) -> {
            calls.incrementAndGet();
            return res.header("Content-Type", "text/event-stream")
                    .sendString(Flux.just("id: 7\nevent: message\nda", "ta: " + USAGE + "\n", "\ndata: [DONE]\n\n"));
        }).bindNow();
        try (LlmClient client = LlmClient.builder().build()) {
            CallOptions options = CallOptions.builder()
                    .rawEventHandler(event -> Mono.delay(Duration.ofMillis(5)).doOnNext(ignored -> observed.incrementAndGet()).then())
                    .completionListener(completion::set).build();
            List<LlmStreamEvent> events = client.stream(new LlmRequest(endpoint(server), Map.of(), options)).collectList().block(WAIT);
            assertThat(events.get(0)).isInstanceOf(LlmStreamEvent.Headers.class);
            List<LlmStreamEvent.Data> data = events.stream().filter(LlmStreamEvent.Data.class::isInstance)
                    .map(LlmStreamEvent.Data.class::cast).toList();
            assertThat(data).hasSize(2);
            assertThat(data.get(0).event().id()).isEqualTo("7");
            assertThat(data.get(0).event().event()).isEqualTo("message");
            assertThat(data.get(0).event().data()).isEqualTo(USAGE);
            assertThat(observed).hasValue(2);
            assertThat(calls).hasValue(1);
            assertThat(completion.get().usage()).isPresent();
            assertThat(completion.get().outcome()).isEqualTo(CallOutcome.COMPLETED);
        } finally { server.disposeNow(); }
    }

    @Test
    void preservesHttpErrorBodyForProtocolTranslation() {
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) ->
                res.status(429).header("Content-Type", "application/json").header("retry-after", "2")
                        .sendString(Mono.just("{\"error\":{\"message\":\"rate limited\"}}"))).bindNow();
        try (LlmClient client = LlmClient.builder().build()) {
            LlmRequest request = new LlmRequest(endpoint(server), Map.of());
            LlmResponse response = client.execute(request).block(WAIT);
            assertThat(response.statusCode()).isEqualTo(429);
            List<LlmStreamEvent> events = client.stream(request).collectList().block(WAIT);
            assertThat(events).hasSize(2);
            LlmStreamEvent.Failure error = (LlmStreamEvent.Failure) events.get(1);
            assertThat(error.statusCode()).isEqualTo(429);
            assertThat(error.body()).contains("rate limited");
            assertThat(error.headers().get("retry-after")).containsExactly("2");
        } finally { server.disposeNow(); }
    }

    @Test
    void rejectsSuccessfulNonSseResponseToStreamingRequest() {
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) ->
                res.header("Content-Type", "application/json").sendString(Mono.just("{}"))).bindNow();
        try (LlmClient client = LlmClient.builder().build()) {
            StepVerifier.create(client.stream(new LlmRequest(endpoint(server), Map.of())))
                    .expectNextCount(1).expectError(UnexpectedContentTypeException.class).verify(WAIT);
        } finally { server.disposeNow(); }
    }

    @Test
    void cancellationDrainsOneSubscriptionAndCompletesOnceWithFinalUsage() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        AtomicReference<CallCompletion> result = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) -> {
            calls.incrementAndGet();
            return res.header("Content-Type", "text/event-stream").sendString(Flux.concat(
                    Mono.just("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"),
                    Mono.delay(Duration.ofMillis(100)).map(ignored -> "data: " + USAGE + "\n\n"),
                    Mono.just("data: [DONE]\n\n")));
        }).bindNow();
        try (LlmClient client = LlmClient.builder().build()) {
            CallOptions options = CallOptions.builder().continuationTimeout(Duration.ofSeconds(2))
                    .completionListener(value -> { result.set(value); completions.incrementAndGet(); finished.countDown(); }).build();
            client.stream(new LlmRequest(endpoint(server), Map.of(), options)).ofType(LlmStreamEvent.Data.class)
                    .take(1).blockLast(WAIT);
            assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(calls).hasValue(1);
            assertThat(completions).hasValue(1);
            assertThat(result.get().downstreamCancelled()).isTrue();
            assertThat(result.get().usage()).isPresent();
        } finally { server.disposeNow(); }
    }

    @Test
    void defaultCancellationStopsUpstreamAndReportsCancellation() throws Exception {
        AtomicReference<CallCompletion> result = new AtomicReference<>();
        CountDownLatch serverCancelled = new CountDownLatch(1);
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) ->
                res.header("Content-Type", "text/event-stream").sendString(Flux.concat(
                        Mono.just("data: {}\n\n"), Flux.<String>never()).doOnCancel(serverCancelled::countDown))).bindNow();
        try (LlmClient client = LlmClient.builder().build()) {
            CallOptions options = CallOptions.builder().completionListener(result::set).build();
            client.stream(new LlmRequest(endpoint(server), Map.of(), options)).ofType(LlmStreamEvent.Data.class)
                    .take(1).blockLast(WAIT);
            assertThat(serverCancelled.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get().outcome()).isEqualTo(CallOutcome.CANCELLED);
        } finally { server.disposeNow(); }
    }

    @Test
    void cancellationBeforeUpstreamSubscriptionReleasesAttempt() throws Exception {
        AtomicInteger exchanges = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        WebClient web = WebClient.builder().exchangeFunction(request -> {
            exchanges.incrementAndGet();
            return Mono.error(new AssertionError("Cancelled request must not reach upstream"));
        }).build();
        LlmEndpoint endpoint = LlmEndpoint.builder(Protocol.OPENAI_CHAT,
                URI.create("http://127.0.0.1/cancelled")).build();
        CallOptions options = CallOptions.builder().completionListener(result -> {
            assertThat(result.outcome()).isEqualTo(CallOutcome.CANCELLED);
            completions.incrementAndGet();
        }).build();
        try (LlmClient client = LlmClient.builder().webClient(web).build()) {
            for (int i = 0; i < 100; i++) {
                client.stream(new LlmRequest(endpoint, Map.of(), options)).subscribe(new BaseSubscriber<>() {
                    @Override protected void hookOnSubscribe(Subscription subscription) { cancel(); }
                });
            }
            assertThat(completions).hasValue(100);
            var activeField = LlmClient.class.getDeclaredField("active");
            activeField.setAccessible(true);
            assertThat((Set<?>) activeField.get(client)).isEmpty();
            assertThat(exchanges).hasValue(0);
        }
    }

    @Test
    void completionListenerCanCloseClientWithoutReplacingSuccess() {
        AtomicInteger completions = new AtomicInteger();
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) ->
                res.header("Content-Type", "application/json").sendString(Mono.just(USAGE))).bindNow();
        try (LlmClient client = LlmClient.builder().build()) {
            CallOptions options = CallOptions.builder().completionListener(result -> {
                assertThat(result.outcome()).isEqualTo(CallOutcome.COMPLETED);
                assertThat(result.usage()).isPresent();
                completions.incrementAndGet();
                client.close();
            }).build();
            LlmResponse response = client.execute(new LlmRequest(endpoint(server), Map.of(), options)).block(WAIT);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(USAGE);
            assertThat(completions).hasValue(1);
        } finally { server.disposeNow(); }
    }

    @Test
    void continuationTimeoutStopsUnfinishedStream() throws Exception {
        AtomicReference<CallCompletion> result = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) ->
                res.header("Content-Type", "text/event-stream").sendString(Flux.concat(Mono.just("data: {}\n\n"), Flux.never()))).bindNow();
        try (LlmClient client = LlmClient.builder().build()) {
            CallOptions options = CallOptions.builder().continuationTimeout(Duration.ofMillis(100))
                    .completionListener(value -> { result.set(value); completed.countDown(); }).build();
            client.stream(new LlmRequest(endpoint(server), Map.of(), options)).ofType(LlmStreamEvent.Data.class)
                    .take(1).blockLast(WAIT);
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get().outcome()).isEqualTo(CallOutcome.CONTINUATION_TIMEOUT);
        } finally { server.disposeNow(); }
    }

    @Test
    void customProtocolUsesExplicitHeadersAndPayloadAndInjectedUsageDecoder() {
        Protocol custom = Protocol.of("vendor-video");
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) -> {
            token.set(req.requestHeaders().get("x-vendor-token"));
            return req.receive().aggregate().asString().flatMap(json -> {
                body.set(json);
                return res.header("Content-Type", "application/json").sendString(Mono.just("{\"meter\":4}")).then();
            });
        }).bindNow();
        UsageParser parser = new UsageParser().withDecoder(custom,
                (json, context) -> java.util.Optional.of(Usage.tokens(json.path("meter").asLong(), 0)));
        try (LlmClient client = LlmClient.builder().usageParser(parser).build()) {
            LlmEndpoint endpoint = LlmEndpoint.builder(custom, uri(server, "/vendor"))
                    .headers(Map.of("x-vendor-token", "private-test-value")).build();
            LlmResponse response = client.execute(new LlmRequest(endpoint, Map.of("job", "render"))).block(WAIT);
            assertThat(body.get()).isEqualTo("{\"job\":\"render\"}");
            assertThat(token.get()).isEqualTo("private-test-value");
            assertThat(response.usage()).contains(Usage.tokens(4, 0));
        } finally { server.disposeNow(); }
    }

    @Test
    void seedanceVideoTaskReturnsBearerAuthenticatedJsonAndConfirmedUsage() {
        AtomicReference<String> body = new AtomicReference<>();
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            assertThat(request.requestHeaders().get("Authorization")).isEqualTo("Bearer test-secret");
            return request.receive().aggregate().asString().flatMap(json -> {
                body.set(json);
                return response.header("Content-Type", "application/json").sendString(Mono.just("""
                        {"id":"cgt-1","status":"succeeded","resolution":"720p",
                         "usage":{"completion_tokens":108900,"total_tokens":108900}}
                        """)).then();
            });
        }).bindNow();
        try (LlmClient client = LlmClient.builder().build()) {
            LlmEndpoint endpoint = LlmEndpoint.builder(Protocol.SEEDANCE_VIDEO,
                            uri(server, "/api/v3/contents/generations/tasks"))
                    .model("doubao-seedance-2-5-260628").apiKey("test-secret").build();
            LlmResponse response = client.execute(new LlmRequest(endpoint,
                    Map.of("content", List.of(Map.of("type", "text", "text", "ocean"))))).block(WAIT);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(body.get()).contains("\"model\":\"doubao-seedance-2-5-260628\"");
            assertThat(response.usage()).isPresent();
            var usage = response.usage().orElseThrow();
            assertThat(usage.token(OUTPUT_TOKENS)).isEqualTo(108900L);
            assertThat(usage.measurements().get(OUTPUT_TOKENS).attributes())
                    .containsEntry("resolution", "720p");
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void usageAlreadyReceivedSurvivesReadTimeout() {
        AtomicReference<CallCompletion> completion = new AtomicReference<>();
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) ->
                res.header("Content-Type", "text/event-stream").sendString(Flux.concat(
                        Mono.just("data: " + USAGE + "\n\n"), Flux.never()))).bindNow();
        try (LlmClient client = LlmClient.builder().idleTimeout(Duration.ofMillis(300)).build()) {
            CallOptions options = CallOptions.builder().completionListener(completion::set).build();
            StepVerifier.create(client.stream(new LlmRequest(endpoint(server), Map.of(), options)))
                    .expectNextCount(2).expectError(java.util.concurrent.TimeoutException.class).verify(WAIT);
            assertThat(completion.get().outcome()).isEqualTo(CallOutcome.ERROR);
            assertThat(completion.get().usage()).isPresent();
        } finally { server.disposeNow(); }
    }

    @Test
    void honoursDemandBeforeCancellation() {
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) ->
                res.header("Content-Type", "text/event-stream").sendString(Flux.just(
                        "data: {}\n\n", "data: " + USAGE + "\n\n", "data: [DONE]\n\n"))).bindNow();
        try (LlmClient client = LlmClient.builder().build()) {
            StepVerifier.create(client.stream(new LlmRequest(endpoint(server), Map.of())), 0)
                    .thenRequest(1).expectNextMatches(LlmStreamEvent.Headers.class::isInstance)
                    .thenAwait(Duration.ofMillis(30))
                    .thenRequest(1).expectNextMatches(LlmStreamEvent.Data.class::isInstance)
                    .thenCancel().verify(WAIT);
        } finally { server.disposeNow(); }
    }

    @Test
    void doesNotDisposeInjectedConnectionPool() {
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) ->
                res.header("Content-Type", "application/json").sendString(Mono.just("{}"))).bindNow();
        ConnectionProvider pool = ConnectionProvider.create("caller-owned", 1);
        WebClient web = WebClient.builder().clientConnector(new ReactorClientHttpConnector(HttpClient.create(pool))).build();
        try {
            try (LlmClient first = LlmClient.builder().webClient(web).build()) {
                assertThat(first.execute(new LlmRequest(endpoint(server), Map.of())).block(WAIT).statusCode()).isEqualTo(200);
            }
            assertThat(pool.isDisposed()).isFalse();
            try (LlmClient second = LlmClient.builder().webClient(web).build()) {
                assertThat(second.execute(new LlmRequest(endpoint(server), Map.of())).block(WAIT).statusCode()).isEqualTo(200);
            }
        } finally { pool.disposeLater().block(WAIT); server.disposeNow(); }
    }

    @Test
    void preservesSubscriberContextForInjectedWebClientFilters() {
        AtomicReference<String> correlation = new AtomicReference<>();
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) ->
                res.header("Content-Type", "application/json").sendString(Mono.just("{}"))).bindNow();
        WebClient web = WebClient.builder().filter((request, next) -> Mono.deferContextual(context -> {
            correlation.set(context.getOrDefault("correlation-id", "missing"));
            return next.exchange(request);
        })).build();
        try (LlmClient client = LlmClient.builder().webClient(web).build()) {
            client.execute(new LlmRequest(endpoint(server), Map.of()))
                    .contextWrite(context -> context.put("correlation-id", "request-1")).block(WAIT);
            assertThat(correlation.get()).isEqualTo("request-1");
        } finally { server.disposeNow(); }
    }

    @Test
    void doesNotRetryConnectionReset() {
        AtomicInteger attempts = new AtomicInteger();
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0)
                .doOnConnection(connection -> { attempts.incrementAndGet(); connection.dispose(); })
                .handle((req, res) -> Mono.empty()).bindNow();
        try (LlmClient client = LlmClient.builder().build()) {
            StepVerifier.create(client.execute(new LlmRequest(endpoint(server), Map.of())))
                    .expectError().verify(WAIT);
            assertThat(attempts).hasValue(1);
        } finally { server.disposeNow(); }
    }

    private static List<Protocol> protocols() {
        return List.of(Protocol.OPENAI_CHAT, Protocol.OPENAI_RESPONSES, Protocol.ANTHROPIC_MESSAGES,
                Protocol.GEMINI, Protocol.SEEDANCE_VIDEO, Protocol.EMBEDDINGS, Protocol.RERANK);
    }

    private static LlmEndpoint endpoint(DisposableServer server) {
        return LlmEndpoint.builder(Protocol.OPENAI_CHAT, uri(server, "/v1/chat/completions")).build();
    }

    private static URI uri(DisposableServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }
}
