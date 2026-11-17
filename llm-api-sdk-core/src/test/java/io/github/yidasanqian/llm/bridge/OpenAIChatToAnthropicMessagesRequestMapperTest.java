package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.bridge.openai.OpenAIChatToAnthropicMessagesRequestMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenAIChatToAnthropicMessagesRequestMapperTest {

    private final OpenAIChatToAnthropicMessagesRequestMapper mapper = new OpenAIChatToAnthropicMessagesRequestMapper();

    @Test
    void shouldMapBasicConversation() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "claude-3-5-sonnet");
        request.put("messages", List.of(
                Map.of("role", "system", "content", "你是一个助手"),
                Map.of("role", "user", "content", "你好"),
                Map.of("role", "assistant", "content", "你好！有什么可以帮你的？"),
                Map.of("role", "user", "content", "介绍一下自己")
        ));

        Map<String, Object> result = mapper.map(request, false);

        assertEquals("你是一个助手", result.get("system"));
        assertFalse((Boolean) result.get("stream"));
        assertEquals(8192, result.get("max_tokens"));
        assertFalse(result.containsKey("model"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).get("role"));
        assertEquals("assistant", messages.get(1).get("role"));
        assertEquals("user", messages.get(2).get("role"));

        // user content should be content array
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstUserContent = (List<Map<String, Object>>) messages.get(0).get("content");
        assertEquals("text", firstUserContent.get(0).get("type"));
        assertEquals("你好", firstUserContent.get(0).get("text"));
    }

    @Test
    void shouldMergeMultipleSystemMessages() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "system", "content", "规则1"),
                Map.of("role", "system", "content", "规则2"),
                Map.of("role", "user", "content", "hi")
        ));

        Map<String, Object> result = mapper.map(request, false);

        assertEquals("规则1\n\n规则2", result.get("system"));
    }

    @Test
    void shouldMergeDeveloperMessageIntoSystemPrompt() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "system", "content", "系统规则"),
                Map.of("role", "developer", "content", "只允许调用查询工具"),
                Map.of("role", "user", "content", "hi")
        ));

        Map<String, Object> result = mapper.map(request, false);

        assertEquals("系统规则\n\n只允许调用查询工具", result.get("system"));
    }

    @Test
    void shouldMapToolCallFlowWithMergedToolResults() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "user", "content", "查天气"),
                Map.of("role", "assistant", "content", "好的",
                        "tool_calls", List.of(
                                Map.of("id", "call_1", "type", "function",
                                        "function", Map.of("name", "get_weather", "arguments", "{\"city\":\"Hangzhou\"}"))
                        )
                ),
                Map.of("role", "tool", "tool_call_id", "call_1", "content", "晴天，25°C"),
                Map.of("role", "tool", "tool_call_id", "call_2", "content", "多云，22°C")
        ));

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");

        // user, assistant, synthetic-user(2 tool_results merged)
        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).get("role"));
        assertEquals("assistant", messages.get(1).get("role"));

        // assistant has text + tool_use
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assistantContent = (List<Map<String, Object>>) messages.get(1).get("content");
        assertEquals("text", assistantContent.get(0).get("type"));
        assertEquals("好的", assistantContent.get(0).get("text"));
        assertEquals("tool_use", assistantContent.get(1).get("type"));
        assertEquals("call_1", assistantContent.get(1).get("id"));
        assertEquals("get_weather", assistantContent.get(1).get("name"));
        assertEquals(Map.of("city", "Hangzhou"), assistantContent.get(1).get("input"));

        // synthetic user message: both tool_results merged
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolResultContent = (List<Map<String, Object>>) messages.get(2).get("content");
        assertEquals(2, toolResultContent.size());
        assertEquals("tool_result", toolResultContent.get(0).get("type"));
        assertEquals("call_1", toolResultContent.get(0).get("tool_use_id"));
        assertEquals("晴天，25°C", toolResultContent.get(0).get("content"));
        assertEquals("tool_result", toolResultContent.get(1).get("type"));
        assertEquals("call_2", toolResultContent.get(1).get("tool_use_id"));
    }

    @Test
    void shouldMapToolResultsBeforeUserMessage() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "tool", "tool_call_id", "call_1", "content", "结果"),
                Map.of("role", "user", "content", "继续")
        ));

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        // tool_result should be prepended to user message's content
        assertEquals(1, messages.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(0).get("content");
        assertEquals("tool_result", content.get(0).get("type"));
        assertEquals("text", content.get(1).get("type"));
        assertEquals("继续", content.get(1).get("text"));
    }

    @Test
    void shouldMapImageUrlBlocksBase64AndHttp() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "user", "content", List.of(
                        Map.of("type", "image_url",
                                "image_url", Map.of("url", "data:image/jpeg;base64,/9j/ABC")),
                        Map.of("type", "image_url",
                                "image_url", Map.of("url", "https://example.com/photo.png")),
                        Map.of("type", "text", "text", "描述一下这两张图片")
                ))
        ));

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(0).get("content");

        assertEquals(3, content.size());

        // base64 image
        assertEquals("image", content.get(0).get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> base64Source = (Map<String, Object>) content.get(0).get("source");
        assertEquals("base64", base64Source.get("type"));
        assertEquals("image/jpeg", base64Source.get("media_type"));
        assertEquals("/9j/ABC", base64Source.get("data"));

        // http url image
        assertEquals("image", content.get(1).get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> urlSource = (Map<String, Object>) content.get(1).get("source");
        assertEquals("url", urlSource.get("type"));
        assertEquals("https://example.com/photo.png", urlSource.get("url"));

        // text
        assertEquals("text", content.get(2).get("type"));
    }

    @Test
    void shouldMapToolsAndToolChoices() {
        Map<String, Object> functionDef = new LinkedHashMap<>();
        functionDef.put("name", "get_weather");
        functionDef.put("description", "Get weather info");
        functionDef.put("parameters", Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "天气怎么样")));
        request.put("tools", List.of(Map.of("type", "function", "function", functionDef)));
        request.put("tool_choice", "required");

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertEquals(1, tools.size());
        assertEquals("get_weather", tools.get(0).get("name"));
        assertEquals("Get weather info", tools.get(0).get("description"));
        assertNotNull(tools.get(0).get("input_schema"));
        assertFalse(tools.get(0).containsKey("parameters"));

        @SuppressWarnings("unchecked")
        Map<String, Object> toolChoice = (Map<String, Object>) result.get("tool_choice");
        assertEquals("any", toolChoice.get("type"));
    }

    @Test
    void shouldDropToolsWhenToolChoiceIsNone() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("tools", List.of(Map.of("type", "function", "function", Map.of("name", "foo"))));
        request.put("tool_choice", "none");

        Map<String, Object> result = mapper.map(request, false);

        assertFalse(result.containsKey("tools"));
        assertFalse(result.containsKey("tool_choice"));
    }

    @Test
    void shouldMapSpecificFunctionToolChoice() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("tools", List.of(Map.of("type", "function",
                "function", Map.of("name", "get_weather"))));
        request.put("tool_choice", Map.of("type", "function", "function", Map.of("name", "get_weather")));

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        Map<String, Object> toolChoice = (Map<String, Object>) result.get("tool_choice");
        assertEquals("tool", toolChoice.get("type"));
        assertEquals("get_weather", toolChoice.get("name"));
    }

    @Test
    void shouldIgnoreCustomToolsWhenAnthropicHasNoEquivalent() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "运行代码")));
        request.put("tools", List.of(Map.of(
                "type", "custom",
                "custom", Map.of("name", "run_python", "description", "执行 Python")
        )));
        request.put("tool_choice", "required");

        Map<String, Object> result = mapper.map(request, false);

        assertFalse(result.containsKey("tools"));
        assertFalse(result.containsKey("tool_choice"));
    }

    @Test
    void shouldKeepFunctionToolsWhenMixedWithCustomTools() {
        Map<String, Object> customTool = Map.of(
                "type", "custom",
                "custom", Map.of("name", "run_python")
        );
        Map<String, Object> functionTool = Map.of(
                "type", "function",
                "function", Map.of("name", "get_weather")
        );
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "天气怎么样")));
        request.put("tools", List.of(customTool, functionTool));
        request.put("tool_choice", "required");

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertEquals(1, tools.size());
        assertEquals("get_weather", tools.get(0).get("name"));
        assertEquals(Map.of("type", "any"), result.get("tool_choice"));
    }

    @Test
    void shouldFilterToolsByAllowedToolsAndIgnoreUnsupportedEntries() {
        Map<String, Object> weatherTool = Map.of(
                "type", "function",
                "function", Map.of("name", "get_weather")
        );
        Map<String, Object> timeTool = Map.of(
                "type", "function",
                "function", Map.of("name", "get_time")
        );
        Map<String, Object> allowedTools = Map.of(
                "mode", "required",
                "tools", List.of(
                        Map.of("type", "function", "function", Map.of("name", "get_weather")),
                        Map.of("type", "custom", "custom", Map.of("name", "run_python"))
                )
        );
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "天气怎么样")));
        request.put("tools", List.of(weatherTool, timeTool));
        request.put("tool_choice", Map.of("type", "allowed_tools", "allowed_tools", allowedTools));

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertEquals(1, tools.size());
        assertEquals("get_weather", tools.get(0).get("name"));
        assertEquals(Map.of("type", "any"), result.get("tool_choice"));
    }

    @Test
    void shouldIgnoreCustomToolCallsAndResultsInHistory() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "user", "content", "查询并运行"),
                Map.of("role", "assistant", "content", "开始处理",
                        "tool_calls", List.of(
                                Map.of("id", "call_weather", "type", "function",
                                        "function", Map.of("name", "get_weather",
                                                "arguments", "{\"city\":\"Hangzhou\"}")),
                                Map.of("id", "call_python", "type", "custom",
                                        "custom", Map.of("name", "run_python", "input", "print(1)"))
                        )),
                Map.of("role", "tool", "tool_call_id", "call_weather", "content", "晴天"),
                Map.of("role", "tool", "tool_call_id", "call_python", "content", "1"),
                Map.of("role", "user", "content", "继续")
        ));

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals(3, messages.size());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assistantContent =
                (List<Map<String, Object>>) messages.get(1).get("content");
        assertEquals(2, assistantContent.size());
        assertEquals("text", assistantContent.get(0).get("type"));
        assertEquals("tool_use", assistantContent.get(1).get("type"));
        assertEquals("call_weather", assistantContent.get(1).get("id"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> userContent =
                (List<Map<String, Object>>) messages.get(2).get("content");
        assertEquals(2, userContent.size());
        assertEquals("tool_result", userContent.get(0).get("type"));
        assertEquals("call_weather", userContent.get(0).get("tool_use_id"));
        assertEquals("text", userContent.get(1).get("type"));

        String serialized = result.toString();
        assertFalse(serialized.contains("call_python"));
        assertFalse(serialized.contains("run_python"));
    }

    @Test
    void shouldMapDisabledParallelToolCallsToAnthropicToolChoice() {
        List<ToolChoiceCase> cases = List.of(
                new ToolChoiceCase(
                        null,
                        Map.of("type", "auto", "disable_parallel_tool_use", true)
                ),
                new ToolChoiceCase(
                        "required",
                        Map.of("type", "any", "disable_parallel_tool_use", true)
                ),
                new ToolChoiceCase(
                        Map.of("type", "function", "function", Map.of("name", "get_weather")),
                        Map.of("type", "tool", "name", "get_weather", "disable_parallel_tool_use", true)
                )
        );

        for (ToolChoiceCase choiceCase : cases) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
            request.put("tools", List.of(Map.of("type", "function",
                    "function", Map.of("name", "get_weather"))));
            request.put("parallel_tool_calls", false);
            if (choiceCase.source() != null) {
                request.put("tool_choice", choiceCase.source());
            }

            Map<String, Object> result = mapper.map(request, false);

            assertEquals(choiceCase.expected(), result.get("tool_choice"));
            assertFalse(result.containsKey("parallel_tool_calls"));
        }
    }

    @Test
    void shouldLeaveParallelToolCallsEnabledByDefault() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("tools", List.of(Map.of("type", "function",
                "function", Map.of("name", "get_weather"))));
        request.put("tool_choice", "required");

        Map<String, Object> result = mapper.map(request, false);

        assertEquals(Map.of("type", "any"), result.get("tool_choice"));
    }

    @Test
    void shouldMapStopStringToStopSequencesArray() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("stop", "</answer>");

        Map<String, Object> result = mapper.map(request, false);

        assertEquals(List.of("</answer>"), result.get("stop_sequences"));
        assertFalse(result.containsKey("stop"));
    }

    @Test
    void shouldPassThroughStopArrayAsStopSequences() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("stop", List.of("END", "STOP"));

        Map<String, Object> result = mapper.map(request, false);

        assertEquals(List.of("END", "STOP"), result.get("stop_sequences"));
    }

    @Test
    void shouldUseMaxCompletionTokensIfMaxTokensAbsent() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("max_completion_tokens", 1024);

        Map<String, Object> result = mapper.map(request, false);

        assertEquals(1024, result.get("max_tokens"));
    }

    @Test
    void shouldDefaultMaxTokensTo8192() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));

        Map<String, Object> result = mapper.map(request, false);

        assertEquals(8192, result.get("max_tokens"));
    }

    @Test
    void shouldDropIgnoredFieldsAndSetStreamFlag() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("model", "gpt-4o");
        request.put("stream_options", Map.of("include_usage", true));
        request.put("n", 2);
        request.put("seed", 42);
        request.put("user", "u-1");
        request.put("prompt_cache_key", "conversation-42");
        request.put("prompt_cache_retention", "24h");
        request.put("temperature", 0.7);

        Map<String, Object> result = mapper.map(request, true);

        assertTrue((Boolean) result.get("stream"));
        assertFalse(result.containsKey("model"));
        assertFalse(result.containsKey("stream_options"));
        assertFalse(result.containsKey("n"));
        assertFalse(result.containsKey("seed"));
        assertFalse(result.containsKey("user"));
        assertFalse(result.containsKey("prompt_cache_key"));
        assertFalse(result.containsKey("prompt_cache_retention"));
        // transparent pass-through
        assertEquals(0.7, result.get("temperature"));
    }

    @Test
    void shouldDropOutputConfigAndContextManagement() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("output_config", Map.of("effort", "medium", "format", Map.of("type", "json_schema")));
        request.put("context_management", Map.of("edits", List.of(Map.of("keep", "all", "type", "clear_thinking_20251015"))));

        Map<String, Object> result = mapper.map(request, false);

        assertFalse(result.containsKey("output_config"));
        assertFalse(result.containsKey("context_management"));
        assertFalse(result.containsKey("reasoning_effort"));
    }

    @Test
    void shouldMapPromptCacheKeyToTopLevelAutomaticCaching() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "system", "content", "长系统提示"),
                Map.of("role", "user", "content", "第一轮"),
                Map.of("role", "assistant", "content", "回复"),
                Map.of("role", "user", "content", "第二轮")
        ));
        request.put("prompt_cache_key", "conversation-42");

        Map<String, Object> result = mapper.map(request, false);

        // 顶层自动缓存字段，不做块级注入
        assertEquals(Map.of("type", "ephemeral"), result.get("cache_control"));
        assertEquals(0, countBlockLevelBreakpoints(result));
        // 声明字段自身不下发上游
        assertFalse(result.containsKey("prompt_cache_key"));
        // 消息结构与 system 形态不因缓存映射而改变
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals(3, messages.size());
        assertEquals("长系统提示", result.get("system"));
    }

    @Test
    void shouldNotMapCacheControlWithoutClientDeclaration() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "user", "content", "第一轮"),
                Map.of("role", "assistant", "content", "回复"),
                Map.of("role", "user", "content", "第二轮")
        ));

        Map<String, Object> result = mapper.map(request, false);

        assertFalse(result.containsKey("cache_control"));
        assertEquals(0, countBlockLevelBreakpoints(result));
    }

    @Test
    void shouldNotMapCacheControlWhenDeclaredPromptCacheKeyIsBlank() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("prompt_cache_key", "   ");

        Map<String, Object> result = mapper.map(request, false);

        assertFalse(result.containsKey("cache_control"));
    }

    @Test
    void shouldNotOverrideTopLevelCacheControlProvidedByClient() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("prompt_cache_key", "conversation-42");
        request.put("cache_control", Map.of("type", "ephemeral", "ttl", "1h"));

        Map<String, Object> result = mapper.map(request, false);

        // 客户端已显式给出顶层缓存配置时保持原值，不被覆盖
        assertEquals(Map.of("type", "ephemeral", "ttl", "1h"), result.get("cache_control"));
    }

    /**
     * 统计块级 cache_control 断点数量；顶层自动缓存不计入。
     */
    @SuppressWarnings("unchecked")
    private int countBlockLevelBreakpoints(Map<String, Object> result) {
        int count = 0;
        Object messagesObj = result.get("messages");
        if (messagesObj instanceof List<?> messages) {
            for (Object messageObj : messages) {
                if (!(messageObj instanceof Map<?, ?> message)) {
                    continue;
                }
                Object contentObj = message.get("content");
                if (contentObj instanceof List<?> blocks) {
                    for (Object blockObj : blocks) {
                        if (blockObj instanceof Map<?, ?> block && block.containsKey("cache_control")) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    private record ToolChoiceCase(Object source, Map<String, Object> expected) {
    }
}
