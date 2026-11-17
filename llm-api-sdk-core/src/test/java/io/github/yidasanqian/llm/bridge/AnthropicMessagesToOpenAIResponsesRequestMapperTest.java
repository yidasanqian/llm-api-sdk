package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.bridge.anthropic.AnthropicMessagesToOpenAIChatRequestMapper;
import io.github.yidasanqian.llm.bridge.anthropic.AnthropicMessagesToOpenAIResponsesRequestMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicMessagesToOpenAIResponsesRequestMapperTest {

    private final AnthropicMessagesToOpenAIResponsesRequestMapper mapper =
            new AnthropicMessagesToOpenAIResponsesRequestMapper(new AnthropicMessagesToOpenAIChatRequestMapper());

    @Test
    void shouldMapAnthropicMessagesToOpenAIResponsesItems() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "openai/gpt-5");
        request.put("system", "你是一个测试助手");
        request.put("max_tokens", 256);
        request.put("stream", true);
        request.put("thinking", Map.of("type", "enabled", "budget_tokens", 2048));
        request.put("top_k", 3);
        request.put("metadata", Map.of("user_id", "cc-user-1", "session_id", "sess-1"));
        request.put("stop_sequences", List.of("</answer>"));
        request.put("tool_choice", "any");
        request.put("tools", List.of(Map.of(
                "name", "get_weather",
                "description", "Get weather",
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of("city", Map.of("type", "string"))
                )
        )));
        request.put("messages", List.of(
                Map.of(
                        "role", "assistant",
                        "content", List.of(
                                Map.of("type", "thinking", "thinking", "内部推理"),
                                Map.of("type", "text", "text", "先查天气"),
                                Map.of("type", "tool_use", "id", "toolu_1", "name", "get_weather", "input", Map.of("city", "Hangzhou"))
                        )
                ),
                Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "document", "title", "文档"),
                                Map.of("type", "tool_result", "tool_use_id", "toolu_1", "content", "晴天"),
                                Map.of("type", "text", "text", "顺便总结一下"),
                                Map.of("type", "image", "source", Map.of(
                                        "type", "url",
                                        "url", "https://example.com/image.png"
                                ))
                        )
                )
        ));

        Map<String, Object> result = mapper.map(request, true);

        assertEquals("你是一个测试助手", result.get("instructions"));
        assertEquals("required", result.get("tool_choice"));
        assertEquals(List.of("</answer>"), result.get("stop"));
        assertEquals(256, result.get("max_output_tokens"));
        assertEquals(Map.of("user_id", "cc-user-1", "session_id", "sess-1"), result.get("metadata"));
        assertFalse(result.containsKey("model"));
        assertFalse(result.containsKey("messages"));
        assertFalse(result.containsKey("thinking"));
        assertFalse(result.containsKey("top_k"));
        assertFalse(result.containsKey("stream_options"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) result.get("input");
        assertEquals(4, input.size());
        assertEquals("assistant", input.get(0).get("role"));
        assertEquals("先查天气", input.get(0).get("content"));
        assertEquals("function_call", input.get(1).get("type"));
        assertEquals("toolu_1", input.get(1).get("call_id"));
        assertEquals("function_call_output", input.get(2).get("type"));
        assertEquals("toolu_1", input.get(2).get("call_id"));
        assertEquals("user", input.get(3).get("role"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> userContent = (List<Map<String, Object>>) input.get(3).get("content");
        assertEquals("input_text", userContent.get(0).get("type"));
        assertEquals("input_image", userContent.get(1).get("type"));
        assertInstanceOf(String.class, userContent.get(1).get("image_url"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertEquals("function", tools.get(0).get("type"));
        assertEquals("get_weather", tools.get(0).get("name"));
        assertTrue(result.containsKey("stream"));
    }

    @Test
    void shouldDropOutputConfigAndContextManagementInResponsesMapper() {
        Map<String, Object> schema = Map.of("type", "object");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("output_config", Map.of(
                "effort", "medium",
                "format", Map.of("type", "json_schema", "schema", schema)
        ));
        request.put("context_management", Map.of("edits", List.of()));

        Map<String, Object> result = mapper.map(request, false);

        assertFalse(result.containsKey("output_config"));
        assertFalse(result.containsKey("context_management"));
        // effort is extracted to reasoning_effort in the intermediate Chat mapper result
        // and then flows into the Responses request
        assertEquals("medium", result.get("reasoning_effort"));
        @SuppressWarnings("unchecked")
        Map<String, Object> responseFormat = (Map<String, Object>) result.get("response_format");
        assertNotNull(responseFormat);
        assertEquals("json_schema", responseFormat.get("type"));
    }

    @Test
    void shouldDropAnthropicCacheControlInResponsesMapper() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        // Anthropic 缓存断点，Responses 侧不识别；Responses 走隐式缓存
        request.put("cache_control", Map.of("type", "ephemeral"));
        request.put("prompt_cache_key", "conversation-42");

        Map<String, Object> result = mapper.map(request, false);

        assertFalse(result.containsKey("cache_control"));
        // Responses 原生支持 prompt_cache_key，客户端给出的值应保留
        assertEquals("conversation-42", result.get("prompt_cache_key"));
    }

    @Test
    void shouldPreserveDisabledParallelToolUseInResponsesMapper() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("tools", List.of(Map.of(
                "name", "get_weather",
                "input_schema", Map.of("type", "object")
        )));
        request.put("tool_choice", Map.of(
                "type", "auto",
                "disable_parallel_tool_use", true
        ));

        Map<String, Object> result = mapper.map(request, false);

        assertEquals(false, result.get("parallel_tool_calls"));
        assertEquals("auto", result.get("tool_choice"));
    }
}
