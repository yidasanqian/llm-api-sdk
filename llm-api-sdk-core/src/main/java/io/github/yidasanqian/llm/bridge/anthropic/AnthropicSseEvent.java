package io.github.yidasanqian.llm.bridge.anthropic;

import io.github.yidasanqian.llm.protocol.SseEvent;

public class AnthropicSseEvent {

    private final String event;
    private final String data;

    public AnthropicSseEvent(String event, String data) {
        this.event = event;
        this.data = data;
    }

    public SseEvent<String> toSseEvent() {
        return SseEvent.<String>builder()
                .event(event)
                .data(data)
                .build();
    }
}
