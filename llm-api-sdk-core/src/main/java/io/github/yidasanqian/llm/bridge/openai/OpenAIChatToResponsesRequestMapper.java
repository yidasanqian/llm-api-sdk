package io.github.yidasanqian.llm.bridge.openai;

import io.github.yidasanqian.llm.json.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 将 OpenAI Chat Completions 请求映射为 OpenAI Responses API 请求。
 *
 * <p>当后端 providerType 为 {@code OPENAI_RESPONSES} 时，用于将 /v1/chat/completions 请求
 * 转换为 /v1/responses 格式，再由 OpenAIResponsesRestClient 发送给上游。
 */
@Slf4j
public class OpenAIChatToResponsesRequestMapper {

    private static final String ROLE = "role";
    private static final String CONTENT = "content";
    private static final String TYPE = "type";
    private static final String TEXT = "text";
    private static final String SYSTEM = "system";
    private static final String DEVELOPER = "developer";
    private static final String USER = "user";
    private static final String ASSISTANT = "assistant";
    private static final String TOOL = "tool";
    private static final String TOOLS = "tools";
    private static final String TOOL_CHOICE = "tool_choice";
    private static final String STREAM = "stream";
    private static final String INPUT = "input";
    private static final String INSTRUCTIONS = "instructions";
    private static final String MAX_OUTPUT_TOKENS = "max_output_tokens";
    private static final String STORE = "store";
    private static final String PROPERTIES = "properties";
    private static final String REQUIRED = "required";
    private static final String ADDITIONAL_PROPERTIES = "additionalProperties";
    private static final String STRICT = "strict";
    private static final String FUNCTION = "function";
    private static final String CUSTOM = "custom";
    private static final String CUSTOM_TOOL_CALL = "custom_tool_call";
    private static final String CUSTOM_TOOL_CALL_OUTPUT = "custom_tool_call_output";

    // Chat Completions 专有字段，Responses API 不支持，需丢弃
    private static final Set<String> CHAT_ONLY_FIELDS = Set.of(
            "messages", TOOLS, TOOL_CHOICE, STREAM,
            "stream_options", "n", "logprobs", "top_logprobs", "logit_bias",
            "presence_penalty", "frequency_penalty", "seed", "user", "stop",
            "max_tokens", "max_completion_tokens", "response_format", "model", STORE
    );

    /**
     * 将 Chat Completions 请求映射为 Responses API 请求。
     *
     * @param chatRequest Chat Completions 请求体
     * @param stream      是否流式
     * @return Responses API 请求体
     */
    public Map<String, Object> map(Map<String, Object> chatRequest, boolean stream) {
        Map<String, Object> responsesRequest = new LinkedHashMap<>();

        // 1. messages -> instructions + input
        MappingResult result = mapMessages(chatRequest.get("messages"));
        if (!result.instructions().isBlank()) {
            responsesRequest.put(INSTRUCTIONS, result.instructions());
        }
        responsesRequest.put(INPUT, result.inputItems());

        // 2. stream
        responsesRequest.put(STREAM, stream);
        responsesRequest.put(STORE, false);

        // 3. max_tokens / max_completion_tokens -> max_output_tokens
        mapMaxOutputTokens(chatRequest, responsesRequest);

        // 4. tools (嵌套格式 -> 扁平格式)
        mapTools(chatRequest.get(TOOLS), responsesRequest);

        // 5. tool_choice
        mapToolChoice(chatRequest.get(TOOL_CHOICE), responsesRequest);

        // 6. response_format -> text.format
        mapResponseFormat(chatRequest.get("response_format"), responsesRequest);

        // 7. 透传其余兼容字段
        copyRemainingFields(chatRequest, responsesRequest);

        return responsesRequest;
    }

    private MappingResult mapMessages(Object messagesObject) {
        if (!(messagesObject instanceof List<?> rawMessages)) {
            return new MappingResult("", List.of());
        }

        List<String> instructionParts = new ArrayList<>();
        List<Map<String, Object>> inputItems = new ArrayList<>();
        // 累积待处理的 tool_calls（assistant 消息中可能含多个 tool_call）
        List<Map<String, Object>> pendingToolCalls = new ArrayList<>();
        Map<String, String> toolCallTypes = new LinkedHashMap<>();

        for (Object msgObj : rawMessages) {
            if (!(msgObj instanceof Map<?, ?> rawMsg)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) rawMsg;
            String role = valueAsString(message.get(ROLE));

            switch (role) {
                case SYSTEM, DEVELOPER -> {
                    flushPendingToolCalls(pendingToolCalls, inputItems);
                    collectInstruction(message.get(CONTENT), instructionParts);
                }
                case USER -> {
                    flushPendingToolCalls(pendingToolCalls, inputItems);
                    inputItems.add(mapUserMessage(message));
                }
                case ASSISTANT -> {
                    flushPendingToolCalls(pendingToolCalls, inputItems);
                    mapAssistantMessage(message, inputItems, pendingToolCalls, toolCallTypes);
                }
                case TOOL -> {
                    // tool 消息必须跟在对应的工具调用之后，先 flush
                    flushPendingToolCalls(pendingToolCalls, inputItems);
                    inputItems.add(mapToolMessage(message, toolCallTypes));
                }
                default -> log.warn("Ignoring unsupported message role '{}' in Chat-to-Responses mapping.", role);
            }
        }
        flushPendingToolCalls(pendingToolCalls, inputItems);

        return new MappingResult(String.join("\n\n", instructionParts), inputItems);
    }

    private void collectInstruction(Object contentObject, List<String> parts) {
        String text = extractTextContent(contentObject);
        if (!text.isBlank()) {
            parts.add(text);
        }
    }

    private Map<String, Object> mapUserMessage(Map<String, Object> message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put(ROLE, USER);
        Object content = message.get(CONTENT);
        if (content instanceof String text) {
            item.put(CONTENT, text);
        } else if (content instanceof List<?> parts) {
            item.put(CONTENT, mapUserContentParts(parts));
        }
        return item;
    }

    private List<Map<String, Object>> mapUserContentParts(List<?> parts) {
        List<Map<String, Object>> result = new ArrayList<>(parts.size());
        for (Object partObj : parts) {
            if (!(partObj instanceof Map<?, ?> rawPart)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> part = (Map<String, Object>) rawPart;
            String type = valueAsString(part.get(TYPE));
            switch (type) {
                case TEXT -> {
                    Map<String, Object> inputText = new LinkedHashMap<>();
                    inputText.put(TYPE, "input_text");
                    inputText.put(TEXT, valueAsString(part.get(TEXT)));
                    result.add(inputText);
                }
                case "image_url" -> {
                    Map<String, Object> inputImage = mapInputImage(part.get("image_url"));
                    if (inputImage != null) {
                        result.add(inputImage);
                    }
                }
                default -> log.warn("Ignoring unsupported user content type '{}' in Chat-to-Responses mapping.", type);
            }
        }
        return result;
    }

    private Map<String, Object> mapInputImage(Object imageUrlObject) {
        String url;
        if (imageUrlObject instanceof Map<?, ?> rawImageUrl) {
            url = valueAsString(rawImageUrl.get("url"));
        } else if (imageUrlObject instanceof String s) {
            url = s;
        } else {
            log.warn("Ignoring image_url with missing or invalid url field.");
            return null;
        }
        if (url.isBlank()) {
            return null;
        }
        Map<String, Object> inputImage = new LinkedHashMap<>();
        inputImage.put(TYPE, "input_image");
        inputImage.put("image_url", url);
        if (imageUrlObject instanceof Map<?, ?> rawImageUrl && rawImageUrl.get("detail") != null) {
            inputImage.put("detail", rawImageUrl.get("detail"));
        }
        return inputImage;
    }

    /**
     * 处理 assistant 消息：
     * - 文本 content -> {role:"assistant", content:"text"}
     * - tool_calls -> 多个 function_call / custom_tool_call（放入 pendingToolCalls）
     */
    private void mapAssistantMessage(
            Map<String, Object> message,
            List<Map<String, Object>> inputItems,
            List<Map<String, Object>> pendingToolCalls,
            Map<String, String> toolCallTypes
    ) {
        Object content = message.get(CONTENT);
        String textContent = "";
        if (content instanceof String s) {
            textContent = s;
        } else if (content instanceof List<?> parts) {
            textContent = extractTextFromParts(parts);
        }

        if (!textContent.isBlank()) {
            Map<String, Object> assistantItem = new LinkedHashMap<>();
            assistantItem.put(ROLE, ASSISTANT);
            assistantItem.put(CONTENT, textContent);
            inputItems.add(assistantItem);
        }

        // tool_calls -> function_call items
        Object toolCallsObject = message.get("tool_calls");
        if (toolCallsObject instanceof List<?> rawToolCalls) {
            for (Object tcObj : rawToolCalls) {
                if (!(tcObj instanceof Map<?, ?> rawTc)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> tc = (Map<String, Object>) rawTc;
                String toolType = valueAsString(tc.get(TYPE));
                String callId = valueAsString(tc.get("id"));
                toolCallTypes.put(callId, toolType);
                pendingToolCalls.add(CUSTOM.equals(toolType)
                        ? buildCustomToolCallItem(tc)
                        : buildFunctionCallItem(tc));
            }
        }
    }

    private Map<String, Object> buildFunctionCallItem(Map<String, Object> toolCall) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put(TYPE, "function_call");
        String callId = valueAsString(toolCall.get("id"));
        item.put("call_id", callId);

        Object functionObj = toolCall.get("function");
        if (functionObj instanceof Map<?, ?> rawFunction) {
            item.put("name", valueAsString(rawFunction.get("name")));
            item.put("arguments", valueAsString(rawFunction.get("arguments")));
        } else {
            item.put("name", "");
            item.put("arguments", "");
        }
        item.put("status", "completed");
        return item;
    }

    private Map<String, Object> buildCustomToolCallItem(Map<String, Object> toolCall) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put(TYPE, CUSTOM_TOOL_CALL);
        item.put("call_id", valueAsString(toolCall.get("id")));

        Object customObj = toolCall.get(CUSTOM);
        if (customObj instanceof Map<?, ?> rawCustom) {
            item.put("name", valueAsString(rawCustom.get("name")));
            item.put("input", valueAsString(rawCustom.get("input")));
        } else {
            item.put("name", "");
            item.put("input", "");
        }
        return item;
    }

    private void flushPendingToolCalls(
            List<Map<String, Object>> pendingToolCalls,
            List<Map<String, Object>> inputItems
    ) {
        if (pendingToolCalls.isEmpty()) {
            return;
        }
        inputItems.addAll(pendingToolCalls);
        pendingToolCalls.clear();
    }

    private Map<String, Object> mapToolMessage(
            Map<String, Object> message, Map<String, String> toolCallTypes
    ) {
        Map<String, Object> item = new LinkedHashMap<>();
        String callId = valueAsString(message.get("tool_call_id"));
        boolean customTool = CUSTOM.equals(toolCallTypes.get(callId));
        item.put(TYPE, customTool ? CUSTOM_TOOL_CALL_OUTPUT : "function_call_output");
        item.put("call_id", callId);
        item.put("output", mapToolOutput(message.get(CONTENT)));
        return item;
    }

    private Object mapToolOutput(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> parts) {
            List<Map<String, Object>> outputs = new ArrayList<>(parts.size());
            for (Object partObject : parts) {
                if (!(partObject instanceof Map<?, ?> rawPart)) {
                    continue;
                }
                if (!TEXT.equals(valueAsString(rawPart.get(TYPE)))) {
                    continue;
                }
                Map<String, Object> output = new LinkedHashMap<>();
                output.put(TYPE, "input_text");
                output.put(TEXT, valueAsString(rawPart.get(TEXT)));
                outputs.add(output);
            }
            return outputs.isEmpty() ? JsonUtil.toJsonStr(content) : outputs;
        }
        return JsonUtil.toJsonStr(content);
    }

    private void mapMaxOutputTokens(Map<String, Object> source, Map<String, Object> target) {
        Object maxTokens = source.get("max_completion_tokens");
        if (maxTokens == null) {
            maxTokens = source.get("max_tokens");
        }
        if (maxTokens != null) {
            target.put(MAX_OUTPUT_TOKENS, maxTokens);
        }
    }

    /**
     * Chat Completions tools 格式（嵌套）-> Responses API tools 格式（扁平）。
     *
     * <p>Chat: {@code {type:"function", function:{name, description, parameters, strict}}}
     * <p>Responses: {@code {type:"function", name, description, parameters, strict}}
     */
    private void mapTools(Object toolsObject, Map<String, Object> target) {
        if (!(toolsObject instanceof List<?> rawTools)) {
            return;
        }
        List<Map<String, Object>> tools = new ArrayList<>(rawTools.size());
        for (Object toolObj : rawTools) {
            if (!(toolObj instanceof Map<?, ?> rawTool)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> tool = (Map<String, Object>) rawTool;
            String toolType = valueAsString(tool.get(TYPE));
            if (CUSTOM.equals(toolType)) {
                Map<String, Object> responsesTool = mapCustomTool(tool.get(CUSTOM));
                if (responsesTool != null) {
                    tools.add(responsesTool);
                }
                continue;
            }
            if (!FUNCTION.equals(toolType)) {
                log.warn("Ignoring unsupported tool type '{}' in Chat-to-Responses mapping.", toolType);
                continue;
            }
            Object functionObj = tool.get("function");
            if (!(functionObj instanceof Map<?, ?> rawFunction)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) rawFunction;

            Map<String, Object> responsesTool = new LinkedHashMap<>();
            responsesTool.put(TYPE, FUNCTION);
            copyIfPresent(function, responsesTool, "name");
            copyIfPresent(function, responsesTool, "description");
            Object strict = function.get(STRICT);
            // Chat 默认为非严格模式，桥接时需显式关闭 Responses 的默认严格规范化。
            responsesTool.put(STRICT, strict == null ? false : strict);
            Object parameters = function.get("parameters");
            if (parameters != null) {
                responsesTool.put("parameters", Boolean.TRUE.equals(strict)
                        ? sanitizeJsonSchema(parameters) : parameters);
            }
            tools.add(responsesTool);
        }
        if (!tools.isEmpty()) {
            target.put(TOOLS, tools);
        }
    }

    private Map<String, Object> mapCustomTool(Object customToolObject) {
        if (!(customToolObject instanceof Map<?, ?> rawCustomTool)) {
            return null;
        }
        Map<String, Object> responsesTool = new LinkedHashMap<>();
        responsesTool.put(TYPE, CUSTOM);
        copyIfPresent(rawCustomTool, responsesTool, "name");
        copyIfPresent(rawCustomTool, responsesTool, "description");
        copyIfPresent(rawCustomTool, responsesTool, "format");
        return responsesTool;
    }

    private Object sanitizeJsonSchema(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String key = String.valueOf(entry.getKey());
                if ("default".equals(key) || "format".equals(key)) {
                    continue;
                }
                sanitized.put(key, sanitizeJsonSchema(entry.getValue()));
            }

            Object type = sanitized.get(TYPE);
            Object properties = sanitized.get(PROPERTIES);
            if ("object".equals(type) && properties instanceof Map<?, ?> propertyMap) {
                sanitized.put(ADDITIONAL_PROPERTIES, false);
                sanitized.put(REQUIRED, new ArrayList<>(propertyMap.keySet().stream()
                        .map(String::valueOf)
                        .toList()));
            }
            return sanitized;
        }
        if (value instanceof List<?> rawList) {
            List<Object> sanitized = new ArrayList<>(rawList.size());
            for (Object item : rawList) {
                sanitized.add(sanitizeJsonSchema(item));
            }
            return sanitized;
        }
        return value;
    }

    /**
     * Chat Completions tool_choice -> Responses API tool_choice。
     *
     * <p>字符串（"auto"/"required"/"none"）直接透传。
     * <p>函数/自定义工具对象: {@code {type:"function", function:{name}}} -> {@code {type:"function", name}}
     * <p>允许工具: Chat 的 {@code allowed_tools} 嵌套对象 -> Responses 的扁平字段。
     */
    private void mapToolChoice(Object toolChoiceObject, Map<String, Object> target) {
        if (toolChoiceObject == null) {
            return;
        }
        if (toolChoiceObject instanceof String) {
            // "auto", "required", "none" 在 Responses API 中格式相同
            target.put(TOOL_CHOICE, toolChoiceObject);
            return;
        }
        if (!(toolChoiceObject instanceof Map<?, ?> rawChoice)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> choice = (Map<String, Object>) rawChoice;
        String type = valueAsString(choice.get(TYPE));
        if (FUNCTION.equals(type) || CUSTOM.equals(type)) {
            Map<String, Object> responsesChoice = new LinkedHashMap<>();
            responsesChoice.put(TYPE, type);
            Object toolObj = choice.get(type);
            if (toolObj instanceof Map<?, ?> rawTool) {
                responsesChoice.put("name", valueAsString(rawTool.get("name")));
            }
            target.put(TOOL_CHOICE, responsesChoice);
        } else if ("allowed_tools".equals(type)) {
            mapAllowedToolsToolChoice(choice.get("allowed_tools"), target);
        } else {
            target.put(TOOL_CHOICE, type);
        }
    }

    private void mapAllowedToolsToolChoice(Object allowedToolsObject, Map<String, Object> target) {
        if (!(allowedToolsObject instanceof Map<?, ?> rawAllowedTools)) {
            return;
        }

        Map<String, Object> responsesChoice = new LinkedHashMap<>();
        responsesChoice.put(TYPE, "allowed_tools");
        copyIfPresent(rawAllowedTools, responsesChoice, "mode");

        Object toolsObject = rawAllowedTools.get(TOOLS);
        if (toolsObject instanceof List<?> rawTools) {
            List<Map<String, Object>> tools = new ArrayList<>(rawTools.size());
            for (Object toolObject : rawTools) {
                if (toolObject instanceof Map<?, ?> rawTool) {
                    tools.add(mapAllowedToolReference(rawTool));
                }
            }
            responsesChoice.put(TOOLS, tools);
        }
        target.put(TOOL_CHOICE, responsesChoice);
    }

    private Map<String, Object> mapAllowedToolReference(Map<?, ?> tool) {
        String type = valueAsString(tool.get(TYPE));
        if (FUNCTION.equals(type) || CUSTOM.equals(type)) {
            Map<String, Object> mappedTool = new LinkedHashMap<>();
            mappedTool.put(TYPE, type);
            Object nestedTool = tool.get(type);
            if (nestedTool instanceof Map<?, ?> rawTool) {
                mappedTool.put("name", valueAsString(rawTool.get("name")));
            }
            return mappedTool;
        }

        Map<String, Object> mappedTool = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : tool.entrySet()) {
            mappedTool.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return mappedTool;
    }

    /**
     * response_format -> text.format（Responses API 的格式配置位于 text.format 下）。
     */
    private void mapResponseFormat(Object responseFormat, Map<String, Object> target) {
        if (responseFormat == null) {
            return;
        }
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("format", responseFormat);
        target.put("text", text);
    }

    private void copyRemainingFields(Map<String, Object> source, Map<String, Object> target) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (!CHAT_ONLY_FIELDS.contains(entry.getKey())) {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private void copyIfPresent(Map<?, ?> source, Map<String, Object> target, String field) {
        Object value = source.get(field);
        if (value != null) {
            target.put(field, value);
        }
    }

    private String extractTextContent(Object contentObject) {
        if (contentObject == null) {
            return "";
        }
        if (contentObject instanceof String s) {
            return s;
        }
        if (contentObject instanceof List<?> parts) {
            return extractTextFromParts(parts);
        }
        return String.valueOf(contentObject);
    }

    private String extractTextFromParts(List<?> parts) {
        StringBuilder sb = new StringBuilder();
        for (Object partObj : parts) {
            if (!(partObj instanceof Map<?, ?> rawPart)) {
                continue;
            }
            Object typeObj = rawPart.get(TYPE);
            if (TEXT.equals(typeObj) || typeObj == null) {
                Object t = rawPart.get(TEXT);
                if (t != null) {
                    sb.append(t);
                }
            }
        }
        return sb.toString();
    }

    private String valueAsString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record MappingResult(String instructions, List<Map<String, Object>> inputItems) {
    }
}
