package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.bridge.openai.OpenAIResponsesToChatCompletionsResponseMapper;
import io.github.yidasanqian.llm.bridge.anthropic.OpenAIChatCompletionsToAnthropicResponseMapper;
import io.github.yidasanqian.llm.protocol.Protocol;
import io.github.yidasanqian.llm.protocol.SseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BridgeStreamSafetyTest {
    static Stream<Arguments> bridges() {
        return Stream.of(
                Arguments.of(Protocol.OPENAI_CHAT, Protocol.OPENAI_RESPONSES),
                Arguments.of(Protocol.ANTHROPIC_MESSAGES, Protocol.OPENAI_RESPONSES),
                Arguments.of(Protocol.ANTHROPIC_MESSAGES, Protocol.OPENAI_CHAT),
                Arguments.of(Protocol.OPENAI_CHAT, Protocol.ANTHROPIC_MESSAGES));
    }

    @ParameterizedTest @MethodSource("bridges")
    void eofWithoutProtocolTerminalProducesAnError(Protocol client, Protocol upstream) {
        List<SseEvent<String>> out = new ProtocolBridge(client, upstream)
                .mapStream(Flux.just(start(upstream)), "test").collectList().block();
        assertTrue(out.stream().anyMatch(e -> e.data().contains("\"error\"")));
        assertFalse(out.stream().anyMatch(e -> e.data().contains("\"finish_reason\":\"stop\"")
                || "message_stop".equals(e.event())));
    }

    @ParameterizedTest @MethodSource("bridges")
    void protocolTerminalAllowsSuccessfulCompletion(Protocol client, Protocol upstream) {
        List<SseEvent<String>> out = new ProtocolBridge(client, upstream)
                .mapStream(Flux.just(start(upstream), terminal(upstream)), "test").collectList().block();
        assertFalse(out.stream().anyMatch(e -> e.data().contains("\"error\"")));
        assertEquals(1, out.stream().filter(e -> "[DONE]".equals(e.data()) || "message_stop".equals(e.event())).count());
    }

    @ParameterizedTest @MethodSource("bridges")
    void upstreamErrorSkipsSuccessValidation(Protocol client, Protocol upstream) {
        String body = upstream.equals(Protocol.ANTHROPIC_MESSAGES)
                ? "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}"
                : "{\"error\":{\"type\":\"server_error\",\"message\":\"busy\"}}";
        for (Flux<SseEvent<String>> source : List.of(Flux.just(event("error", body)),
                Flux.just(start(upstream), event("error", body)))) {
            List<SseEvent<String>> out = new ProtocolBridge(client, upstream)
                    .mapStream(source, "test").collectList().block();
            assertEquals(1, out.stream().filter(e -> e.data().contains("\"error\"")).count());
            assertTrue(out.stream().anyMatch(e -> e.data().contains("busy")));
            assertFalse(out.stream().anyMatch(e -> e.data().contains("\"finish_reason\":\"stop\"")
                    || "message_stop".equals(e.event())));
        }
    }

    @Test void chatDoneWithoutContentDoesNotFailTerminalValidation() {
        assertTrue(new OpenAIChatCompletionsToAnthropicResponseMapper(JsonMapper.builder().build())
                .createStreamReadiness().test(event(null, "[DONE]")));
        List<SseEvent<String>> out = new ProtocolBridge(Protocol.ANTHROPIC_MESSAGES, Protocol.OPENAI_CHAT)
                .mapStream(Flux.just(event(null, "[DONE]")), "test").collectList().block();
        assertTrue(out.isEmpty());
    }

    static Stream<Protocol> responseClients() { return Stream.of(Protocol.OPENAI_CHAT, Protocol.ANTHROPIC_MESSAGES); }

    @ParameterizedTest @MethodSource("responseClients")
    void responsesIgnoresChatDoneAndRetainsFinalUsage(Protocol client) {
        List<SseEvent<String>> out = new ProtocolBridge(client, Protocol.OPENAI_RESPONSES).mapStream(Flux.just(
                start(Protocol.OPENAI_RESPONSES), event(null, "[DONE]"),
                event("response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"item_id\":\"a\",\"delta\":\"later text\"}"),
                terminal(Protocol.OPENAI_RESPONSES)), "test").collectList().block();
        assertTrue(out.stream().anyMatch(e -> e.data().contains("later text")));
        assertTrue(out.stream().anyMatch(e -> e.data().contains("\"output_tokens\":20") || e.data().contains("\"completion_tokens\":20")));
        assertEquals(1, out.stream().filter(e -> "[DONE]".equals(e.data()) || "message_stop".equals(e.event())).count());
        SseEvent<String> last = out.get(out.size()-1);
        assertTrue("[DONE]".equals(last.data()) || "message_stop".equals(last.event()));
    }

    @Test void incompleteResponsesRetainsUsageAndTruncationReason() {
        List<SseEvent<String>> out = new ProtocolBridge(Protocol.ANTHROPIC_MESSAGES, Protocol.OPENAI_RESPONSES)
                .mapStream(Flux.just(start(Protocol.OPENAI_RESPONSES), event("response.incomplete", """
                        {"type":"response.incomplete","response":{"status":"incomplete",
                         "incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":100,"output_tokens":20}}}
                        """)), "test").collectList().block();
        String finalDelta = out.stream().filter(e -> "message_delta".equals(e.event())).findFirst().orElseThrow().data();
        assertTrue(finalDelta.contains("\"stop_reason\":\"max_tokens\""));
        assertTrue(finalDelta.contains("\"input_tokens\":100"));
        assertTrue(finalDelta.contains("\"output_tokens\":20"));
    }

    @Test void toolArgumentsDoNotAccumulateInTheBridgeHeap() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-Xmx64m", "-cp", System.getProperty("java.class.path"),
                ToolStreamProbe.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "tool stream probe timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("streamed=67108864"), output);
        } finally { process.destroyForcibly(); }
    }

    @Test void toolPrefixChecksHandleSplitSurrogatesAndRepeatedSnapshots() {
        var json = JsonMapper.builder().build();
        BridgeStreamSession session = new OpenAIResponsesToChatCompletionsResponseMapper(json).newSession();
        session.map(event("response.output_item.added", "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"a\",\"type\":\"function_call\",\"call_id\":\"c\",\"name\":\"f\"}}"));
        String emoji = "\uD83D\uDE00";
        for (String part : List.of("\uD83D", "\uDE00")) {
            session.map(event("response.function_call_arguments.delta", json.writeValueAsString(Map.of(
                    "type", "response.function_call_arguments.delta", "item_id", "a", "delta", part))));
        }
        assertTrue(session.map(event("response.function_call_arguments.done", json.writeValueAsString(Map.of(
                "type", "response.function_call_arguments.done", "item_id", "a", "arguments", "wrong")))).isEmpty());
        List<SseEvent<String>> suffix = session.map(event("response.function_call_arguments.done", json.writeValueAsString(Map.of(
                "type", "response.function_call_arguments.done", "item_id", "a", "arguments", emoji + "suffix"))));
        assertEquals(1, suffix.size());
        assertTrue(suffix.get(0).data().contains("\"arguments\":\"suffix\""));
        assertTrue(session.map(event("response.function_call_arguments.done", json.writeValueAsString(Map.of(
                "type", "response.function_call_arguments.done", "item_id", "a", "arguments", emoji + "suffix")))).isEmpty());
        assertThrows(io.github.yidasanqian.llm.error.ProtocolConversionException.class,
                () -> session.map(event("response.function_call_arguments.delta", json.writeValueAsString(Map.of(
                        "type", "response.function_call_arguments.delta", "item_id", "a", "delta", "late")))));
    }

    public static class ToolStreamProbe {
        public static void main(String[] args) {
            BridgeStreamSession session = new OpenAIResponsesToChatCompletionsResponseMapper(JsonMapper.builder().build()).newSession();
            session.map(event("response.output_item.added", "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"a\",\"type\":\"function_call\",\"call_id\":\"c\",\"name\":\"f\"}}"));
            SseEvent<String> delta = event("response.function_call_arguments.delta", "{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"a\",\"delta\":\"" + "x".repeat(16384) + "\"}");
            for (int i=0;i<4096;i++) session.map(delta);
            System.out.println("streamed=67108864");
        }
    }

    private static SseEvent<String> start(Protocol upstream) {
        if (upstream.equals(Protocol.OPENAI_RESPONSES)) return event("response.created", "{\"type\":\"response.created\",\"response\":{\"id\":\"r\",\"model\":\"m\"}}");
        if (upstream.equals(Protocol.OPENAI_CHAT)) return event(null, "{\"id\":\"r\",\"model\":\"m\",\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}");
        return event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"r\",\"model\":\"m\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}");
    }
    private static SseEvent<String> terminal(Protocol upstream) {
        if (upstream.equals(Protocol.OPENAI_RESPONSES)) return event("response.completed", "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":100,\"output_tokens\":20}}}");
        if (upstream.equals(Protocol.OPENAI_CHAT)) return event(null, "[DONE]");
        return event("message_stop", "{\"type\":\"message_stop\"}");
    }
    private static SseEvent<String> event(String name, String data) { return SseEvent.<String>builder().event(name).data(data).build(); }
}
