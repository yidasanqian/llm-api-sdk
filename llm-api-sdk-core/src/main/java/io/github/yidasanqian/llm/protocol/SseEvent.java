package io.github.yidasanqian.llm.protocol;

import java.time.Duration;

/** A decoded SSE event. HTTP transports own framing and decoding. */
public record SseEvent<T>(String id, String event, Duration retry, String comment, T data) {

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static <T> Builder<T> builder(T data) {
        return new Builder<T>().data(data);
    }

    public static final class Builder<T> {
        private String id;
        private String event;
        private Duration retry;
        private String comment;
        private T data;

        public Builder<T> id(String id) { this.id = id; return this; }
        public Builder<T> event(String event) { this.event = event; return this; }
        public Builder<T> retry(Duration retry) { this.retry = retry; return this; }
        public Builder<T> comment(String comment) { this.comment = comment; return this; }
        public Builder<T> data(T data) { this.data = data; return this; }

        public SseEvent<T> build() {
            return new SseEvent<>(id, event, retry, comment, data);
        }
    }
}
