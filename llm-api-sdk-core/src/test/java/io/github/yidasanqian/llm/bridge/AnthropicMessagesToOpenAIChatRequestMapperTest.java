package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.bridge.anthropic.AnthropicMessagesToOpenAIChatRequestMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicMessagesToOpenAIChatRequestMapperTest {

    private final AnthropicMessagesToOpenAIChatRequestMapper mapper = new AnthropicMessagesToOpenAIChatRequestMapper();

    @Test
    void shouldMapAnthropicMessagesToOpenAIChatCompletions() {
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
                                Map.of("type", "server_tool_use", "name", "internal_search"),
                                Map.of("type", "text", "text", "先查天气"),
                                Map.of("type", "redacted_thinking", "data", "redacted"),
                                Map.of("type", "mcp_tool_use", "name", "mcp_tool"),
                                Map.of("type", "tool_use", "id", "toolu_1", "name", "get_weather", "input", Map.of("city", "Hangzhou"))
                        )
                ),
                Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "document", "title", "文档"),
                                Map.of("type", "tool_result", "tool_use_id", "toolu_1", "content", "晴天"),
                                Map.of("type", "text", "text", "顺便总结一下"),
                                Map.of("type", "web_search_tool_result", "content", "搜索结果"),
                                Map.of("type", "code_execution_tool_result", "content", "执行结果"),
                                Map.of("type", "container_upload", "file_id", "file_1"),
                                Map.of("type", "image", "source", Map.of(
                                        "type", "url",
                                        "url", "https://example.com/image.png"
                                ))
                        )
                )
        ));

        Map<String, Object> result = mapper.map(request, true);

        assertEquals("required", result.get("tool_choice"));
        assertEquals(List.of("</answer>"), result.get("stop"));
        assertEquals(Map.of("user_id", "cc-user-1", "session_id", "sess-1"), result.get("metadata"));
        assertTrue(result.containsKey("stream_options"));
        assertFalse(result.containsKey("model"));
        assertFalse(result.containsKey("thinking"));
        assertFalse(result.containsKey("top_k"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals(4, messages.size());
        assertEquals("system", messages.get(0).get("role"));
        assertEquals("assistant", messages.get(1).get("role"));
        assertEquals("tool", messages.get(2).get("role"));
        assertEquals("user", messages.get(3).get("role"));
        assertEquals("先查天气", messages.get(1).get("content"));

        @SuppressWarnings("unchecked")
        Map<String, Object> assistantToolCall = ((List<Map<String, Object>>) messages.get(1).get("tool_calls")).get(0);
        assertEquals("toolu_1", assistantToolCall.get("id"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> userContent = (List<Map<String, Object>>) messages.get(3).get("content");
        assertEquals("text", userContent.get(0).get("type"));
        assertEquals("image_url", userContent.get(1).get("type"));
        assertInstanceOf(Map.class, userContent.get(1).get("image_url"));
    }

    @Test
    void shouldExtractOutputConfigEffortAsReasoningEffort() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("output_config", Map.of("effort", "medium"));

        Map<String, Object> result = mapper.map(request, false);

        assertEquals("medium", result.get("reasoning_effort"));
        assertFalse(result.containsKey("output_config"));
    }

    @Test
    void shouldExtractOutputConfigFormatAsResponseFormat() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("title", Map.of("type", "string")));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("output_config", Map.of(
                "effort", "medium",
                "format", Map.of("type", "json_schema", "schema", schema)
        ));

        Map<String, Object> result = mapper.map(request, false);

        assertFalse(result.containsKey("output_config"));
        @SuppressWarnings("unchecked")
        Map<String, Object> responseFormat = (Map<String, Object>) result.get("response_format");
        assertNotNull(responseFormat);
        assertEquals("json_schema", responseFormat.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> jsonSchema = (Map<String, Object>) responseFormat.get("json_schema");
        assertEquals("response", jsonSchema.get("name"));
        assertEquals(true, jsonSchema.get("strict"));
        assertEquals(schema, jsonSchema.get("schema"));
    }

    @Test
    void shouldRemoveContextManagement() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("context_management", Map.of("edits", List.of(Map.of("keep", "all", "type", "clear_thinking_20251015"))));

        Map<String, Object> result = mapper.map(request, false);

        assertFalse(result.containsKey("context_management"));
    }

    @Test
    void shouldMapAnthropicDisabledParallelToolUseToOpenAI() {
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

        assertEquals("auto", result.get("tool_choice"));
        assertEquals(false, result.get("parallel_tool_calls"));
    }

    @Test
    void shouldLeaveParallelToolCallsUnsetWhenAnthropicAllowsThem() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("tools", List.of(Map.of(
                "name", "get_weather",
                "input_schema", Map.of("type", "object")
        )));
        request.put("tool_choice", Map.of("type", "auto"));

        Map<String, Object> result = mapper.map(request, false);

        assertFalse(result.containsKey("parallel_tool_calls"));
    }

    @Test
    void shouldHoistSystemMessagesBeforeConversationMessages() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "user", "content", "hi"),
                Map.of("role", "system", "content", "always answer briefly"),
                Map.of("role", "assistant", "content", "ok")
        ));

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals("system", messages.get(0).get("role"));
        assertEquals("user", messages.get(1).get("role"));
        assertEquals("assistant", messages.get(2).get("role"));
    }

    @Test
    void shouldDropTopLevelCacheControlUnsupportedByOpenAI() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        // Anthropic 顶层自动缓存字段，OpenAI 不认识该参数，透传会 400
        request.put("cache_control", Map.of("type", "ephemeral"));

        Map<String, Object> result = mapper.map(request, false);

        assertFalse(result.containsKey("cache_control"));
        // OpenAI 侧隐式缓存默认生效，无需网关补声明字段
        assertFalse(result.containsKey("prompt_cache_key"));
    }

    @Test
    void shouldPassThroughClientProvidedPromptCacheKey() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        // 客户端直接使用 OpenAI 原生字段时原样透传
        request.put("prompt_cache_key", "conversation-42");

        Map<String, Object> result = mapper.map(request, false);

        assertEquals("conversation-42", result.get("prompt_cache_key"));
    }

    @Test
    void shouldNotInjectCacheFieldsWithoutDeclaration() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));

        Map<String, Object> result = mapper.map(request, false);

        assertFalse(result.containsKey("prompt_cache_key"));
        assertFalse(result.containsKey("cache_control"));
    }
}
