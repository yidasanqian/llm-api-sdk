package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.json.JsonUtil;
import io.github.yidasanqian.llm.protocol.Protocol;
import io.github.yidasanqian.llm.protocol.SseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolBridgeTest {
    static Stream<Arguments> supportedBridges() {
        return Stream.of(
                Arguments.of(Protocol.OPENAI_CHAT, Protocol.OPENAI_RESPONSES),
                Arguments.of(Protocol.OPENAI_CHAT, Protocol.ANTHROPIC_MESSAGES),
                Arguments.of(Protocol.ANTHROPIC_MESSAGES, Protocol.OPENAI_RESPONSES),
                Arguments.of(Protocol.ANTHROPIC_MESSAGES, Protocol.OPENAI_CHAT));
    }

    @ParameterizedTest
    @MethodSource("supportedBridges")
    void selectsBothDirectionsWithoutModifyingTheInput(Protocol client, Protocol upstream) {
        ProtocolBridge bridge = new ProtocolBridge(client, upstream);
        Map<String, Object> request = Map.of("model", "public-model", "max_tokens", 256,
                "messages", List.of(Map.of("role", "user", "content", "hello")));

        Map<String, Object> mapped = bridge.mapRequest(request, true);
        assertEquals(Boolean.TRUE, mapped.get("stream"));
        assertFalse(mapped.containsKey("model"));
        assertEquals("public-model", request.get("model"));
        assertFalse(request.containsKey("stream"));
        assertTrue(mapped.containsKey(upstream.equals(Protocol.OPENAI_RESPONSES) ? "input" : "messages"));

        JsonNode response = JsonUtil.toObject(bridge.mapResponse(response(upstream)), JsonNode.class);
        String text = client.equals(Protocol.OPENAI_CHAT)
                ? response.path("choices").path(0).path("message").path("content").asText()
                : response.path("content").path(0).path("text").asText();
        assertEquals("hello", text);
    }

    @Test
    void sameProtocolPreservesNativeFieldsAndErrors() {
        ProtocolBridge bridge = new ProtocolBridge(Protocol.GEMINI, Protocol.GEMINI);
        Map<String, Object> nativePayload = Map.of("contents", List.of(), "vendor_extension", true);
        assertEquals(nativePayload, bridge.mapRequest(nativePayload, false));
        String body = "{\"vendor_extension\":true}";
        assertSame(body, bridge.mapResponse(body));
        assertSame(body, bridge.mapError(429, body, "req-test"));
    }

    @Test
    void unsupportedConversionIsRejectedBeforeAnyCall() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProtocolBridge(Protocol.GEMINI, Protocol.OPENAI_CHAT));
    }

    @Test
    void eachSubscriptionCreatesItsOwnConversionStateAndOneUpstreamSubscription() {
        ProtocolBridge bridge = new ProtocolBridge(Protocol.OPENAI_CHAT, Protocol.OPENAI_RESPONSES);
        AtomicInteger subscriptions = new AtomicInteger();
        Flux<SseEvent<String>> source = Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Flux.just(
                    event("response.created", "{\"type\":\"response.created\",\"response\":{\"id\":\"resp-test\",\"model\":\"test\",\"created_at\":1}}"),
                    event("response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"item_id\":\"item-1\",\"content_index\":0,\"delta\":\"hello\"}"),
                    event("response.completed", "{\"type\":\"response.completed\",\"response\":" + response(Protocol.OPENAI_RESPONSES) + "}"));
        });

        Flux<SseEvent<String>> converted = bridge.mapStream(source, "req-test");
        List<SseEvent<String>> first = converted.collectList().block();
        List<SseEvent<String>> second = converted.collectList().block();
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(2, subscriptions.get());
        assertEquals(first, second);
        assertTrue(first.stream().anyMatch(event -> event.data().contains("hello")));
        assertEquals(1, first.stream().filter(event -> "[DONE]".equals(event.data())).count());
    }

    @Test
    void mapsHttpErrorsToTheClientEnvelope() {
        ProtocolBridge bridge = new ProtocolBridge(Protocol.ANTHROPIC_MESSAGES, Protocol.OPENAI_RESPONSES);
        JsonNode response = JsonUtil.toObject(bridge.mapError(429,
                "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"busy\"}}", "req-test"), JsonNode.class);
        assertEquals("error", response.path("type").asText());
        assertEquals("busy", response.path("error").path("message").asText());
        assertEquals("rate_limit_error", response.path("error").path("type").asText());
    }

    private static SseEvent<String> event(String name, String data) {
        return SseEvent.<String>builder().event(name).data(data).build();
    }

    private static String response(Protocol protocol) {
        if (Protocol.OPENAI_CHAT.equals(protocol)) return """
                    {"id":"chat-test","model":"test","choices":[{"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}
                    """;
        if (Protocol.OPENAI_RESPONSES.equals(protocol)) return """
                    {"id":"resp-test","model":"test","created_at":1,"status":"completed",
                     "output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"hello"}]}],
                     "usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12}}
                    """;
        if (Protocol.ANTHROPIC_MESSAGES.equals(protocol)) return """
                    {"id":"msg-test","model":"test","type":"message","role":"assistant",
                     "content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn",
                     "usage":{"input_tokens":10,"output_tokens":2}}
                    """;
        throw new IllegalArgumentException(protocol.id());
    }
}
