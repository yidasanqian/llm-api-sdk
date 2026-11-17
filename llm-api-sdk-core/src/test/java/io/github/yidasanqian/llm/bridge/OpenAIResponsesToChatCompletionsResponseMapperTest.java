package io.github.yidasanqian.llm.bridge;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.yidasanqian.llm.bridge.BridgeStreamSession;
import io.github.yidasanqian.llm.bridge.openai.OpenAIResponsesToChatCompletionsResponseMapper;
import org.junit.jupiter.api.Test;
import io.github.yidasanqian.llm.protocol.SseEvent;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenAIResponsesToChatCompletionsResponseMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAIResponsesToChatCompletionsResponseMapper mapper =
            new OpenAIResponsesToChatCompletionsResponseMapper(objectMapper);

    // ─────────────────────────── 非流式 ───────────────────────────

    @Test
    void shouldMapNonStreamTextResponse() throws Exception {
        String responsesBody = """
                {
                  "id": "resp_abc",
                  "object": "response",
                  "created_at": 1700000000,
                  "status": "completed",
                  "model": "gpt-5.3-codex",
                  "output": [
                    {
                      "type": "message",
                      "id": "msg_1",
                      "role": "assistant",
                      "status": "completed",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "你好！有什么需要帮助的？"
                        }
                      ]
                    }
                  ],
                  "usage": {
                    "input_tokens": 10,
                    "output_tokens": 8,
                    "total_tokens": 18
                  }
                }
                """;

        Map<?, ?> result = objectMapper.readValue(mapper.mapNonStream(responsesBody), Map.class);

        assertEquals("chat.completion", result.get("object"));
        assertEquals("gpt-5.3-codex", result.get("model"));
        assertEquals(1700000000, result.get("created"));

        List<?> choices = (List<?>) result.get("choices");
        assertEquals(1, choices.size());

        Map<?, ?> choice = (Map<?, ?>) choices.get(0);
        assertEquals("stop", choice.get("finish_reason"));

        Map<?, ?> message = (Map<?, ?>) choice.get("message");
        assertEquals("assistant", message.get("role"));
        assertEquals("你好！有什么需要帮助的？", message.get("content"));

        Map<?, ?> usage = (Map<?, ?>) result.get("usage");
        assertEquals(10, usage.get("prompt_tokens"));
        assertEquals(8, usage.get("completion_tokens"));
        assertEquals(18, usage.get("total_tokens"));
    }

    @Test
    void shouldMapNonStreamFunctionCallResponse() throws Exception {
        String responsesBody = """
                {
                  "id": "resp_fc",
                  "status": "completed",
                  "model": "gpt-5.3-codex",
                  "output": [
                    {
                      "type": "function_call",
                      "id": "fc_1",
                      "call_id": "call_xyz",
                      "name": "get_weather",
                      "arguments": "{\\"city\\":\\"Hangzhou\\"}",
                      "status": "completed"
                    }
                  ],
                  "usage": {
                    "input_tokens": 12,
                    "output_tokens": 5,
                    "total_tokens": 17
                  }
                }
                """;

        Map<?, ?> result = objectMapper.readValue(mapper.mapNonStream(responsesBody), Map.class);

        List<?> choices = (List<?>) result.get("choices");
        Map<?, ?> choice = (Map<?, ?>) choices.get(0);
        assertEquals("tool_calls", choice.get("finish_reason"));

        Map<?, ?> message = (Map<?, ?>) choice.get("message");
        assertNull(message.get("content"));

        List<?> toolCalls = (List<?>) message.get("tool_calls");
        assertNotNull(toolCalls);
        assertEquals(1, toolCalls.size());

        Map<?, ?> tc = (Map<?, ?>) toolCalls.get(0);
        assertEquals("call_xyz", tc.get("id"));
        assertEquals("function", tc.get("type"));

        Map<?, ?> function = (Map<?, ?>) tc.get("function");
        assertEquals("get_weather", function.get("name"));
        assertEquals("{\"city\":\"Hangzhou\"}", function.get("arguments"));
    }

    @Test
    void shouldMapNonStreamCustomToolCallResponse() throws Exception {
        String responsesBody = """
                {
                  "id": "resp_custom",
                  "status": "completed",
                  "model": "gpt-5.3-codex",
                  "output": [
                    {
                      "type": "custom_tool_call",
                      "id": "ctc_1",
                      "call_id": "call_python",
                      "name": "run_python",
                      "input": "print(1)",
                      "status": "completed"
                    }
                  ],
                  "usage": {"input_tokens": 12, "output_tokens": 5}
                }
                """;

        Map<?, ?> result = objectMapper.readValue(mapper.mapNonStream(responsesBody), Map.class);
        Map<?, ?> choice = (Map<?, ?>) ((List<?>) result.get("choices")).get(0);
        assertEquals("tool_calls", choice.get("finish_reason"));

        Map<?, ?> toolCall = (Map<?, ?>) ((List<?>) ((Map<?, ?>) choice.get("message")).get("tool_calls")).get(0);
        assertEquals("call_python", toolCall.get("id"));
        assertEquals("custom", toolCall.get("type"));

        Map<?, ?> custom = (Map<?, ?>) toolCall.get("custom");
        assertEquals("run_python", custom.get("name"));
        assertEquals("print(1)", custom.get("input"));
    }

    @Test
    void shouldMapNonStreamTextAndFunctionCallResponse() throws Exception {
        String responsesBody = """
                {
                  "id": "resp_both",
                  "status": "completed",
                  "model": "gpt-5.3-codex",
                  "output": [
                    {
                      "type": "message",
                      "id": "msg_1",
                      "role": "assistant",
                      "content": [{"type": "output_text", "text": "我来查一下"}]
                    },
                    {
                      "type": "function_call",
                      "call_id": "call_1",
                      "name": "get_weather",
                      "arguments": "{}"
                    }
                  ],
                  "usage": {"input_tokens": 8, "output_tokens": 4}
                }
                """;

        Map<?, ?> result = objectMapper.readValue(mapper.mapNonStream(responsesBody), Map.class);
        Map<?, ?> choice = (Map<?, ?>) ((List<?>) result.get("choices")).get(0);
        assertEquals("tool_calls", choice.get("finish_reason"));
        // 同时有文本和工具调用时，工具调用优先决定 finish_reason
        Map<?, ?> message = (Map<?, ?>) choice.get("message");
        assertEquals("我来查一下", message.get("content"));
        assertNotNull(message.get("tool_calls"));
    }

    @Test
    void shouldMapNonStreamIncompleteStatus() throws Exception {
        String responsesBody = """
                {
                  "id": "resp_inc",
                  "status": "incomplete",
                  "model": "gpt-5.3-codex",
                  "output": [
                    {
                      "type": "message",
                      "id": "msg_inc",
                      "role": "assistant",
                      "content": [{"type": "output_text", "text": "截断的"}]
                    }
                  ],
                  "usage": {"input_tokens": 5, "output_tokens": 1000}
                }
                """;

        Map<?, ?> result = objectMapper.readValue(mapper.mapNonStream(responsesBody), Map.class);
        Map<?, ?> choice = (Map<?, ?>) ((List<?>) result.get("choices")).get(0);
        assertEquals("length", choice.get("finish_reason"));
    }

    @Test
    void shouldMapNonStreamCacheTokensInUsage() throws Exception {
        String responsesBody = """
                {
                  "id": "resp_cache",
                  "status": "completed",
                  "model": "gpt-5.3-codex",
                  "output": [
                    {"type": "message", "role": "assistant",
                     "content": [{"type": "output_text", "text": "hi"}]}
                  ],
                  "usage": {
                    "input_tokens": 100,
                    "output_tokens": 5,
                    "input_tokens_details": {"cached_tokens": 80, "cache_write_tokens": 20}
                  }
                }
                """;

        Map<?, ?> result = objectMapper.readValue(mapper.mapNonStream(responsesBody), Map.class);
        Map<?, ?> usage = (Map<?, ?>) result.get("usage");
        Map<?, ?> promptDetails = (Map<?, ?>) usage.get("prompt_tokens_details");
        assertNotNull(promptDetails);
        assertEquals(80, promptDetails.get("cached_tokens"));
        assertEquals(20, promptDetails.get("cache_write_tokens"));
    }

    // ─────────────────────────── 流式 ───────────────────────────

    @Test
    void shouldMapStreamTextDeltas() {
        List<SseEvent<String>> source = List.of(
                namedSse("response.created",
                        "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-5.3-codex\",\"created_at\":1700000001,\"output\":[]}}"),
                namedSse("response.output_item.added",
                        "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}}"),
                namedSse("response.content_part.added",
                        "{\"type\":\"response.content_part.added\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"\"}}"),
                namedSse("response.output_text.delta",
                        "{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"delta\":\"你好\"}"),
                namedSse("response.output_text.delta",
                        "{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"delta\":\"！\"}"),
                namedSse("response.completed",
                        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-5.3-codex\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"你好！\"}]}],\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}}")
        );

        List<SseEvent<String>> chunks = BridgeStreamTestSupport.mapStream(
                mapper, flux(source)).collectList().block();

        assertNotNull(chunks);
        // 首个 chunk 含 role:assistant
        String firstData = chunks.get(0).data();
        assertTrue(firstData.contains("\"role\":\"assistant\""), "首个 chunk 应含 role");

        // 文本 delta chunks
        assertTrue(chunks.stream().anyMatch(c -> c.data() != null && c.data().contains("你好")),
                "应有包含'你好'的 delta chunk");
        assertTrue(chunks.stream().anyMatch(c -> c.data() != null && c.data().contains("！")),
                "应有包含'！'的 delta chunk");

        // 最后是 [DONE]
        SseEvent<String> last = chunks.get(chunks.size() - 1);
        assertEquals("[DONE]", last.data());

        // finish_reason = stop
        assertTrue(chunks.stream().anyMatch(c -> c.data() != null && c.data().contains("\"finish_reason\":\"stop\"")),
                "应有 finish_reason=stop 的 chunk");

        // usage 包含在最终 chunk
        assertTrue(chunks.stream().anyMatch(c -> c.data() != null && c.data().contains("\"prompt_tokens\":5")),
                "应有 prompt_tokens=5 的 chunk");
    }

    @Test
    void shouldMapStreamCacheTokensInUsage() throws Exception {
        List<SseEvent<String>> source = List.of(
                namedSse("response.created",
                        "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_stream_cache\",\"model\":\"gpt-5.3-codex\",\"output\":[]}}"),
                namedSse("response.completed",
                        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_stream_cache\",\"model\":\"gpt-5.3-codex\",\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":100,\"output_tokens\":5,\"input_tokens_details\":{\"cached_tokens\":80,\"cache_write_tokens\":20}}}}")
        );

        JsonNode usage = findStreamUsage(BridgeStreamTestSupport.mapStream(
                mapper, flux(source)).collectList().block());

        assertEquals(100, usage.path("prompt_tokens").asInt());
        assertEquals(80, usage.path("prompt_tokens_details").path("cached_tokens").asInt());
        assertEquals(20, usage.path("prompt_tokens_details").path("cache_write_tokens").asInt());
    }

    @Test
    void shouldPreserveMissingStreamCacheTokensAndApplyExplicitZero() throws Exception {
        List<SseEvent<String>> source = List.of(
                namedSse("response.created",
                        "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_stream_cache_zero\",\"model\":\"gpt-5.3-codex\",\"output\":[]}}"),
                namedSse("response.in_progress",
                        "{\"type\":\"response.in_progress\",\"response\":{\"id\":\"resp_stream_cache_zero\",\"usage\":{\"input_tokens_details\":{\"cached_tokens\":80,\"cache_write_tokens\":20}}}}"),
                namedSse("response.completed",
                        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_stream_cache_zero\",\"model\":\"gpt-5.3-codex\",\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":100,\"output_tokens\":5,\"input_tokens_details\":{\"cache_write_tokens\":0}}}}")
        );

        JsonNode usage = findStreamUsage(BridgeStreamTestSupport.mapStream(
                mapper, flux(source)).collectList().block());

        assertEquals(80, usage.path("prompt_tokens_details").path("cached_tokens").asInt());
        assertEquals(0, usage.path("prompt_tokens_details").path("cache_write_tokens").asInt());
    }

    @Test
    void shouldMapStreamFunctionCallArguments() {
        List<SseEvent<String>> source = List.of(
                namedSse("response.created",
                        "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_2\",\"model\":\"gpt-5.3-codex\",\"output\":[]}}"),
                namedSse("response.output_item.added",
                        "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_abc\",\"name\":\"get_weather\",\"arguments\":\"\"}}"),
                namedSse("response.function_call_arguments.delta",
                        "{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"output_index\":0,\"delta\":\"{\\\"city\\\":\"}"),
                namedSse("response.function_call_arguments.delta",
                        "{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"output_index\":0,\"delta\":\"\\\"Hangzhou\\\"}\"}"),
                namedSse("response.completed",
                        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_2\",\"status\":\"completed\",\"model\":\"gpt-5.3-codex\",\"output\":[{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_abc\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Hangzhou\\\"}\"}],\"usage\":{\"input_tokens\":8,\"output_tokens\":4}}}")
        );

        List<SseEvent<String>> chunks = BridgeStreamTestSupport.mapStream(
                mapper, flux(source)).collectList().block();

        assertNotNull(chunks);

        // 应有 tool_calls 开始 chunk（含 name）
        assertTrue(chunks.stream().anyMatch(c -> c.data() != null && c.data().contains("\"name\":\"get_weather\"")),
                "应有 get_weather 工具调用开始 chunk");

        // 应有 arguments delta chunks
        assertTrue(chunks.stream().anyMatch(c -> c.data() != null && c.data().contains("\"arguments\":")),
                "应有 arguments delta chunk");

        // finish_reason = tool_calls
        assertTrue(chunks.stream().anyMatch(c -> c.data() != null && c.data().contains("\"finish_reason\":\"tool_calls\"")),
                "应有 finish_reason=tool_calls");

        // 最后是 [DONE]
        assertEquals("[DONE]", chunks.get(chunks.size() - 1).data());
    }

    @Test
    void shouldEmitFinalFunctionArgumentsWhenNoArgumentDeltasArrive() {
        List<SseEvent<String>> source = List.of(
                namedSse("response.created",
                        "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_done_only\",\"model\":\"gpt-5.3-codex\",\"output\":[]}}"),
                namedSse("response.output_item.added",
                        "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"fc_done_only\",\"type\":\"function_call\",\"call_id\":\"call_done_only\",\"name\":\"get_weather\",\"arguments\":\"\"}}"),
                namedSse("response.function_call_arguments.done",
                        "{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_done_only\",\"output_index\":0,\"arguments\":\"{\\\"city\\\":\\\"Hangzhou\\\"}\"}"),
                namedSse("response.completed",
                        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_done_only\",\"status\":\"completed\",\"model\":\"gpt-5.3-codex\",\"output\":[{\"type\":\"function_call\",\"id\":\"fc_done_only\",\"call_id\":\"call_done_only\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Hangzhou\\\"}\"}],\"usage\":{\"input_tokens\":8,\"output_tokens\":4}}}")
        );

        List<SseEvent<String>> chunks = BridgeStreamTestSupport.mapStream(
                mapper, flux(source)).collectList().block();

        assertNotNull(chunks);
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.data() != null
                        && chunk.data().contains("\"arguments\":\"{\\\"city\\\":\\\"Hangzhou\\\"}\"")),
                "无 arguments delta 时应使用 done 事件中的完整参数");
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.data() != null
                        && chunk.data().contains("\"finish_reason\":\"tool_calls\"")),
                "工具调用结束应返回 finish_reason=tool_calls");
    }

    @Test
    void shouldMapStreamCustomToolCallInput() {
        List<SseEvent<String>> source = List.of(
                namedSse("response.created",
                        "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_custom_stream\",\"model\":\"gpt-5.3-codex\",\"output\":[]}}"),
                namedSse("response.output_item.added",
                        "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"ctc_1\",\"type\":\"custom_tool_call\",\"call_id\":\"call_python\",\"name\":\"run_python\",\"input\":\"\"}}"),
                namedSse("response.custom_tool_call_input.delta",
                        "{\"type\":\"response.custom_tool_call_input.delta\",\"item_id\":\"ctc_1\",\"output_index\":0,\"delta\":\"print(1)\"}"),
                namedSse("response.completed",
                        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_custom_stream\",\"status\":\"completed\",\"model\":\"gpt-5.3-codex\",\"output\":[{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_python\",\"name\":\"run_python\",\"input\":\"print(1)\"}],\"usage\":{\"input_tokens\":8,\"output_tokens\":4}}}")
        );

        List<SseEvent<String>> chunks = BridgeStreamTestSupport.mapStream(
                mapper, flux(source)).collectList().block();

        assertNotNull(chunks);
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.data() != null
                        && chunk.data().contains("\"type\":\"custom\"")
                        && chunk.data().contains("\"name\":\"run_python\"")),
                "应输出自定义工具调用开始 chunk");
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.data() != null
                        && chunk.data().contains("\"input\":\"print(1)\"")),
                "应输出自定义工具输入 delta");
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.data() != null
                        && chunk.data().contains("\"finish_reason\":\"tool_calls\"")),
                "自定义工具调用结束应返回 finish_reason=tool_calls");
    }

    @Test
    void shouldMapStreamIncompleteStatus() {
        List<SseEvent<String>> source = List.of(
                namedSse("response.created",
                        "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_3\",\"model\":\"gpt-5.3-codex\",\"output\":[]}}"),
                namedSse("response.output_text.delta",
                        "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"部分\"}"),
                namedSse("response.completed",
                        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_3\",\"status\":\"incomplete\",\"model\":\"gpt-5.3-codex\",\"output\":[],\"usage\":{\"input_tokens\":3,\"output_tokens\":500}}}")
        );

        List<SseEvent<String>> chunks = BridgeStreamTestSupport.mapStream(
                mapper, flux(source)).collectList().block();

        assertNotNull(chunks);
        assertTrue(chunks.stream().anyMatch(c -> c.data() != null && c.data().contains("\"finish_reason\":\"length\"")),
                "status=incomplete 应映射为 finish_reason=length");
    }

    @Test
    void shouldEmitFinalToolInputWhenResponsesStreamIsIncomplete() {
        List<SseEvent<String>> source = List.of(
                namedSse("response.created",
                        "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_incomplete_tool\",\"model\":\"gpt-5.3-codex\",\"output\":[]}}"),
                namedSse("response.output_item.added",
                        "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"fc_incomplete\",\"type\":\"function_call\",\"call_id\":\"call_incomplete\",\"name\":\"get_weather\",\"arguments\":\"\"}}"),
                namedSse("response.incomplete",
                        "{\"type\":\"response.incomplete\",\"response\":{\"id\":\"resp_incomplete_tool\",\"status\":\"incomplete\",\"model\":\"gpt-5.3-codex\",\"output\":[{\"type\":\"function_call\",\"id\":\"fc_incomplete\",\"call_id\":\"call_incomplete\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Hangzhou\\\"}\"}],\"usage\":{\"input_tokens\":8,\"output_tokens\":4}}}")
        );

        List<SseEvent<String>> chunks = BridgeStreamTestSupport.mapStream(
                mapper, flux(source)).collectList().block();

        assertNotNull(chunks);
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.data() != null
                        && chunk.data().contains("\"arguments\":\"{\\\"city\\\":\\\"Hangzhou\\\"}\"")),
                "incomplete 响应也应补发最终工具参数");
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.data() != null
                        && chunk.data().contains("\"finish_reason\":\"length\"")),
                "工具调用未完成时仍应返回 finish_reason=length");
    }

    @Test
    void shouldHandleStreamDataWithTypeFieldInsteadOfEventField() {
        // 有些实现可能不设置 SSE event 字段，而是依赖 data 中的 type 字段
        List<SseEvent<String>> source = List.of(
                // 无 event 字段，依赖 data 中的 type
                dataSse("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_4\",\"model\":\"gpt-5.3-codex\",\"output\":[]}}"),
                dataSse("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"hello\"}"),
                dataSse("{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_4\",\"status\":\"completed\",\"model\":\"gpt-5.3-codex\",\"output\":[],\"usage\":{\"input_tokens\":2,\"output_tokens\":1}}}")
        );

        List<SseEvent<String>> chunks = BridgeStreamTestSupport.mapStream(
                mapper, flux(source)).collectList().block();

        assertNotNull(chunks);
        assertTrue(chunks.stream().anyMatch(c -> c.data() != null && c.data().contains("hello")),
                "无 event 字段时也应处理 delta");
        assertEquals("[DONE]", chunks.get(chunks.size() - 1).data());
    }

    @Test
    void shouldRejectDoneSignalWithoutResponsesTerminal() {
        List<SseEvent<String>> source = List.of(
                namedSse("response.created",
                        "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_5\",\"model\":\"gpt-5.3-codex\",\"output\":[]}}"),
                dataSse("[DONE]")
        );

        assertThrows(io.github.yidasanqian.llm.error.ProtocolConversionException.class,
                () -> BridgeStreamTestSupport.mapStream(mapper, flux(source)).collectList().block());
    }

    @Test
    void shouldIgnoreDuplicateTerminalEvents() {
        List<SseEvent<String>> source = List.of(
                namedSse("response.created",
                        "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_6\",\"model\":\"gpt-5.3-codex\",\"created_at\":1778207403,\"output\":[]}}"),
                namedSse("response.completed",
                        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_6\",\"model\":\"gpt-5.3-codex\",\"status\":\"completed\",\"output\":[]}}"),
                namedSse("response.completed",
                        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_6\",\"model\":\"gpt-5.3-codex\",\"status\":\"failed\",\"output\":[]}}")
        );

        List<SseEvent<String>> chunks = BridgeStreamTestSupport.mapStream(
                mapper, flux(source)).collectList().block();

        assertNotNull(chunks);
        assertEquals(1, chunks.stream()
                .filter(chunk -> chunk.data() != null && chunk.data().contains("\"finish_reason\":\""))
                .count());
        assertTrue(chunks.stream()
                .anyMatch(chunk -> chunk.data() != null && chunk.data().contains("\"finish_reason\":\"stop\"")));
        assertFalse(chunks.stream()
                .anyMatch(chunk -> chunk.data() != null && chunk.data().contains("\"finish_reason\":\"content_filter\"")));
        assertEquals(1, chunks.stream().filter(chunk -> "[DONE]".equals(chunk.data())).count());
    }

    @Test
    void shouldLeaveResponseFailedForPipelineInterceptor() {
        List<SseEvent<String>> source = List.of(
                namedSse("response.created",
                        "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_7\",\"model\":\"gpt-5.3-codex\",\"created_at\":1778207403,\"status\":\"in_progress\",\"output\":[]}}"),
                dataSse("{\"type\":\"error\",\"error\":{\"type\":\"server_error\",\"code\":\"server_error\",\"message\":\"server error\"}}"),
                namedSse("response.failed",
                        "{\"type\":\"response.failed\",\"response\":{\"id\":\"resp_7\",\"model\":\"gpt-5.3-codex\",\"created_at\":1778207403,\"status\":\"failed\",\"output\":[]}}")
        );

        BridgeStreamSession session = mapper.newSession();
        List<SseEvent<String>> chunks = source.stream()
                .flatMap(event -> session.map(event).stream())
                .toList();

        assertNotNull(chunks);
        assertFalse(chunks.stream()
                .anyMatch(chunk -> chunk.data() != null && chunk.data().contains("\"finish_reason\":\"")));
        assertFalse(chunks.stream().anyMatch(chunk -> "[DONE]".equals(chunk.data())));
    }

    // ─────────────────────────── 辅助方法 ───────────────────────────

    /**
     * 带 event 字段的 SSE（模拟 Responses API 命名事件）
     */
    private SseEvent<String> namedSse(String event, String data) {
        return SseEvent.<String>builder().event(event).data(data).build();
    }

    /**
     * 仅有 data 字段（无 event）的 SSE
     */
    private SseEvent<String> dataSse(String data) {
        return SseEvent.<String>builder().data(data).build();
    }

    private JsonNode findStreamUsage(List<SseEvent<String>> chunks) throws Exception {
        return chunks.stream()
                .map(SseEvent::data)
                .filter(data -> data != null && !"[DONE]".equals(data))
                .map(data -> {
                    try {
                        return objectMapper.readTree(data);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .filter(chunk -> chunk.has("usage"))
                .findFirst()
                .orElseThrow()
                .path("usage");
    }

    private reactor.core.publisher.Flux<SseEvent<String>> flux(List<SseEvent<String>> events) {
        return reactor.core.publisher.Flux.fromIterable(events);
    }
}
