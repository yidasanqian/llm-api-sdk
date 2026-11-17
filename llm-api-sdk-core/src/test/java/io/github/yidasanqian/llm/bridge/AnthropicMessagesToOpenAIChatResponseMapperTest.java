package io.github.yidasanqian.llm.bridge;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.yidasanqian.llm.bridge.openai.AnthropicMessagesToOpenAIChatResponseMapper;
import org.junit.jupiter.api.Test;
import io.github.yidasanqian.llm.protocol.SseEvent;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicMessagesToOpenAIChatResponseMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnthropicMessagesToOpenAIChatResponseMapper mapper =
            new AnthropicMessagesToOpenAIChatResponseMapper(objectMapper);

    // -------------------------------------------------------------------------
    // Non-streaming
    // -------------------------------------------------------------------------

    @Test
    void shouldMapNonStreamTextResponse() throws Exception {
        String anthropicResponse = """
                {
                  "id": "msg_abc",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-3-5-sonnet-20241022",
                  "content": [{"type": "text", "text": "你好！"}],
                  "stop_reason": "end_turn",
                  "usage": {"input_tokens": 10, "output_tokens": 5}
                }
                """;

        JsonNode result = objectMapper.readTree(mapper.mapNonStream(anthropicResponse));

        assertTrue(result.get("id").asText().startsWith("chatcmpl-"));
        assertEquals("chat.completion", result.get("object").asText());
        assertEquals("claude-3-5-sonnet-20241022", result.get("model").asText());

        JsonNode choice = result.get("choices").get(0);
        assertEquals(0, choice.get("index").asInt());
        assertEquals("stop", choice.get("finish_reason").asText());
        assertEquals("assistant", choice.get("message").get("role").asText());
        assertEquals("你好！", choice.get("message").get("content").asText());

        assertEquals(10, result.get("usage").get("prompt_tokens").asInt());
        assertEquals(5, result.get("usage").get("completion_tokens").asInt());
        assertEquals(15, result.get("usage").get("total_tokens").asInt());
    }

    @Test
    void shouldMapNonStreamToolCallResponse() throws Exception {
        String anthropicResponse = """
                {
                  "id": "msg_tool",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-3-5-sonnet-20241022",
                  "content": [
                    {"type": "text", "text": "我来查一下"},
                    {"type": "tool_use", "id": "toolu_1", "name": "get_weather", "input": {"city": "Hangzhou"}}
                  ],
                  "stop_reason": "tool_use",
                  "usage": {"input_tokens": 20, "output_tokens": 10}
                }
                """;

        JsonNode result = objectMapper.readTree(mapper.mapNonStream(anthropicResponse));

        assertEquals("tool_calls", result.get("choices").get(0).get("finish_reason").asText());

        JsonNode message = result.get("choices").get(0).get("message");
        assertEquals("我来查一下", message.get("content").asText());

        JsonNode toolCall = message.get("tool_calls").get(0);
        assertEquals("toolu_1", toolCall.get("id").asText());
        assertEquals("function", toolCall.get("type").asText());
        assertEquals("get_weather", toolCall.get("function").get("name").asText());
        assertTrue(toolCall.get("function").get("arguments").asText().contains("Hangzhou"));
    }

    @Test
    void shouldMapNonStreamThinkingToReasoningContent() throws Exception {
        String anthropicResponse = """
                {
                  "id": "msg_think",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-3-7-sonnet",
                  "content": [
                    {"type": "thinking", "thinking": "先分析问题..."},
                    {"type": "redacted_thinking", "data": "REDACTED"},
                    {"type": "text", "text": "答案是42"}
                  ],
                  "stop_reason": "end_turn",
                  "usage": {"input_tokens": 5, "output_tokens": 8}
                }
                """;

        JsonNode result = objectMapper.readTree(mapper.mapNonStream(anthropicResponse));

        JsonNode message = result.get("choices").get(0).get("message");
        assertEquals("答案是42", message.get("content").asText());
        assertEquals("先分析问题...REDACTED", message.get("reasoning_content").asText());
    }

    @Test
    void shouldMapNonStreamCacheTokensToPromptTokensDetails() throws Exception {
        String anthropicResponse = """
                {
                  "id": "msg_cache",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-3-5-haiku",
                  "content": [{"type": "text", "text": "ok"}],
                  "stop_reason": "end_turn",
                  "usage": {
                    "input_tokens": 100,
                    "output_tokens": 5,
                    "cache_read_input_tokens": 80,
                    "cache_creation_input_tokens": 70,
                    "cache_creation": {
                      "ephemeral_5m_input_tokens": 50,
                      "ephemeral_1h_input_tokens": 30
                    }
                  }
                }
                """;

        JsonNode result = objectMapper.readTree(mapper.mapNonStream(anthropicResponse));

        JsonNode usage = result.get("usage");
        assertEquals(250, usage.get("prompt_tokens").asInt());
        assertEquals(5, usage.get("completion_tokens").asInt());
        assertEquals(255, usage.get("total_tokens").asInt());
        assertEquals(80, usage.get("prompt_tokens_details").get("cached_tokens").asInt());
        assertEquals(70, usage.get("prompt_tokens_details").get("cache_write_tokens").asInt());
    }

    @Test
    void shouldUseCacheCreationTtlBreakdownWhenLegacyTotalMissing() throws Exception {
        String anthropicResponse = """
                {
                  "id": "msg_cache_ttl",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-3-5-haiku",
                  "content": [{"type": "text", "text": "ok"}],
                  "stop_reason": "end_turn",
                  "usage": {
                    "input_tokens": 50,
                    "output_tokens": 5,
                    "cache_creation": {
                      "ephemeral_5m_input_tokens": 30,
                      "ephemeral_1h_input_tokens": 10
                    }
                  }
                }
                """;

        JsonNode usage = objectMapper.readTree(mapper.mapNonStream(anthropicResponse)).get("usage");

        assertEquals(90, usage.get("prompt_tokens").asInt());
        assertEquals(95, usage.get("total_tokens").asInt());
        assertEquals(40, usage.get("prompt_tokens_details").get("cache_write_tokens").asInt());
        assertNull(usage.get("prompt_tokens_details").get("cached_tokens"));
    }

    @Test
    void shouldMapMaxTokensStopReasonToLength() throws Exception {
        String anthropicResponse = """
                {
                  "id": "msg_len",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-3-5-haiku",
                  "content": [{"type": "text", "text": "..."}],
                  "stop_reason": "max_tokens",
                  "usage": {"input_tokens": 5, "output_tokens": 100}
                }
                """;

        JsonNode result = objectMapper.readTree(mapper.mapNonStream(anthropicResponse));

        assertEquals("length", result.get("choices").get(0).get("finish_reason").asText());
    }

    // -------------------------------------------------------------------------
    // Streaming
    // -------------------------------------------------------------------------

    @Test
    void shouldMapStreamTextChunks() {
        List<SseEvent<String>> input = List.of(
                sseEvent("message_start", "{\"message\":{\"id\":\"msg_s1\",\"model\":\"claude-3-5-sonnet\",\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}"),
                sseEvent("content_block_start", "{\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"),
                sseEvent("content_block_delta", "{\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}"),
                sseEvent("content_block_delta", "{\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"世界\"}}"),
                sseEvent("content_block_stop", "{\"index\":0}"),
                sseEvent("message_delta", "{\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}"),
                sseEvent("message_stop", "{\"type\":\"message_stop\"}")
        );

        List<SseEvent<String>> output = BridgeStreamTestSupport.mapStream(
                mapper, Flux.fromIterable(input)).collectList().block();

        assertNotNull(output);
        // message_start → role chunk
        assertTrue(output.get(0).data().contains("\"role\":\"assistant\""));
        // text deltas
        assertTrue(output.stream().anyMatch(e -> e.data() != null && e.data().contains("你好")));
        assertTrue(output.stream().anyMatch(e -> e.data() != null && e.data().contains("世界")));
        // message_delta → chunk with finish_reason + usage
        assertTrue(output.stream().anyMatch(e -> e.data() != null && e.data().contains("\"stop\"")));
        // message_stop → [DONE]
        assertEquals("[DONE]", output.get(output.size() - 1).data());
    }

    @Test
    void shouldMapStreamToolCallChunks() {
        List<SseEvent<String>> input = List.of(
                sseEvent("message_start", "{\"message\":{\"id\":\"msg_t1\",\"model\":\"claude-3-5-sonnet\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}"),
                sseEvent("content_block_start", "{\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\"}}"),
                sseEvent("content_block_delta", "{\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\\\"Hang\"}}"),
                sseEvent("content_block_delta", "{\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"zhou\\\"}\"}}"),
                sseEvent("content_block_stop", "{\"index\":0}"),
                sseEvent("message_delta", "{\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":7}}"),
                sseEvent("message_stop", "{\"type\":\"message_stop\"}")
        );

        List<SseEvent<String>> output = BridgeStreamTestSupport.mapStream(
                mapper, Flux.fromIterable(input)).collectList().block();

        assertNotNull(output);
        // tool_call start chunk: contains id + name
        assertTrue(output.stream().anyMatch(e -> e.data() != null
                && e.data().contains("toolu_1") && e.data().contains("get_weather")));
        // partial_json delta chunks
        assertTrue(output.stream().anyMatch(e -> e.data() != null && e.data().contains("Hang")));
        // finish_reason = tool_calls
        assertTrue(output.stream().anyMatch(e -> e.data() != null && e.data().contains("\"tool_calls\"")));
        assertEquals("[DONE]", output.get(output.size() - 1).data());
    }

    @Test
    void shouldMapStreamThinkingDelta() {
        List<SseEvent<String>> input = List.of(
                sseEvent("message_start", "{\"message\":{\"id\":\"msg_th\",\"model\":\"claude-3-7-sonnet\",\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}"),
                sseEvent("content_block_start", "{\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}"),
                sseEvent("content_block_delta", "{\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"分析中...\"}}"),
                sseEvent("content_block_stop", "{\"index\":0}"),
                sseEvent("message_delta", "{\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}"),
                sseEvent("message_stop", "{\"type\":\"message_stop\"}")
        );

        List<SseEvent<String>> output = BridgeStreamTestSupport.mapStream(
                mapper, Flux.fromIterable(input)).collectList().block();

        assertNotNull(output);
        assertTrue(output.stream().anyMatch(e -> e.data() != null && e.data().contains("reasoning_content")));
        assertTrue(output.stream().anyMatch(e -> e.data() != null && e.data().contains("分析中...")));
    }

    @Test
    void shouldIgnorePingAndEmitDoneOnMessageStop() {
        List<SseEvent<String>> input = List.of(
                SseEvent.<String>builder().event("ping").build(),
                sseEvent("message_start", "{\"message\":{\"id\":\"msg_p\",\"model\":\"claude-3-5-sonnet\",\"usage\":{}}}"),
                sseEvent("content_block_start", "{\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"),
                sseEvent("content_block_delta", "{\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}"),
                sseEvent("content_block_stop", "{\"index\":0}"),
                sseEvent("message_delta", "{\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}"),
                sseEvent("message_stop", "{\"type\":\"message_stop\"}")
        );

        List<SseEvent<String>> output = BridgeStreamTestSupport.mapStream(
                mapper, Flux.fromIterable(input)).collectList().block();

        assertNotNull(output);
        // ping is ignored (no chunk with null/empty data from ping)
        assertTrue(output.stream().noneMatch(e -> e.data() == null));
        assertEquals("[DONE]", output.get(output.size() - 1).data());
    }

    @Test
    void shouldIncludeUsageInFinalStreamChunk() {
        List<SseEvent<String>> input = List.of(
                sseEvent("message_start", "{\"message\":{\"id\":\"msg_u\",\"model\":\"claude-3-5-sonnet\",\"usage\":{\"input_tokens\":20,\"output_tokens\":0,\"cache_read_input_tokens\":12}}}"),
                sseEvent("message_delta", "{\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":8,\"cache_creation\":{\"ephemeral_5m_input_tokens\":3,\"ephemeral_1h_input_tokens\":5}}}"),
                sseEvent("message_stop", "{\"type\":\"message_stop\"}")
        );

        List<SseEvent<String>> output = BridgeStreamTestSupport.mapStream(
                mapper, Flux.fromIterable(input)).collectList().block();

        assertNotNull(output);
        // find the chunk with finish_reason (message_delta mapped chunk)
        String finalChunk = output.stream()
                .filter(e -> e.data() != null && e.data().contains("\"prompt_tokens\""))
                .findFirst()
                .map(SseEvent::data)
                .orElse("");

        assertTrue(finalChunk.contains("\"prompt_tokens\":40"));
        assertTrue(finalChunk.contains("\"completion_tokens\":8"));
        assertTrue(finalChunk.contains("\"total_tokens\":48"));
        assertTrue(finalChunk.contains("\"cached_tokens\":12"));
        assertTrue(finalChunk.contains("\"cache_write_tokens\":8"));
    }

    @Test
    void shouldClearStreamCacheUsageWhenMessageDeltaExplicitlyReportsZero() throws Exception {
        List<SseEvent<String>> input = List.of(
                sseEvent("message_start", "{\"message\":{\"id\":\"msg_u_zero\",\"model\":\"claude-3-5-sonnet\",\"usage\":{\"input_tokens\":20,\"cache_read_input_tokens\":12,\"cache_creation_input_tokens\":8}}}"),
                sseEvent("message_delta", "{\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":8,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0}}"),
                sseEvent("message_stop", "{\"type\":\"message_stop\"}")
        );

        List<SseEvent<String>> output = BridgeStreamTestSupport.mapStream(
                mapper, Flux.fromIterable(input)).collectList().block();
        String finalChunk = output.stream()
                .filter(event -> event.data() != null && event.data().contains("\"prompt_tokens\""))
                .findFirst()
                .map(SseEvent::data)
                .orElseThrow();

        JsonNode usage = objectMapper.readTree(finalChunk).get("usage");
        assertEquals(20, usage.get("prompt_tokens").asInt());
        assertEquals(8, usage.get("completion_tokens").asInt());
        assertEquals(28, usage.get("total_tokens").asInt());
        assertNull(usage.get("prompt_tokens_details"));
    }

    private SseEvent<String> sseEvent(String event, String data) {
        return SseEvent.<String>builder().event(event).data(data).build();
    }
}
