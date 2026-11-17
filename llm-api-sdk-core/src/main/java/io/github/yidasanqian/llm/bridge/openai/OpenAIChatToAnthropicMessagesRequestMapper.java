package io.github.yidasanqian.llm.bridge.openai;

import io.github.yidasanqian.llm.error.ProtocolConversionException;
import io.github.yidasanqian.llm.json.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class OpenAIChatToAnthropicMessagesRequestMapper {

    private static final String ROLE = "role";
    private static final String CONTENT = "content";
    private static final String TYPE = "type";
    private static final String TEXT = "text";
    private static final String USER = "user";
    private static final String ASSISTANT = "assistant";
    private static final String SYSTEM = "system";
    private static final String DEVELOPER = "developer";
    private static final String TOOL = "tool";
    private static final String TOOLS = "tools";
    private static final String TOOL_CHOICE = "tool_choice";
    private static final String ALLOWED_TOOLS = "allowed_tools";
    private static final String FUNCTION = "function";
    private static final String TOOL_USE = "tool_use";
    private static final String TOOL_RESULT = "tool_result";
    private static final String PARALLEL_TOOL_CALLS = "parallel_tool_calls";
    private static final String DISABLE_PARALLEL_TOOL_USE = "disable_parallel_tool_use";
    private static final String IMAGE = "image";
    private static final String IMAGE_URL = "image_url";
    private static final String STREAM = "stream";
    private static final String STOP = "stop";
    private static final String STOP_SEQUENCES = "stop_sequences";
    private static final String MAX_TOKENS = "max_tokens";
    private static final int DEFAULT_MAX_TOKENS = 8192;
    private static final String PROMPT_CACHE_KEY = "prompt_cache_key";
    private static final String CACHE_CONTROL = "cache_control";
    private static final String CACHE_CONTROL_TYPE = "type";
    private static final String EPHEMERAL = "ephemeral";
    private static final Map<String, Object> EMPTY_INPUT_SCHEMA =
            Map.of("type", "object", "properties", Map.of());

    // Fields to silently drop (model is excluded because the caller injects the real backend model name)
    private static final Set<String> IGNORED_FIELDS = Set.of(
            "stream_options", "n", "logprobs", "top_logprobs", "logit_bias",
            "presence_penalty", "frequency_penalty", "seed", "user", "store",
            "metadata", "response_format", "model",
            "output_config", "context_management", "prompt_cache_key", "prompt_cache_retention",
            PARALLEL_TOOL_CALLS
    );

    public Map<String, Object> map(Map<String, Object> openAIRequest, boolean stream) {
        validateTopLevelFields(openAIRequest);

        Map<String, Object> anthropicRequest = new LinkedHashMap<>();

        // Build system string and messages list
        List<String> systemParts = new ArrayList<>();
        List<Map<String, Object>> anthropicMessages = buildMessages(openAIRequest.get("messages"), systemParts);

        anthropicRequest.put("messages", anthropicMessages);

        if (!systemParts.isEmpty()) {
            anthropicRequest.put("system", String.join("\n\n", systemParts));
        }

        anthropicRequest.put(STREAM, stream);

        // max_tokens
        anthropicRequest.put(MAX_TOKENS, resolveMaxTokens(openAIRequest));

        // tools
        Object tools = openAIRequest.get(TOOLS);
        Object toolChoice = openAIRequest.get(TOOL_CHOICE);
        String toolChoiceStr = resolveToolChoiceString(toolChoice);
        boolean dropTools = "none".equals(toolChoiceStr);

        List<Map<String, Object>> mappedTools = List.of();
        if (!dropTools && tools != null) {
            mappedTools = mapTools(tools);
            Set<String> allowedToolNames = resolveAllowedFunctionNames(toolChoice);
            if (allowedToolNames != null) {
                mappedTools = filterAllowedTools(mappedTools, allowedToolNames);
            }
        }

        if (!mappedTools.isEmpty()) {
            anthropicRequest.put(TOOLS, mappedTools);
            boolean disableParallelToolUse =
                    Boolean.FALSE.equals(openAIRequest.get(PARALLEL_TOOL_CALLS));
            Set<String> availableToolNames = new LinkedHashSet<>();
            for (Map<String, Object> tool : mappedTools) {
                availableToolNames.add(valueAsString(tool.get("name")));
            }
            Object mappedToolChoice = mapToolChoice(
                    toolChoice, disableParallelToolUse, availableToolNames);
            if (mappedToolChoice != null) {
                anthropicRequest.put(TOOL_CHOICE, mappedToolChoice);
            }
        }

        // stop → stop_sequences; Anthropic only accepts arrays, so wrap a string value
        Object stop = openAIRequest.get(STOP);
        if (stop instanceof String stopStr) {
            anthropicRequest.put(STOP_SEQUENCES, List.of(stopStr));
        } else if (stop instanceof List<?>) {
            anthropicRequest.put(STOP_SEQUENCES, stop);
        }

        // transparent pass-through fields
        copyTransparentFields(openAIRequest, anthropicRequest);

        // 客户端声明了 prompt_cache_key 时，映射为 Anthropic 顶层自动缓存字段。
        // Anthropic 的 prompt caching 是 opt-in：请求体没有 cache_control 就完全不缓存，
        // 而 OpenAI 的 prompt_cache_key 在 Anthropic 侧没有等价字段，只能显式映射。
        // 顶层自动缓存由上游自动定位最后一个可缓存块并随对话增长前移，不介入消息结构；
        // 低于最小可缓存长度时上游静默忽略且不报错，因此此处不判断阈值。
        if (declaresPromptCache(openAIRequest) && !anthropicRequest.containsKey(CACHE_CONTROL)) {
            anthropicRequest.put(CACHE_CONTROL, Map.of(CACHE_CONTROL_TYPE, EPHEMERAL));
        }

        return anthropicRequest;
    }

    /**
     * 判断客户端是否显式声明启用跨协议缓存。
     * <p>{@code prompt_cache_key} 在 OpenAI 语义中即为缓存键，客户端传入非空值视为显式声明。</p>
     */
    private boolean declaresPromptCache(Map<String, Object> openAIRequest) {
        Object cacheKey = openAIRequest.get(PROMPT_CACHE_KEY);
        return cacheKey != null && !String.valueOf(cacheKey).isBlank();
    }

    private void validateTopLevelFields(Map<String, Object> openAIRequest) {
        if (!(openAIRequest.get("messages") instanceof List<?>)) {
            throw ProtocolConversionException.badRequest("OpenAI chat request must contain a messages array.");
        }
    }

    private List<Map<String, Object>> buildMessages(Object messagesObject, List<String> systemParts) {
        List<?> rawMessages = (List<?>) messagesObject;
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> pendingToolResults = new ArrayList<>();
        Set<String> skippedToolCallIds = new HashSet<>();

        for (Object msgObj : rawMessages) {
            if (!(msgObj instanceof Map<?, ?> rawMsg)) {
                throw ProtocolConversionException.badRequest("Each OpenAI message must be an object.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) rawMsg;
            String role = valueAsString(message.get(ROLE));

            switch (role) {
                case SYSTEM, DEVELOPER -> {
                    String text = extractSystemText(message.get(CONTENT));
                    if (text != null && !text.isEmpty()) {
                        systemParts.add(text);
                    }
                }
                case TOOL -> {
                    String toolCallId = valueAsString(message.get("tool_call_id"));
                    if (toolCallId.isBlank()) {
                        log.warn("Ignoring OpenAI tool result without tool_call_id.");
                        continue;
                    }
                    if (skippedToolCallIds.contains(toolCallId)) {
                        log.warn("Ignoring OpenAI tool result for unsupported tool call '{}'.", toolCallId);
                        continue;
                    }
                    Map<String, Object> toolResultBlock = buildToolResultBlock(message);
                    pendingToolResults.add(toolResultBlock);
                }
                case USER -> {
                    List<Map<String, Object>> contentArray = new ArrayList<>();
                    // prepend pending tool_results first
                    contentArray.addAll(pendingToolResults);
                    pendingToolResults.clear();
                    // add user's own content
                    contentArray.addAll(buildUserContentParts(message.get(CONTENT)));

                    Map<String, Object> userMessage = new LinkedHashMap<>();
                    userMessage.put(ROLE, USER);
                    userMessage.put(CONTENT, contentArray);
                    result.add(userMessage);
                }
                case ASSISTANT -> {
                    // if there are pending tool_results with no user message, emit synthetic user message
                    if (!pendingToolResults.isEmpty()) {
                        result.add(buildSyntheticUserMessage(pendingToolResults));
                        pendingToolResults.clear();
                    }
                    result.add(buildAssistantMessage(message, skippedToolCallIds));
                }
                default -> throw unsupported("message role", role);
            }
        }

        // flush remaining tool_results
        if (!pendingToolResults.isEmpty()) {
            result.add(buildSyntheticUserMessage(pendingToolResults));
        }

        return result;
    }

    private String extractSystemText(Object content) {
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> rawPart) {
                    Object typeObj = rawPart.get(TYPE);
                    if (TEXT.equals(typeObj) || typeObj == null) {
                        Object t = rawPart.get(TEXT);
                        if (t != null) {
                            if (sb.length() > 0) sb.append("\n\n");
                            sb.append(String.valueOf(t));
                        }
                    }
                } else if (part != null) {
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append(String.valueOf(part));
                }
            }
            return sb.toString();
        }
        return null;
    }

    private Map<String, Object> buildToolResultBlock(Map<String, Object> toolMessage) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put(TYPE, TOOL_RESULT);
        block.put("tool_use_id", valueAsString(toolMessage.get("tool_call_id")));
        Object content = toolMessage.get(CONTENT);
        if (content == null) {
            block.put(CONTENT, "");
        } else if (content instanceof String text) {
            block.put(CONTENT, text);
        } else if (content instanceof List<?>) {
            block.put(CONTENT, content);
        } else {
            block.put(CONTENT, JsonUtil.toJsonStr(content));
        }
        return block;
    }

    private List<Map<String, Object>> buildUserContentParts(Object content) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (content instanceof String text) {
            parts.add(buildTextBlock(text));
            return parts;
        }
        if (content instanceof List<?> contentArray) {
            for (Object itemObj : contentArray) {
                if (!(itemObj instanceof Map<?, ?> rawItem)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) rawItem;
                String type = valueAsString(item.get(TYPE));
                switch (type) {
                    case TEXT -> parts.add(buildTextBlock(valueAsString(item.get(TEXT))));
                    case IMAGE_URL -> {
                        Map<String, Object> imageBlock = mapImageUrlBlock(item);
                        if (imageBlock != null) {
                            parts.add(imageBlock);
                        }
                    }
                    default -> log.warn("Ignoring unsupported OpenAI user content type '{}' in user message.", type);
                }
            }
            return parts;
        }
        // null or unexpected — return empty
        return parts;
    }

    private Map<String, Object> mapImageUrlBlock(Map<String, Object> item) {
        Object imageUrlObj = item.get(IMAGE_URL);
        String url;
        if (imageUrlObj instanceof Map<?, ?> rawImageUrl) {
            url = valueAsString(rawImageUrl.get("url"));
        } else if (imageUrlObj instanceof String s) {
            url = s;
        } else {
            log.warn("Ignoring image_url content block with missing or invalid url field.");
            return null;
        }

        Map<String, Object> imageBlock = new LinkedHashMap<>();
        imageBlock.put(TYPE, IMAGE);

        if (url.startsWith("data:")) {
            // data:image/jpeg;base64,XXXX
            int semiBase64Idx = url.indexOf(";base64,");
            if (semiBase64Idx < 0) {
                log.warn("Ignoring image_url data URL with unsupported format (missing ;base64,): {}", url);
                return null;
            }
            String mediaType = url.substring("data:".length(), semiBase64Idx);
            String data = url.substring(semiBase64Idx + ";base64,".length());

            Map<String, Object> source = new LinkedHashMap<>();
            source.put(TYPE, "base64");
            source.put("media_type", mediaType);
            source.put("data", data);
            imageBlock.put("source", source);
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put(TYPE, "url");
            source.put("url", url);
            imageBlock.put("source", source);
        } else {
            log.warn("Ignoring image_url with unsupported URL scheme: {}", url);
            return null;
        }

        return imageBlock;
    }

    private Map<String, Object> buildSyntheticUserMessage(List<Map<String, Object>> toolResults) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put(ROLE, USER);
        message.put(CONTENT, new ArrayList<>(toolResults));
        return message;
    }

    private Map<String, Object> buildAssistantMessage(
            Map<String, Object> message, Set<String> skippedToolCallIds) {
        List<Map<String, Object>> contentArray = new ArrayList<>();

        Object content = message.get(CONTENT);
        if (content instanceof String text && !text.isEmpty()) {
            contentArray.add(buildTextBlock(text));
        } else if (content instanceof List<?> parts) {
            for (Object partObj : parts) {
                if (!(partObj instanceof Map<?, ?> rawPart)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> part = (Map<String, Object>) rawPart;
                String type = valueAsString(part.get(TYPE));
                if (TEXT.equals(type)) {
                    contentArray.add(buildTextBlock(valueAsString(part.get(TEXT))));
                }
            }
        }

        Object toolCallsObj = message.get("tool_calls");
        if (toolCallsObj instanceof List<?> toolCalls) {
            for (Object tcObj : toolCalls) {
                if (!(tcObj instanceof Map<?, ?> rawTc)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> tc = (Map<String, Object>) rawTc;
                Map<String, Object> toolUseBlock = mapToolCallToToolUse(tc);
                if (toolUseBlock != null) {
                    contentArray.add(toolUseBlock);
                } else {
                    skippedToolCallIds.add(valueAsString(tc.get("id")));
                }
            }
        }

        Map<String, Object> assistantMessage = new LinkedHashMap<>();
        assistantMessage.put(ROLE, ASSISTANT);
        // Anthropic rejects empty content arrays; fallback to empty text block
        if (contentArray.isEmpty()) {
            contentArray.add(buildTextBlock(""));
        }
        assistantMessage.put(CONTENT, contentArray);
        return assistantMessage;
    }

    private Map<String, Object> mapToolCallToToolUse(Map<String, Object> toolCall) {
        String callId = valueAsString(toolCall.get("id"));
        String type = valueAsString(toolCall.get(TYPE));
        if (!type.isBlank() && !FUNCTION.equals(type)) {
            log.warn("Ignoring unsupported OpenAI tool call type '{}' for call '{}'.", type, callId);
            return null;
        }

        Object functionObj = toolCall.get("function");
        if (!(functionObj instanceof Map<?, ?> rawFunction)) {
            log.warn("Ignoring OpenAI tool call '{}' without a function object.", callId);
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) rawFunction;
        String name = valueAsString(function.get("name"));
        if (name.isBlank()) {
            log.warn("Ignoring OpenAI tool call '{}' without a function name.", callId);
            return null;
        }

        Map<String, Object> block = new LinkedHashMap<>();
        block.put(TYPE, TOOL_USE);
        block.put("id", callId);
        block.put("name", name);

        Object arguments = function.get("arguments");
        Map<String, Object> input;
        if (arguments instanceof String argsStr) {
            try {
                input = JsonUtil.toObject(argsStr, Map.class);
                if (input == null) {
                    input = new LinkedHashMap<>();
                }
            } catch (Exception e) {
                log.warn("Failed to parse tool_call function.arguments as JSON, using empty map. arguments={}", argsStr, e);
                input = new LinkedHashMap<>();
            }
        } else if (arguments instanceof Map<?, ?> argsMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> castedMap = (Map<String, Object>) argsMap;
            input = castedMap;
        } else {
            input = new LinkedHashMap<>();
        }
        block.put("input", input);

        return block;
    }

    private List<Map<String, Object>> mapTools(Object toolsObject) {
        if (!(toolsObject instanceof List<?> rawTools)) {
            throw ProtocolConversionException.badRequest("OpenAI tools must be an array.");
        }
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Object toolObj : rawTools) {
            if (!(toolObj instanceof Map<?, ?> rawTool)) {
                log.warn("Ignoring non-object OpenAI tool definition.");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> tool = (Map<String, Object>) rawTool;

            String type = valueAsString(tool.get(TYPE));
            if (!type.isBlank() && !FUNCTION.equals(type)) {
                log.warn("Ignoring unsupported OpenAI tool type '{}' for Anthropic bridge.", type);
                continue;
            }

            Object functionObj = tool.get(FUNCTION);
            if (!(functionObj instanceof Map<?, ?> rawFunction)) {
                log.warn("Ignoring OpenAI tool definition without a function object.");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) rawFunction;
            String name = valueAsString(function.get("name"));
            if (name.isBlank()) {
                log.warn("Ignoring OpenAI function tool without a name.");
                continue;
            }

            Map<String, Object> anthropicTool = new LinkedHashMap<>();
            anthropicTool.put("name", name);
            if (function.get("description") != null) {
                anthropicTool.put("description", valueAsString(function.get("description")));
            }
            Object parameters = function.get("parameters");
            if (parameters instanceof Map<?, ?>) {
                anthropicTool.put("input_schema", parameters);
            } else {
                if (parameters != null) {
                    log.warn("Ignoring invalid parameters for OpenAI tool '{}'.", name);
                }
                anthropicTool.put("input_schema", EMPTY_INPUT_SCHEMA);
            }
            tools.add(anthropicTool);
        }
        return tools;
    }

    /**
     * 提取 allowed_tools 中可在 Anthropic 侧表达的 function 工具名。
     * 返回 null 表示该 tool_choice 不是 allowed_tools，调用方不应过滤工具。
     */
    private Set<String> resolveAllowedFunctionNames(Object toolChoiceObject) {
        if (!(toolChoiceObject instanceof Map<?, ?> rawChoice)
                || !ALLOWED_TOOLS.equals(valueAsString(rawChoice.get(TYPE)))) {
            return null;
        }
        Object allowedToolsObject = rawChoice.get(ALLOWED_TOOLS);
        if (!(allowedToolsObject instanceof Map<?, ?> allowedTools)) {
            log.warn("Ignoring malformed OpenAI allowed_tools tool_choice.");
            return null;
        }
        Object toolsObject = allowedTools.get(TOOLS);
        if (!(toolsObject instanceof List<?> toolRefs)) {
            log.warn("Ignoring malformed OpenAI allowed_tools tool list.");
            return null;
        }

        Set<String> names = new LinkedHashSet<>();
        for (Object toolRefObject : toolRefs) {
            if (!(toolRefObject instanceof Map<?, ?> toolRef)) {
                log.warn("Ignoring non-object entry in OpenAI allowed_tools.");
                continue;
            }
            String type = valueAsString(toolRef.get(TYPE));
            if (!FUNCTION.equals(type)) {
                log.warn("Ignoring unsupported OpenAI allowed_tools entry type '{}'.", type);
                continue;
            }
            Object functionObject = toolRef.get(FUNCTION);
            if (!(functionObject instanceof Map<?, ?> function)) {
                log.warn("Ignoring OpenAI allowed_tools function entry without a function object.");
                continue;
            }
            String name = valueAsString(function.get("name"));
            if (name.isBlank()) {
                log.warn("Ignoring OpenAI allowed_tools function entry without a name.");
                continue;
            }
            names.add(name);
        }
        return names;
    }

    private List<Map<String, Object>> filterAllowedTools(
            List<Map<String, Object>> tools, Set<String> allowedToolNames) {
        List<Map<String, Object>> filteredTools = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            if (allowedToolNames.contains(valueAsString(tool.get("name")))) {
                filteredTools.add(tool);
            }
        }
        if (filteredTools.isEmpty() && !tools.isEmpty()) {
            log.warn("No compatible function tools remain after applying OpenAI allowed_tools.");
        }
        return filteredTools;
    }

    /**
     * Returns the raw string value of tool_choice for "none" detection, or null if it's an object.
     */
    private String resolveToolChoiceString(Object toolChoice) {
        if (toolChoice instanceof String s) {
            return s;
        }
        return null;
    }

    private Object mapToolChoice(
            Object toolChoiceObject,
            boolean disableParallelToolUse,
            Set<String> availableToolNames) {
        if (toolChoiceObject == null) {
            return disableParallelToolUse ? buildTypeMap("auto", true) : null;
        }
        if (toolChoiceObject instanceof String value) {
            return switch (value) {
                case "auto" -> buildTypeMap("auto", disableParallelToolUse);
                case "required" -> buildTypeMap("any", disableParallelToolUse);
                case "none" -> null; // caller handles dropping
                default -> {
                    log.warn("Ignoring unsupported OpenAI tool_choice string '{}'.", value);
                    yield null;
                }
            };
        }
        if (!(toolChoiceObject instanceof Map<?, ?> rawChoice)) {
            log.warn("Ignoring OpenAI tool_choice with unsupported value type.");
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> choice = (Map<String, Object>) rawChoice;
        String type = valueAsString(choice.get(TYPE));
        if (FUNCTION.equals(type)) {
            Object functionObj = choice.get("function");
            String name = "";
            if (functionObj instanceof Map<?, ?> rawFunction) {
                name = valueAsString(((Map<?, ?>) rawFunction).get("name"));
            }
            if (name.isBlank() || !availableToolNames.contains(name)) {
                log.warn("Ignoring OpenAI tool_choice for unavailable function '{}'.", name);
                return null;
            }
            Map<String, Object> anthropicChoice = new LinkedHashMap<>();
            anthropicChoice.put(TYPE, "tool");
            anthropicChoice.put("name", name);
            if (disableParallelToolUse) {
                anthropicChoice.put(DISABLE_PARALLEL_TOOL_USE, true);
            }
            return anthropicChoice;
        }
        if (ALLOWED_TOOLS.equals(type)) {
            Object allowedToolsObject = choice.get(ALLOWED_TOOLS);
            if (!(allowedToolsObject instanceof Map<?, ?> allowedTools)) {
                log.warn("Ignoring malformed OpenAI allowed_tools tool_choice.");
                return null;
            }
            String mode = valueAsString(allowedTools.get("mode"));
            if (!"auto".equals(mode) && !"required".equals(mode)) {
                log.warn("Ignoring unsupported OpenAI allowed_tools mode '{}', falling back to auto.", mode);
            }
            String anthropicMode = "required".equals(mode) ? "any" : "auto";
            return buildTypeMap(anthropicMode, disableParallelToolUse);
        }
        log.warn("Ignoring unsupported OpenAI tool_choice type '{}'.", type);
        return null;
    }

    private int resolveMaxTokens(Map<String, Object> openAIRequest) {
        Object maxTokens = openAIRequest.get(MAX_TOKENS);
        if (maxTokens instanceof Number n) {
            return n.intValue();
        }
        Object maxCompletionTokens = openAIRequest.get("max_completion_tokens");
        if (maxCompletionTokens instanceof Number n) {
            return n.intValue();
        }
        return DEFAULT_MAX_TOKENS;
    }

    private void copyTransparentFields(Map<String, Object> source, Map<String, Object> target) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if ("messages".equals(key)
                    || SYSTEM.equals(key)
                    || TOOLS.equals(key)
                    || TOOL_CHOICE.equals(key)
                    || STREAM.equals(key)
                    || MAX_TOKENS.equals(key)
                    || "max_completion_tokens".equals(key)
                    || STOP.equals(key)
                    || IGNORED_FIELDS.contains(key)) {
                continue;
            }
            target.put(key, entry.getValue());
        }
    }

    private Map<String, Object> buildTextBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put(TYPE, TEXT);
        block.put(TEXT, text == null ? "" : text);
        return block;
    }

    private Map<String, Object> buildTypeMap(String type, boolean disableParallelToolUse) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(TYPE, type);
        if (disableParallelToolUse) {
            map.put(DISABLE_PARALLEL_TOOL_USE, true);
        }
        return map;
    }

    private String valueAsString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private RuntimeException unsupported(String fieldName, String value) {
        return ProtocolConversionException.badRequest("Unsupported OpenAI " + fieldName + ": " + value);
    }
}
