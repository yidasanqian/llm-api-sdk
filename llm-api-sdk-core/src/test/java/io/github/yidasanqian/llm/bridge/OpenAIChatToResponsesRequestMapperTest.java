package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.bridge.openai.OpenAIChatToResponsesRequestMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.yidasanqian.llm.bridge.FunctionToolSchemaFixtures.taskCreateFunction;

class OpenAIChatToResponsesRequestMapperTest {

    private final OpenAIChatToResponsesRequestMapper mapper = new OpenAIChatToResponsesRequestMapper();

    // ─────────────────────────── 基础会话 ───────────────────────────

    @Test
    void shouldMapBasicTextConversation() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "gpt-5.3-codex");
        request.put("messages", List.of(
                Map.of("role", "system", "content", "你是一个助手"),
                Map.of("role", "user", "content", "你好"),
                Map.of("role", "assistant", "content", "你好！有什么需要帮助的？"),
                Map.of("role", "user", "content", "介绍自己")
        ));

        Map<String, Object> result = mapper.map(request, false);

        assertEquals("你是一个助手", result.get("instructions"));
        assertEquals(false, result.get("stream"));
        assertFalse(result.containsKey("model"));
        assertFalse(result.containsKey("messages"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) result.get("input");
        assertEquals(3, input.size());
        assertEquals("user", input.get(0).get("role"));
        assertEquals("你好", input.get(0).get("content"));
        assertEquals("assistant", input.get(1).get("role"));
        assertEquals("你好！有什么需要帮助的？", input.get(1).get("content"));
        assertEquals("user", input.get(2).get("role"));
        assertEquals("介绍自己", input.get(2).get("content"));
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

        assertEquals("规则1\n\n规则2", result.get("instructions"));
    }

    @Test
    void shouldMergeDeveloperMessageIntoInstructions() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "system", "content", "系统规则"),
                Map.of("role", "developer", "content", "只允许调用查询工具"),
                Map.of("role", "user", "content", "hi")
        ));

        Map<String, Object> result = mapper.map(request, false);

        assertEquals("系统规则\n\n只允许调用查询工具", result.get("instructions"));
    }

    @Test
    void shouldHandleNoSystemMessage() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "user", "content", "hello")
        ));

        Map<String, Object> result = mapper.map(request, false);

        assertFalse(result.containsKey("instructions"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) result.get("input");
        assertEquals(1, input.size());
    }

    // ─────────────────────────── stream 标记 ───────────────────────────

    @Test
    void shouldSetStreamFlag() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));

        Map<String, Object> streamResult = mapper.map(request, true);
        Map<String, Object> nonStreamResult = mapper.map(request, false);

        assertEquals(true, streamResult.get("stream"));
        assertEquals(false, nonStreamResult.get("stream"));
    }

    @Test
    void shouldDisableResponseStorageForChatBridgeRequests() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("store", true);

        Map<String, Object> result = mapper.map(request, true);

        assertEquals(false, result.get("store"));
    }

    // ─────────────────────────── max_tokens 映射 ───────────────────────────

    @Test
    void shouldMapMaxCompletionTokensToMaxOutputTokens() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("max_completion_tokens", 512);

        Map<String, Object> result = mapper.map(request, false);

        assertEquals(512, result.get("max_output_tokens"));
        assertFalse(result.containsKey("max_completion_tokens"));
        assertFalse(result.containsKey("max_tokens"));
    }

    @Test
    void shouldMapMaxTokensToMaxOutputTokens() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("max_tokens", 256);

        Map<String, Object> result = mapper.map(request, false);

        assertEquals(256, result.get("max_output_tokens"));
    }

    @Test
    void shouldPreferMaxCompletionTokensOverMaxTokens() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("max_completion_tokens", 1024);
        request.put("max_tokens", 512);

        Map<String, Object> result = mapper.map(request, false);

        assertEquals(1024, result.get("max_output_tokens"));
    }

    // ─────────────────────────── tools 映射 ───────────────────────────

    @Test
    void shouldMapNestedToolsToFlatFormat() {
        Map<String, Object> params = Map.of("type", "object",
                "properties", Map.of("city", Map.of("type", "string")));
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "get_weather");
        function.put("description", "获取天气");
        function.put("parameters", params);
        function.put("strict", true);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "天气怎样？")));
        request.put("tools", List.of(Map.of("type", "function", "function", function)));

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertNotNull(tools);
        assertEquals(1, tools.size());

        Map<String, Object> tool = tools.get(0);
        assertEquals("function", tool.get("type"));
        assertEquals("get_weather", tool.get("name"));
        assertEquals("获取天气", tool.get("description"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitizedParams = (Map<String, Object>) tool.get("parameters");
        assertEquals("object", sanitizedParams.get("type"));
        assertEquals(false, sanitizedParams.get("additionalProperties"));
        assertEquals(List.of("city"), sanitizedParams.get("required"));
        assertNotNull(sanitizedParams.get("properties"));
        assertEquals(true, tool.get("strict"));
        assertFalse(tool.containsKey("function")); // 不含嵌套 function 对象
        assertFalse(result.containsKey("parallel_tool_calls"));
    }

    @Test
    void shouldIgnoreNonFunctionToolTypes() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("tools", List.of(
                Map.of("type", "web_search"),  // 不支持的类型，应忽略
                Map.of("type", "function", "function", Map.of("name", "fn1", "parameters", Map.of()))
        ));

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertEquals(1, tools.size());
        assertEquals("fn1", tools.get(0).get("name"));
    }

    @Test
    void shouldMapCustomToolDefinitionToResponsesFormat() {
        Map<String, Object> custom = Map.of(
                "name", "run_python",
                "description", "执行 Python 代码",
                "format", Map.of("type", "text")
        );
        Map<String, Object> request = Map.of(
                "messages", List.of(Map.of("role", "user", "content", "计算")),
                "tools", List.of(Map.of("type", "custom", "custom", custom))
        );

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertEquals(Map.of(
                "type", "custom",
                "name", "run_python",
                "description", "执行 Python 代码",
                "format", Map.of("type", "text")
        ), tools.get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSanitizeFunctionToolSchemasForResponsesStrictMode() {
        Map<String, Object> messageItemProperties = new LinkedHashMap<>();
        messageItemProperties.put("role", Map.of("type", "string", "default", "user"));
        messageItemProperties.put("content", Map.of("type", "string", "format", ""));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("prompt", Map.of("type", "string", "description", "prompt"));
        properties.put("size", Map.of("type", "string", "default", "auto", "format", ""));
        properties.put("messages", Map.of(
                "type", "array",
                "items", Map.of(
                        "type", "object",
                        "properties", messageItemProperties
                )
        ));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("required", List.of("prompt"));
        params.put("properties", properties);
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "azs_image_gen");
        function.put("parameters", params);
        function.put("strict", true);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "画图")));
        request.put("tools", List.of(Map.of("type", "function", "function", function)));

        Map<String, Object> result = mapper.map(request, true);

        assertFalse(result.containsKey("parallel_tool_calls"));
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        Map<String, Object> tool = tools.get(0);
        assertEquals(true, tool.get("strict"));

        Map<String, Object> sanitizedParams = (Map<String, Object>) tool.get("parameters");
        assertEquals(false, sanitizedParams.get("additionalProperties"));
        assertEquals(List.of("prompt", "size", "messages"), sanitizedParams.get("required"));

        Map<String, Object> sanitizedProperties = (Map<String, Object>) sanitizedParams.get("properties");
        Map<String, Object> size = (Map<String, Object>) sanitizedProperties.get("size");
        assertFalse(size.containsKey("default"));
        assertFalse(size.containsKey("format"));

        Map<String, Object> messages = (Map<String, Object>) sanitizedProperties.get("messages");
        Map<String, Object> items = (Map<String, Object>) messages.get("items");
        assertEquals(false, items.get("additionalProperties"));
        assertEquals(List.of("role", "content"), items.get("required"));
        Map<String, Object> itemProperties = (Map<String, Object>) items.get("properties");
        assertFalse(((Map<String, Object>) itemProperties.get("role")).containsKey("default"));
        assertFalse(((Map<String, Object>) itemProperties.get("content")).containsKey("format"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldPreserveNonStrictTaskCreateSchemaWhenStrictIsExplicitOrOmitted(boolean explicit) {
        Map<String, Object> function = taskCreateFunction();
        if (!explicit) {
            function.remove("strict");
        }
        Map<String, Object> original = new LinkedHashMap<>(function);
        Map<String, Object> request = Map.of(
                "messages", List.of(Map.of("role", "user", "content", "create a task")),
                "tools", List.of(Map.of("type", "function", "function", function)));

        Map<String, Object> result = mapper.map(request, false);

        Map<?, ?> mappedTool = (Map<?, ?>) ((List<?>) result.get("tools")).get(0);
        assertEquals(false, mappedTool.get("strict"));
        assertEquals(function.get("parameters"), mappedTool.get("parameters"));
        assertEquals(original, function);
    }

    // ─────────────────────────── tool_choice 映射 ───────────────────────────

    @Test
    void shouldPassThroughStringToolChoice() {
        for (String choice : List.of("auto", "required", "none")) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
            request.put("tool_choice", choice);

            Map<String, Object> result = mapper.map(request, false);
            assertEquals(choice, result.get("tool_choice"), "failed for: " + choice);
        }
    }

    @Test
    void shouldMapFunctionToolChoiceToFlatFormat() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("tool_choice", Map.of(
                "type", "function",
                "function", Map.of("name", "get_weather")
        ));

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        Map<String, Object> toolChoice = (Map<String, Object>) result.get("tool_choice");
        assertEquals("function", toolChoice.get("type"));
        assertEquals("get_weather", toolChoice.get("name"));
        assertFalse(toolChoice.containsKey("function")); // 扁平化，不含嵌套 function
    }

    @Test
    void shouldPreserveExplicitParallelToolCalls() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("tools", List.of(Map.of("type", "function",
                "function", Map.of("name", "get_weather"))));
        request.put("parallel_tool_calls", true);

        Map<String, Object> result = mapper.map(request, false);

        assertEquals(true, result.get("parallel_tool_calls"));
    }

    @Test
    void shouldPreserveExplicitlyDisabledParallelToolCalls() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("tools", List.of(Map.of("type", "function",
                "function", Map.of("name", "get_weather"))));
        request.put("parallel_tool_calls", false);

        Map<String, Object> result = mapper.map(request, false);

        assertEquals(false, result.get("parallel_tool_calls"));
    }

    @Test
    void shouldMapAllowedFunctionToolsToResponsesFormat() {
        Map<String, Object> allowedTools = Map.of(
                "mode", "auto",
                "tools", List.of(
                        Map.of("type", "function", "function", Map.of("name", "get_weather")),
                        Map.of("type", "function", "function", Map.of("name", "get_time"))
                )
        );
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("tool_choice", Map.of(
                "type", "allowed_tools",
                "allowed_tools", allowedTools
        ));

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        Map<String, Object> toolChoice = (Map<String, Object>) result.get("tool_choice");
        assertEquals("allowed_tools", toolChoice.get("type"));
        assertEquals("auto", toolChoice.get("mode"));
        assertEquals(List.of(
                Map.of("type", "function", "name", "get_weather"),
                Map.of("type", "function", "name", "get_time")
        ), toolChoice.get("tools"));
        assertFalse(toolChoice.containsKey("allowed_tools"));
    }

    @Test
    void shouldMapAllowedCustomToolsToResponsesFormat() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("tool_choice", Map.of(
                "type", "allowed_tools",
                "allowed_tools", Map.of(
                        "mode", "required",
                        "tools", List.of(Map.of("type", "custom",
                                "custom", Map.of("name", "run_python")))
                )
        ));

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        Map<String, Object> toolChoice = (Map<String, Object>) result.get("tool_choice");
        assertEquals("required", toolChoice.get("mode"));
        assertEquals(List.of(Map.of("type", "custom", "name", "run_python")), toolChoice.get("tools"));
    }

    // ─────────────────────────── function_call 和 function_call_output ───────────────────────────

    @Test
    void shouldMapAssistantToolCallsToFunctionCallItems() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "get_weather");
        function.put("arguments", "{\"city\":\"Hangzhou\"}");

        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", "call_abc123");
        toolCall.put("type", "function");
        toolCall.put("function", function);

        Map<String, Object> assistantMsg = new LinkedHashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("tool_calls", List.of(toolCall));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "user", "content", "查天气"),
                assistantMsg
        ));

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) result.get("input");
        assertEquals(2, input.size());

        Map<String, Object> fcItem = input.get(1);
        assertEquals("function_call", fcItem.get("type"));
        assertEquals("call_abc123", fcItem.get("call_id"));
        assertEquals("get_weather", fcItem.get("name"));
        assertEquals("{\"city\":\"Hangzhou\"}", fcItem.get("arguments"));
        assertEquals("completed", fcItem.get("status"));
    }

    @Test
    void shouldMapToolMessageToFunctionCallOutput() {
        Map<String, Object> toolMsg = new LinkedHashMap<>();
        toolMsg.put("role", "tool");
        toolMsg.put("tool_call_id", "call_abc123");
        toolMsg.put("content", "{\"temp\":\"25°C\"}");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "user", "content", "查天气"),
                toolMsg
        ));

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) result.get("input");
        Map<String, Object> fcoItem = input.get(1);
        assertEquals("function_call_output", fcoItem.get("type"));
        assertEquals("call_abc123", fcoItem.get("call_id"));
        assertEquals("{\"temp\":\"25°C\"}", fcoItem.get("output"));
    }

    @Test
    void shouldMapTextPartToolOutputToResponsesContentArray() {
        Map<String, Object> toolMsg = new LinkedHashMap<>();
        toolMsg.put("role", "tool");
        toolMsg.put("tool_call_id", "call_abc123");
        toolMsg.put("content", List.of(
                Map.of("type", "text", "text", "第一段"),
                Map.of("type", "text", "text", "第二段")
        ));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "user", "content", "查天气"),
                toolMsg
        ));

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) result.get("input");
        Map<String, Object> fcoItem = input.get(1);
        assertEquals(List.of(
                Map.of("type", "input_text", "text", "第一段"),
                Map.of("type", "input_text", "text", "第二段")
        ), fcoItem.get("output"));
    }

    @Test
    void shouldMapCustomToolCallHistoryAndOutput() {
        Map<String, Object> customCall = Map.of(
                "id", "call_python",
                "type", "custom",
                "custom", Map.of("name", "run_python", "input", "print(1)")
        );
        Map<String, Object> assistantMsg = Map.of(
                "role", "assistant",
                "tool_calls", List.of(customCall)
        );
        Map<String, Object> toolMsg = Map.of(
                "role", "tool",
                "tool_call_id", "call_python",
                "content", "1"
        );
        Map<String, Object> request = Map.of(
                "messages", List.of(
                        Map.of("role", "user", "content", "执行代码"),
                        assistantMsg,
                        toolMsg
                )
        );

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) result.get("input");
        assertEquals(Map.of(
                "type", "custom_tool_call",
                "call_id", "call_python",
                "name", "run_python",
                "input", "print(1)"
        ), input.get(1));
        assertEquals(Map.of(
                "type", "custom_tool_call_output",
                "call_id", "call_python",
                "output", "1"
        ), input.get(2));
    }

    @Test
    void shouldHandleAssistantTextAndToolCallsTogether() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "fn");
        function.put("arguments", "{}");
        Map<String, Object> toolCall = Map.of("id", "c1", "type", "function", "function", function);

        Map<String, Object> assistantMsg = new LinkedHashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", "我来帮你查");
        assistantMsg.put("tool_calls", List.of(toolCall));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "user", "content", "hi"),
                assistantMsg
        ));

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) result.get("input");
        // assistant 文本消息 + function_call item
        assertEquals(3, input.size());
        assertEquals("user", input.get(0).get("role"));
        assertEquals("assistant", input.get(1).get("role"));
        assertEquals("function_call", input.get(2).get("type"));
    }

    // ─────────────────────────── 多模态 user content ───────────────────────────

    @Test
    void shouldMapUserContentPartsToInputFormat() {
        Map<String, Object> textPart = Map.of("type", "text", "text", "描述图片");
        Map<String, Object> imagePart = Map.of("type", "image_url",
                "image_url", Map.of("url", "https://example.com/img.png", "detail", "high"));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "user", "content", List.of(textPart, imagePart))
        ));

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) result.get("input");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) input.get(0).get("content");
        assertEquals(2, content.size());

        assertEquals("input_text", content.get(0).get("type"));
        assertEquals("描述图片", content.get(0).get("text"));

        assertEquals("input_image", content.get(1).get("type"));
        assertEquals("https://example.com/img.png", content.get(1).get("image_url"));
        assertEquals("high", content.get(1).get("detail"));
    }

    // ─────────────────────────── response_format 映射 ───────────────────────────

    @Test
    void shouldMapResponseFormatToTextFormat() {
        Map<String, Object> responseFormat = Map.of("type", "json_object");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("response_format", responseFormat);

        Map<String, Object> result = mapper.map(request, false);

        assertFalse(result.containsKey("response_format"));
        @SuppressWarnings("unchecked")
        Map<String, Object> text = (Map<String, Object>) result.get("text");
        assertNotNull(text);
        assertEquals(responseFormat, text.get("format"));
    }

    // ─────────────────────────── 字段过滤和透传 ───────────────────────────

    @Test
    void shouldDropChatOnlyFields() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("stream_options", Map.of("include_usage", true));
        request.put("n", 1);
        request.put("logprobs", false);
        request.put("frequency_penalty", 0.5);
        request.put("presence_penalty", 0.5);

        Map<String, Object> result = mapper.map(request, false);

        assertFalse(result.containsKey("stream_options"));
        assertFalse(result.containsKey("n"));
        assertFalse(result.containsKey("logprobs"));
        assertFalse(result.containsKey("frequency_penalty"));
        assertFalse(result.containsKey("presence_penalty"));
    }

    @Test
    void shouldPassThroughCompatibleFields() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("temperature", 0.7);
        request.put("top_p", 0.9);

        Map<String, Object> result = mapper.map(request, false);

        assertEquals(0.7, result.get("temperature"));
        assertEquals(0.9, result.get("top_p"));
    }

    @Test
    void shouldPassThroughPromptCacheKeyToResponses() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        // Responses 与 Chat Completions 共用同名缓存字段，转换时不应丢弃
        request.put("prompt_cache_key", "conversation-42");

        Map<String, Object> result = mapper.map(request, false);

        assertEquals("conversation-42", result.get("prompt_cache_key"));
    }

    @Test
    void shouldNotInjectCacheFieldsWithoutClientDeclaration() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));

        Map<String, Object> result = mapper.map(request, false);

        // 两侧同为 OpenAI 协议族，隐式缓存默认生效，网关不额外注入
        assertFalse(result.containsKey("prompt_cache_key"));
        assertFalse(result.containsKey("cache_control"));
    }

    // ─────────────────────────── 空消息列表 ───────────────────────────

    @Test
    void shouldHandleNullOrNonListMessages() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", null);

        Map<String, Object> result = mapper.map(request, false);

        @SuppressWarnings("unchecked")
        List<?> input = (List<?>) result.get("input");
        assertTrue(input.isEmpty());
        assertFalse(result.containsKey("instructions"));
    }
}
