package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.protocol.Protocol;
import io.github.yidasanqian.llm.protocol.SseEvent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolAdapterRegistryTest {
    @Test
    void thirdPartyGeminiChatAdapterCanBeRegisteredWithoutChangingBuiltins() {
        ProtocolAdapterRegistry registry = ProtocolAdapterRegistry.builder()
                .builtins()
                .register(Protocol.GEMINI, Protocol.OPENAI_CHAT, new CommunityGeminiChatAdapter())
                .build();
        ProtocolBridge bridge = new ProtocolBridge(Protocol.of("gemini"), Protocol.of("openai-chat"), registry);
        assertEquals("hello", ((Map<?, ?>) ((List<?>) bridge.mapRequest(
                Map.of("contents", "hello"), false).get("messages")).get(0)).get("content"));
        assertEquals("gemini:upstream", bridge.mapResponse("upstream"));
        assertEquals("gemini-error:429", bridge.mapError(429, "upstream-error", "test"));
        assertEquals("gemini:chunk", bridge.mapStream(Flux.just(SseEvent.builder("chunk").build()), "test")
                .blockFirst().data());
        assertFalse(bridge.streamReadiness("test").test(SseEvent.<String>builder().comment("ping").build()));
        assertTrue(bridge.streamReadiness("test").test(SseEvent.builder("content").build()));
    }

    @Test
    void customProtocolIdsAreNotRestrictedToBuiltinConstants() {
        Protocol custom = Protocol.of("community-video-v1");
        ProtocolAdapterRegistry registry = ProtocolAdapterRegistry.builder()
                .register(custom, Protocol.OPENAI_CHAT, new CommunityGeminiChatAdapter()).build();
        assertEquals("gemini:upstream", new ProtocolBridge(Protocol.of("community-video-v1"),
                Protocol.OPENAI_CHAT, registry).mapResponse("upstream"));
    }

    @Test
    void duplicatePairsCannotSilentlyReplaceExistingAdapters() {
        ProtocolAdapterRegistry.Builder builder = ProtocolAdapterRegistry.builder().builtins();
        assertThrows(IllegalArgumentException.class, () -> builder.register(
                Protocol.of("openai-chat"), Protocol.OPENAI_RESPONSES, new CommunityGeminiChatAdapter()));
    }

    @Test
    void unsupportedPairsAreRejectedAndEqualIdsPassThrough() {
        ProtocolAdapterRegistry registry = ProtocolAdapterRegistry.builder().build();
        assertThrows(IllegalArgumentException.class,
                () -> new ProtocolBridge(Protocol.GEMINI, Protocol.OPENAI_CHAT, registry));
        ProtocolBridge nativeBridge = new ProtocolBridge(Protocol.of("custom-wire"),
                Protocol.of("custom-wire"), registry);
        assertEquals("raw", nativeBridge.mapResponse("raw"));
        assertThrows(IllegalArgumentException.class, () -> ProtocolAdapterRegistry.builder()
                .register(Protocol.GEMINI, Protocol.of("gemini"), new CommunityGeminiChatAdapter()));
    }

    /** This adapter lives outside SDK production code, just as a community extension would. */
    private static final class CommunityGeminiChatAdapter implements ProtocolAdapter {
        @Override
        public Map<String, Object> mapRequest(Map<String, Object> request, boolean stream) {
            return Map.of("messages", List.of(Map.of("role", "user", "content", request.get("contents"))),
                    "stream", stream);
        }

        @Override
        public String mapResponse(String upstreamBody) { return "gemini:" + upstreamBody; }

        @Override
        public Flux<SseEvent<String>> mapStream(Flux<SseEvent<String>> source, String requestId) {
            return source.map(event -> SseEvent.builder("gemini:" + event.data()).build());
        }

        @Override
        public Predicate<SseEvent<String>> streamReadiness(String requestId) {
            return event -> event.data() != null;
        }

        @Override
        public String mapError(int statusCode, String body, String requestId) {
            return "gemini-error:" + statusCode;
        }
    }
}
