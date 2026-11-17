package io.github.yidasanqian.llm.client;

import io.github.yidasanqian.llm.protocol.SseEvent;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Per-call host hooks run on the reactive chain and must not block. */
public final class CallOptions {
    private static final CallOptions DEFAULTS = builder().build();
    private final Duration continuationTimeout;
    private final Function<LlmResponse, Mono<Void>> rawResponseHandler;
    private final Function<SseEvent<String>, Mono<Void>> rawEventHandler;
    private final Consumer<CallCompletion> completionListener;

    private CallOptions(Builder builder) {
        continuationTimeout = builder.continuationTimeout;
        rawResponseHandler = builder.rawResponseHandler;
        rawEventHandler = builder.rawEventHandler;
        completionListener = builder.completionListener;
    }
    public static CallOptions defaults() { return DEFAULTS; }
    public static Builder builder() { return new Builder(); }
    public Duration continuationTimeout() { return continuationTimeout; }
    public Function<LlmResponse, Mono<Void>> rawResponseHandler() { return rawResponseHandler; }
    public Function<SseEvent<String>, Mono<Void>> rawEventHandler() { return rawEventHandler; }
    public Consumer<CallCompletion> completionListener() { return completionListener; }

    public static final class Builder {
        private Duration continuationTimeout = Duration.ZERO;
        private Function<LlmResponse, Mono<Void>> rawResponseHandler = response -> Mono.empty();
        private Function<SseEvent<String>, Mono<Void>> rawEventHandler = event -> Mono.empty();
        private Consumer<CallCompletion> completionListener = completion -> { };
        /** Zero cancels immediately; a positive duration allows bounded upstream draining after cancellation. */
        public Builder continuationTimeout(Duration value) {
            Objects.requireNonNull(value, "continuationTimeout");
            if (value.isNegative()) throw new IllegalArgumentException("continuationTimeout must not be negative");
            continuationTimeout = value; return this;
        }
        public Builder rawResponseHandler(Function<LlmResponse, Mono<Void>> value) {
            rawResponseHandler = Objects.requireNonNull(value, "rawResponseHandler"); return this;
        }
        public Builder rawEventHandler(Function<SseEvent<String>, Mono<Void>> value) {
            rawEventHandler = Objects.requireNonNull(value, "rawEventHandler"); return this;
        }
        public Builder completionListener(Consumer<CallCompletion> value) {
            completionListener = Objects.requireNonNull(value, "completionListener"); return this;
        }
        public CallOptions build() { return new CallOptions(this); }
    }
}
