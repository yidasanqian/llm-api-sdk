package io.github.yidasanqian.llm.client;

import io.github.yidasanqian.llm.protocol.Protocol;
import io.github.yidasanqian.llm.protocol.SseEvent;
import io.github.yidasanqian.llm.usage.Usage;
import io.github.yidasanqian.llm.usage.UsageAccumulator;
import io.github.yidasanqian.llm.usage.UsageContext;
import io.github.yidasanqian.llm.usage.UsageParser;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.util.context.Context;
import reactor.netty.resources.ConnectionProvider;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reactive native JSON/SSE transport without a Spring container. Each subscription is a new attempt;
 * a stream's headers, events, usage and completion notification share that attempt's single HTTP request.
 */
public final class LlmClient implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(LlmClient.class);
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE = new ParameterizedTypeReference<>() { };
    private final WebClient webClient;
    private final ConnectionProvider ownedPool;
    private final JsonMapper mapper;
    private final UsageParser usageParser;
    private final Duration idleTimeout;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Forwarder<?>> active = ConcurrentHashMap.newKeySet();

    private LlmClient(Builder builder) {
        mapper = builder.mapper;
        usageParser = builder.usageParser == null ? new UsageParser(mapper) : builder.usageParser;
        idleTimeout = builder.idleTimeout;
        if (builder.webClient != null) {
            ownedPool = null;
            webClient = builder.webClient;
        } else {
            ownedPool = ConnectionProvider.builder("llm-api-sdk").maxConnections(builder.maxConnections).build();
            webClient = WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(HttpClient.create(ownedPool).disableRetry(true)))
                    .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(builder.maxInMemorySize))
                    .build();
        }
    }

    public static Builder builder() { return new Builder(); }

    public Mono<LlmResponse> execute(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        return Mono.defer(() -> {
            ensureOpen();
            CallState state = new CallState(request);
            Flux<LlmResponse> source = post(request, false).exchangeToMono(response -> {
                state.statusCode = response.statusCode().value();
                return response.bodyToMono(String.class).defaultIfEmpty("").flatMap(body -> {
                    state.jsonUsage(body);
                    LlmResponse result = new LlmResponse(state.statusCode,
                            ResponseHeaders.from(response.headers().asHttpHeaders()), body, state.confirmed);
                    return Mono.defer(() -> request.options().rawResponseHandler().apply(result)).thenReturn(result);
                });
            }).timeout(idleTimeout).flux();
            return forward(source, state, false).single();
        });
    }

    /** Headers arrive first. Apply protocol mapping only to Data events, after raw host hooks have completed. */
    public Flux<LlmStreamEvent> stream(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        Protocol protocol = request.endpoint().protocol();
        if (protocol.equals(Protocol.EMBEDDINGS) || protocol.equals(Protocol.RERANK)
                || protocol.equals(Protocol.SEEDANCE_VIDEO)) {
            return Flux.error(new IllegalArgumentException("Embedding, rerank and Seedance video endpoints do not support streaming"));
        }
        return Flux.defer(() -> {
            ensureOpen();
            CallState state = new CallState(request);
            Flux<LlmStreamEvent> source = post(request, true)
                    .exchangeToFlux(response -> streamBody(response, state)).timeout(idleTimeout);
            return forward(source, state, true);
        });
    }

    private Flux<LlmStreamEvent> streamBody(ClientResponse response, CallState state) {
        int status = response.statusCode().value();
        state.statusCode = status;
        Map<String, java.util.List<String>> headers = ResponseHeaders.from(response.headers().asHttpHeaders());
        LlmStreamEvent.Headers head = new LlmStreamEvent.Headers(status, headers);
        if (!response.statusCode().is2xxSuccessful()) {
            return Flux.concat(Mono.just(head), response.bodyToMono(String.class).defaultIfEmpty("")
                    .flatMap(body -> {
                        LlmResponse raw = new LlmResponse(status, headers, body, Optional.empty());
                        return Mono.defer(() -> state.options.rawResponseHandler().apply(raw))
                                .thenReturn((LlmStreamEvent) new LlmStreamEvent.Failure(status, headers, body));
                    }));
        }
        MediaType contentType = response.headers().contentType().orElse(null);
        if (contentType == null || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
            return Flux.concat(Mono.just(head), Flux.error(new UnexpectedContentTypeException(String.valueOf(contentType))));
        }
        Flux<LlmStreamEvent> body = response.bodyToFlux(SSE_TYPE).map(LlmClient::toEvent)
                .concatMap(event -> Mono.defer(() -> {
                    state.event(event);
                    ObservedEvent observed = new ObservedEvent(event, state.terminal);
                    return state.options.rawEventHandler().apply(event).thenReturn(observed);
                }), 1)
                .takeUntil(ObservedEvent::terminal)
                .map(observed -> new LlmStreamEvent.Data(observed.event()));
        return Flux.concat(Mono.just(head), body);
    }

    private WebClient.RequestHeadersSpec<?> post(LlmRequest request, boolean stream) {
        LlmEndpoint endpoint = request.endpoint();
        Map<String, Object> body = new LinkedHashMap<>(request.body());
        Protocol protocol = endpoint.protocol();
        boolean conversation = protocol.equals(Protocol.OPENAI_CHAT) || protocol.equals(Protocol.OPENAI_RESPONSES)
                || protocol.equals(Protocol.ANTHROPIC_MESSAGES);
        boolean inputOnly = protocol.equals(Protocol.EMBEDDINGS) || protocol.equals(Protocol.RERANK);
        boolean modelInBody = conversation || inputOnly || protocol.equals(Protocol.SEEDANCE_VIDEO);
        if (modelInBody && endpoint.model() != null) body.put("model", endpoint.model());
        if (conversation) body.put("stream", stream);
        if (stream && protocol.equals(Protocol.OPENAI_CHAT)) {
            Map<String, Object> streamOptions = new LinkedHashMap<>();
            Object supplied = body.get("stream_options");
            if (supplied instanceof Map<?, ?> options) options.forEach((key, value) -> streamOptions.put(String.valueOf(key), value));
            streamOptions.putIfAbsent("include_usage", true);
            body.put("stream_options", streamOptions);
        }
        return webClient.post().uri(stream ? endpoint.streamUri() : endpoint.uri())
                .headers(headers -> endpoint.headers().forEach(headers::set))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(stream ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON)
                .bodyValue(mapper.writeValueAsString(body));
    }

    private <T> Flux<T> forward(Flux<T> source, CallState state, boolean allowDrain) {
        return Flux.create(sink -> {
            Forwarder<T> forwarder = new Forwarder<>(sink, state, allowDrain);
            active.add(forwarder);
            sink.onCancel(forwarder::downstreamCancel);
            if (closed.get()) forwarder.abort();
            else if (!state.finished.get()) source.subscribe(forwarder);
        }, FluxSink.OverflowStrategy.ERROR);
    }

    private record ObservedEvent(SseEvent<String> event, boolean terminal) { }

    private static SseEvent<String> toEvent(ServerSentEvent<String> event) {
        return SseEvent.<String>builder().id(event.id()).event(event.event()).data(event.data())
                .comment(event.comment()).retry(event.retry()).build();
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("LlmClient is closed");
    }

    /** Cancels this client's attempts and disposes only the connection pool created by this client. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        active.forEach(Forwarder::abort);
        if (ownedPool != null) ownedPool.dispose();
    }

    private final class CallState {
        private final CallOptions options;
        private final UsageContext context;
        private final UsageAccumulator accumulator;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile int statusCode;
        private volatile boolean downstreamCancelled;
        private volatile boolean terminal;
        private volatile Optional<Usage> confirmed = Optional.empty();
        private volatile Optional<Usage> partial = Optional.empty();

        CallState(LlmRequest request) {
            options = request.options();
            context = new UsageContext(request.endpoint().protocol(), request.endpoint().providerId());
            accumulator = new UsageAccumulator(context, usageParser);
        }
        synchronized void jsonUsage(String body) {
            if (finished.get()) return;
            confirmed = statusCode >= 200 && statusCode < 300 ? usageParser.parse(body, context) : Optional.empty();
            partial = confirmed;
        }
        synchronized void event(SseEvent<String> event) {
            if (finished.get()) return;
            accumulator.accept(event);
            confirmed = accumulator.confirmedUsage();
            partial = accumulator.latestUsage();
            terminal = accumulator.terminal();
        }
        boolean finish(CallOutcome outcome, Throwable error) {
            CallCompletion completion;
            synchronized (this) {
                if (!finished.compareAndSet(false, true)) return false;
                completion = new CallCompletion(outcome, statusCode, confirmed, partial, downstreamCancelled, error);
            }
            try {
                options.completionListener().accept(completion);
            } catch (RuntimeException listenerError) {
                LOGGER.warn("Completion listener failed ({})", listenerError.getClass().getSimpleName());
            }
            return true;
        }
        CallOutcome normalOutcome() { return statusCode >= 200 && statusCode < 300 ? CallOutcome.COMPLETED : CallOutcome.HTTP_ERROR; }
    }

    /** A single upstream subscriber forwards demand until cancellation, then optionally drains without buffering. */
    private final class Forwarder<T> extends BaseSubscriber<T> {
        private final FluxSink<T> sink;
        private final CallState state;
        private final boolean allowDrain;
        private final Disposable.Swap continuation = Disposables.swap();

        Forwarder(FluxSink<T> sink, CallState state, boolean allowDrain) {
            this.sink = sink;
            this.state = state;
            this.allowDrain = allowDrain;
        }
        @Override public Context currentContext() { return Context.of(sink.contextView()); }
        @Override protected void hookOnSubscribe(Subscription subscription) {
            if (state.finished.get()) { cancel(); return; }
            sink.onRequest(this::request);
            if (state.downstreamCancelled) requestUnbounded();
        }
        @Override protected void hookOnNext(T value) {
            if (!state.downstreamCancelled) sink.next(value);
        }
        @Override protected void hookOnComplete() {
            if (finish(state.normalOutcome(), null)) sink.complete();
        }
        @Override protected void hookOnError(Throwable error) {
            if (finish(CallOutcome.ERROR, error) && !state.downstreamCancelled) sink.error(error);
        }
        @Override protected void hookFinally(SignalType type) {
            release();
        }
        private void release() {
            continuation.dispose();
            active.remove(this);
        }
        private boolean finish(CallOutcome outcome, Throwable error) {
            // Cleanup also covers cancellation before BaseSubscriber receives an upstream subscription.
            // Remove this attempt before invoking a listener that may close the client reentrantly.
            release();
            return state.finish(outcome, error);
        }
        void downstreamCancel() {
            state.downstreamCancelled = true;
            Duration timeout = state.options.continuationTimeout();
            if (!allowDrain || timeout.isZero()) {
                finish(CallOutcome.CANCELLED, null);
                cancel();
            } else if (!state.finished.get()) {
                continuation.update(Schedulers.parallel().schedule(() -> {
                    finish(CallOutcome.CONTINUATION_TIMEOUT, null);
                    cancel();
                }, timeout.toNanos(), TimeUnit.NANOSECONDS));
                requestUnbounded();
            }
        }
        void abort() {
            boolean aborted = finish(CallOutcome.CANCELLED, null);
            cancel();
            if (aborted && !sink.isCancelled()) sink.error(new IllegalStateException("LlmClient is closed"));
        }
    }

    public static final class Builder {
        private WebClient webClient;
        private UsageParser usageParser;
        private JsonMapper mapper = JsonMapper.builder().build();
        private Duration idleTimeout = Duration.ofSeconds(60);
        private int maxConnections = 100;
        private int maxInMemorySize = 16 * 1024 * 1024;
        private Builder() { }
        /** An injected WebClient, its transport retry policy and connection resources remain owned by the caller. */
        public Builder webClient(WebClient value) { webClient = Objects.requireNonNull(value, "webClient"); return this; }
        public Builder usageParser(UsageParser value) { usageParser = Objects.requireNonNull(value, "usageParser"); return this; }
        public Builder jsonMapper(JsonMapper value) { mapper = Objects.requireNonNull(value, "jsonMapper"); return this; }
        /**
         * Limits the interval between observable SSE events, including hook execution and downstream
         * backpressure waits. For JSON calls this limits the entire response and raw-response hook.
         * This is not a socket-read-only timeout.
         */
        public Builder idleTimeout(Duration value) {
            if (Objects.requireNonNull(value, "idleTimeout").isZero() || value.isNegative())
                throw new IllegalArgumentException("idleTimeout must be positive");
            idleTimeout = value; return this;
        }
        public Builder maxConnections(int value) {
            if (value <= 0) throw new IllegalArgumentException("maxConnections must be positive");
            maxConnections = value; return this;
        }
        public Builder maxInMemorySize(int value) {
            if (value <= 0) throw new IllegalArgumentException("maxInMemorySize must be positive");
            maxInMemorySize = value; return this;
        }
        public LlmClient build() { return new LlmClient(this); }
    }
}
