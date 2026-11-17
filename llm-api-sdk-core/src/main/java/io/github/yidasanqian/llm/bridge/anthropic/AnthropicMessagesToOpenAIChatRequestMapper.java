package io.github.yidasanqian.llm.bridge.anthropic;

import io.github.yidasanqian.llm.error.ProtocolConversionException;
import io.github.yidasanqian.llm.json.JsonUtil;
import io.github.yidasanqian.llm.bridge.anthropic.AnthropicRequestOptions;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class AnthropicMessagesToOpenAIChatRequestMapper {

    private static final String ROLE = "role";
    private static final String CONTENT = "content";
    private static final String TYPE = "type";
    private static final String TEXT = "text";
    private static final String USER = "user";
    private static final String ASSISTANT = "assistant";
    private static final String SYSTEM = "system";
    private static final String TOOL = "tool";
    private static final String TOOLS = "tools";
    private static final String TOOL_CHOICE = "tool_choice";
    private static final String TOOL_USE = "tool_use";
    private static final String TOOL_RESULT = "tool_result";
    private static final String PARALLEL_TOOL_CALLS = "parallel_tool_calls";
    private static final String DISABLE_PARALLEL_TOOL_USE = "disable_parallel_tool_use";
    private static final String IMAGE = "image";
    private static final String IMAGE_URL = "image_url";
    private static final String THINKING = "thinking";
    private static final String REDACTED_THINKING = "redacted_thinking";
    private static final String DOCUMENT = "document";
    private static final String SERVER_TOOL_USE = "server_tool_use";
    private static final String WEB_SEARCH_TOOL_RESULT = "web_search_tool_result";
    private static final String CODE_EXECUTION_TOOL_RESULT = "code_execution_tool_result";
    private static final String CONTAINER_UPLOAD = "container_upload";
    private static final String STOP = "stop";
    private static final String STOP_SEQUENCES = "stop_sequences";
    private static final String STREAM = "stream";
    private static final String MODEL = "model";
    private static final String TOP_K = "top_k";
    private static final String IS_ERROR = "is_error";
    /** Anthropic 缓存断点字段，OpenAI 不识别，透传会导致 400，需剔除。 */
    private static final String CACHE_CONTROL = "cache_control";

    public Map<String, Object> map(Map<String, Object> anthropicRequest, boolean stream) {
        validateTopLevelFields(anthropicRequest);

        Map<String, Object> openAIRequest = new LinkedHashMap<>();
        List<Map<String, Object>> systemMessages = new ArrayList<>();
        List<Map<String, Object>> conversationMessages = new ArrayList<>();

        appendSystemMessages(anthropicRequest.get("system"), systemMessages);
        appendConversationMessages(anthropicRequest.get("messages"), systemMessages, conversationMessages);

        List<Map<String, Object>> messages = new ArrayList<>(systemMessages);
        messages.addAll(conversationMessages);
        openAIRequest.put("messages", messages);
        openAIRequest.put(STREAM, stream);
        copyRemainingFields(anthropicRequest, openAIRequest);
        AnthropicRequestOptions.applyOutputConfig(anthropicRequest.get("output_config"), openAIRequest);

        Object tools = anthropicRequest.get(TOOLS);
        if (tools != null) {
            openAIRequest.put(TOOLS, mapTools(tools));
        }

        Object toolChoice = anthropicRequest.get(TOOL_CHOICE);
        if (toolChoice != null) {
            openAIRequest.put(TOOL_CHOICE, mapToolChoice(toolChoice));
            if (tools != null && disablesParallelToolUse(toolChoice)) {
                openAIRequest.put(PARALLEL_TOOL_CALLS, false);
            }
        }

        if (stream) {
            AnthropicRequestOptions.ensureStreamIncludeUsage(openAIRequest);
        }

        return openAIRequest;
    }

    private void validateTopLevelFields(Map<String, Object> anthropicRequest) {
        if (!(anthropicRequest.get("messages") instanceof List<?>)) {
            throw ProtocolConversionException.badRequest("Anthropic messages request must contain a messages array.");
        }
    }

    private void appendSystemMessages(Object systemObject, List<Map<String, Object>> messages) {
        if (systemObject == null) {
            return;
        }
        if (systemObject instanceof String text) {
            messages.add(buildSimpleMessage(SYSTEM, text));
            return;
        }
        if (systemObject instanceof Map<?, ?> rawBlock) {
            String text = extractTextBlock(rawBlock, "system");
            if (text != null) {
                messages.add(buildSimpleMessage(SYSTEM, text));
            }
            return;
        }
        if (systemObject instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> rawMap) {
                    String text = extractTextBlock(rawMap, "system");
                    if (text != null) {
                        messages.add(buildSimpleMessage(SYSTEM, text));
                    }
                } else if (block != null) {
                    messages.add(buildSimpleMessage(SYSTEM, String.valueOf(block)));
                }
            }
            return;
        }
        throw ProtocolConversionException.badRequest("Unsupported anthropic system format.");
    }

    private void appendConversationMessages(
            Object messagesObject,
            List<Map<String, Object>> systemMessages,
            List<Map<String, Object>> openAIMessages) {
        List<?> anthropicMessages = (List<?>) messagesObject;
        for (Object messageObject : anthropicMessages) {
            if (!(messageObject instanceof Map<?, ?> rawMessage)) {
                throw ProtocolConversionException.badRequest("Each anthropic message must be an object.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) rawMessage;
            String role = valueAsString(message.get(ROLE));
            switch (role) {
                case SYSTEM -> appendSystemMessages(message.get(CONTENT), systemMessages);
                case USER -> appendUserMessage(message, openAIMessages);
                case ASSISTANT -> appendAssistantMessage(message, openAIMessages);
                default -> throw unsupported("message role", role);
            }
        }
    }

    private void appendUserMessage(Map<String, Object> message, List<Map<String, Object>> openAIMessages) {
        Object content = message.get(CONTENT);
        if (content instanceof String text) {
            openAIMessages.add(buildSimpleMessage(USER, text));
            return;
        }
        if (!(content instanceof List<?> parts)) {
            throw ProtocolConversionException.badRequest("Anthropic user content must be a string or array.");
        }

        List<Map<String, Object>> contentParts = new ArrayList<>();
        List<Map<String, Object>> toolMessages = new ArrayList<>();
        for (Object partObject : parts) {
            if (!(partObject instanceof Map<?, ?> rawPart)) {
                throw ProtocolConversionException.badRequest("Anthropic user content block must be an object.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> part = (Map<String, Object>) rawPart;
            String type = valueAsString(part.get(TYPE));
            if (shouldIgnoreContentType(type, "user")) {
                continue;
            }
            switch (type) {
                case TEXT -> contentParts.add(buildTextPart(part.get(TEXT)));
                case IMAGE -> contentParts.add(mapImagePart(part));
                case TOOL_RESULT -> toolMessages.add(mapToolResultMessage(part));
                default -> throw unsupported("user content type", type);
            }
        }

        openAIMessages.addAll(toolMessages);
        if (!contentParts.isEmpty()) {
            Map<String, Object> userMessage = new LinkedHashMap<>();
            userMessage.put(ROLE, USER);
            userMessage.put(CONTENT, contentParts);
            openAIMessages.add(userMessage);
        }
    }

    private void appendAssistantMessage(Map<String, Object> message, List<Map<String, Object>> openAIMessages) {
        Object content = message.get(CONTENT);
        if (content instanceof String text) {
            openAIMessages.add(buildSimpleMessage(ASSISTANT, text));
            return;
        }
        if (!(content instanceof List<?> parts)) {
            throw ProtocolConversionException.badRequest("Anthropic assistant content must be a string or array.");
        }

        StringBuilder textBuilder = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        for (Object partObject : parts) {
            if (!(partObject instanceof Map<?, ?> rawPart)) {
                throw ProtocolConversionException.badRequest("Anthropic assistant content block must be an object.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> part = (Map<String, Object>) rawPart;
            String type = valueAsString(part.get(TYPE));
            if (shouldIgnoreContentType(type, "assistant")) {
                continue;
            }
            switch (type) {
                case TEXT -> textBuilder.append(valueAsString(part.get(TEXT)));
                case TOOL_USE -> toolCalls.add(mapToolUse(part));
                default -> throw unsupported("assistant content type", type);
            }
        }

        Map<String, Object> assistantMessage = new LinkedHashMap<>();
        assistantMessage.put(ROLE, ASSISTANT);
        if (textBuilder.length() > 0) {
            assistantMessage.put(CONTENT, textBuilder.toString());
        }
        if (!toolCalls.isEmpty()) {
            assistantMessage.put("tool_calls", toolCalls);
        }
        if (assistantMessage.size() == 1) {
            assistantMessage.put(CONTENT, "");
        }
        openAIMessages.add(assistantMessage);
    }

    private Map<String, Object> mapToolUse(Map<String, Object> part) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", valueAsString(part.get("name")));
        function.put("arguments", JsonUtil.toJsonStr(part.getOrDefault("input", Map.of())));

        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", valueAsString(part.get("id")));
        toolCall.put("type", "function");
        toolCall.put("function", function);
        return toolCall;
    }

    private Map<String, Object> mapToolResultMessage(Map<String, Object> part) {
        Map<String, Object> toolMessage = new LinkedHashMap<>();
        toolMessage.put(ROLE, TOOL);
        toolMessage.put("tool_call_id", valueAsString(part.get("tool_use_id")));
        toolMessage.put(CONTENT, serializeToolResultContent(part));
        return toolMessage;
    }

    private String serializeToolResultContent(Map<String, Object> part) {
        Object content = part.get(CONTENT);
        if (Boolean.TRUE.equals(part.get(IS_ERROR))) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(IS_ERROR, true);
            payload.put(CONTENT, content == null ? "" : content);
            return JsonUtil.toJsonStr(payload);
        }
        if (content == null) {
            return "";
        }
        if (content instanceof String text) {
            return text;
        }
        return JsonUtil.toJsonStr(content);
    }

    private Map<String, Object> mapImagePart(Map<String, Object> part) {
        Object sourceObject = part.get("source");
        if (!(sourceObject instanceof Map<?, ?> rawSource)) {
            throw ProtocolConversionException.badRequest("Anthropic image content must contain a source object.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) rawSource;
        String sourceType = valueAsString(source.get("type"));

        String url;
        if ("base64".equals(sourceType)) {
            url = "data:" + valueAsString(source.get("media_type")) + ";base64," + valueAsString(source.get("data"));
        } else if ("url".equals(sourceType)) {
            url = valueAsString(source.get("url"));
        } else {
            throw unsupported("image source type", sourceType);
        }

        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", url);
        imageUrl.put("detail", "auto");

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put(TYPE, IMAGE_URL);
        imagePart.put(IMAGE_URL, imageUrl);
        return imagePart;
    }

    private List<Map<String, Object>> mapTools(Object toolsObject) {
        if (!(toolsObject instanceof List<?> rawTools)) {
            throw ProtocolConversionException.badRequest("Anthropic tools must be an array.");
        }
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Object toolObject : rawTools) {
            if (!(toolObject instanceof Map<?, ?> rawTool)) {
                throw ProtocolConversionException.badRequest("Anthropic tool definition must be an object.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> tool = (Map<String, Object>) rawTool;

            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", valueAsString(tool.get("name")));
            if (tool.get("description") != null) {
                function.put("description", valueAsString(tool.get("description")));
            }
            if (tool.get("input_schema") instanceof Map<?, ?> schema) {
                function.put("parameters", new LinkedHashMap<>(schema));
            }

            Map<String, Object> openAITool = new LinkedHashMap<>();
            openAITool.put(TYPE, "function");
            openAITool.put("function", function);
            tools.add(openAITool);
        }
        return tools;
    }

    private Object mapToolChoice(Object toolChoiceObject) {
        if (toolChoiceObject instanceof String value) {
            return switch (value) {
                case "auto" -> "auto";
                case "any" -> "required";
                case "none" -> "none";
                default -> throw unsupported("tool_choice", value);
            };
        }
        if (!(toolChoiceObject instanceof Map<?, ?> rawChoice)) {
            throw ProtocolConversionException.badRequest("Anthropic tool_choice must be a string or object.");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> choice = (Map<String, Object>) rawChoice;
        String type = valueAsString(choice.get("type"));
        return switch (type) {
            case "auto" -> "auto";
            case "any" -> "required";
            case "none" -> "none";
            case "tool" -> {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", valueAsString(choice.get("name")));

                Map<String, Object> openAIChoice = new LinkedHashMap<>();
                openAIChoice.put("type", "function");
                openAIChoice.put("function", function);
                yield openAIChoice;
            }
            default -> throw unsupported("tool_choice", type);
        };
    }

    private boolean disablesParallelToolUse(Object toolChoiceObject) {
        return toolChoiceObject instanceof Map<?, ?> choice
                && Boolean.TRUE.equals(choice.get(DISABLE_PARALLEL_TOOL_USE));
    }

    private void copyRemainingFields(Map<String, Object> source, Map<String, Object> target) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if ("messages".equals(key)
                    || "system".equals(key)
                    || TOOLS.equals(key)
                    || TOOL_CHOICE.equals(key)
                    || STREAM.equals(key)
                    || THINKING.equals(key)
                    || TOP_K.equals(key)
                    || STOP_SEQUENCES.equals(key)
                    || MODEL.equals(key)
                    || "output_config".equals(key)
                    || "context_management".equals(key)
                    || CACHE_CONTROL.equals(key)) {
                continue;
            }
            target.put(key, entry.getValue());
        }

        Object stopSequences = source.get(STOP_SEQUENCES);
        if (!target.containsKey(STOP) && stopSequences != null) {
            target.put(STOP, stopSequences);
        }
    }

    private Map<String, Object> buildSimpleMessage(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put(ROLE, role);
        message.put(CONTENT, content == null ? "" : content);
        return message;
    }

    private Map<String, Object> buildTextPart(Object text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put(TYPE, TEXT);
        content.put(TEXT, text == null ? "" : String.valueOf(text));
        return content;
    }

    private String extractTextBlock(Map<?, ?> block, String fieldName) {
        Object type = block.get("type");
        if (type != null && shouldIgnoreContentType(String.valueOf(type), fieldName)) {
            return null;
        }
        if (type != null && !"text".equals(String.valueOf(type))) {
            throw unsupported(fieldName + " content type", String.valueOf(type));
        }
        return valueAsString(block.get("text"));
    }

    private boolean shouldIgnoreContentType(String type, String location) {
        boolean ignored = THINKING.equals(type)
                || REDACTED_THINKING.equals(type)
                || DOCUMENT.equals(type)
                || SERVER_TOOL_USE.equals(type)
                || WEB_SEARCH_TOOL_RESULT.equals(type)
                || CODE_EXECUTION_TOOL_RESULT.equals(type)
                || CONTAINER_UPLOAD.equals(type)
                || type.startsWith("mcp_");
        if (ignored) {
            log.warn("Ignoring unsupported anthropic content type '{}' in {} block.", type, location);
        }
        return ignored;
    }

    private String valueAsString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private RuntimeException unsupported(String fieldName, String value) {
        return ProtocolConversionException.badRequest("Unsupported anthropic " + fieldName + ": " + value);
    }
}
