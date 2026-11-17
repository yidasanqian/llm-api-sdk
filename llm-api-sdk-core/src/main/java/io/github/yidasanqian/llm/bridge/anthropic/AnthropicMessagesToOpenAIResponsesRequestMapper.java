package io.github.yidasanqian.llm.bridge.anthropic;

import io.github.yidasanqian.llm.error.ProtocolConversionException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AnthropicMessagesToOpenAIResponsesRequestMapper {

    private static final String ROLE = "role";
    private static final String CONTENT = "content";
    private static final String TYPE = "type";
    private static final String SYSTEM = "system";
    private static final String USER = "user";
    private static final String ASSISTANT = "assistant";
    private static final String TOOL = "tool";
    private static final String TOOLS = "tools";
    private static final String TOOL_CHOICE = "tool_choice";
    private static final String MESSAGES = "messages";
    private static final String INPUT = "input";
    private static final String STREAM_OPTIONS = "stream_options";

    private final AnthropicMessagesToOpenAIChatRequestMapper chatRequestMapper;

    public AnthropicMessagesToOpenAIResponsesRequestMapper(AnthropicMessagesToOpenAIChatRequestMapper chatRequestMapper) {
        this.chatRequestMapper = chatRequestMapper;
    }

    public Map<String, Object> map(Map<String, Object> anthropicRequest, boolean stream) {
        Map<String, Object> normalizedChatRequest = chatRequestMapper.map(anthropicRequest, stream);
        MappingResult mappingResult = mapMessages(normalizedChatRequest.get(MESSAGES));

        Map<String, Object> responsesRequest = new LinkedHashMap<>();
        responsesRequest.put(INPUT, mappingResult.inputItems());
        if (!mappingResult.instructions().isBlank()) {
            responsesRequest.put("instructions", mappingResult.instructions());
        }

        copyRemainingFields(normalizedChatRequest, responsesRequest);
        mapTools(normalizedChatRequest.get(TOOLS), responsesRequest);
        mapToolChoice(normalizedChatRequest.get(TOOL_CHOICE), responsesRequest);
        normalizeMaxOutputTokens(normalizedChatRequest, responsesRequest);
        return responsesRequest;
    }

    private MappingResult mapMessages(Object messagesObject) {
        if (!(messagesObject instanceof List<?> rawMessages)) {
            throw ProtocolConversionException.badRequest("OpenAI normalized messages must be an array.");
        }

        List<Map<String, Object>> inputItems = new ArrayList<>();
        List<String> instructions = new ArrayList<>();
        for (Object messageObject : rawMessages) {
            if (!(messageObject instanceof Map<?, ?> rawMessage)) {
                throw ProtocolConversionException.badRequest("OpenAI normalized message must be an object.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) rawMessage;
            String role = valueAsString(message.get(ROLE));
            switch (role) {
                case SYSTEM -> collectInstruction(message.get(CONTENT), instructions);
                case USER, ASSISTANT -> inputItems.addAll(mapConversationMessage(message));
                case TOOL -> inputItems.add(mapFunctionCallOutput(message));
                default ->
                        throw ProtocolConversionException.badRequest("Unsupported normalized message role: " + role);
            }
        }
        return new MappingResult(String.join("\n\n", instructions), inputItems);
    }

    private void collectInstruction(Object contentObject, List<String> instructions) {
        String content = stringifyTextContent(contentObject);
        if (!content.isBlank()) {
            instructions.add(content);
        }
    }

    private List<Map<String, Object>> mapConversationMessage(Map<String, Object> message) {
        String role = valueAsString(message.get(ROLE));
        List<Map<String, Object>> inputItems = new ArrayList<>();

        Object content = message.get(CONTENT);
        if (content != null) {
            Map<String, Object> inputMessage = new LinkedHashMap<>();
            inputMessage.put(ROLE, role);
            Object mappedContent = USER.equals(role)
                    ? mapUserContent(content)
                    : stringifyTextContent(content);
            if (mappedContent instanceof String textContent) {
                if (!textContent.isBlank()) {
                    inputMessage.put(CONTENT, textContent);
                    inputItems.add(inputMessage);
                }
            } else if (mappedContent instanceof List<?> listContent && !listContent.isEmpty()) {
                inputMessage.put(CONTENT, listContent);
                inputItems.add(inputMessage);
            }
        }

        if (ASSISTANT.equals(role)) {
            Object toolCallsObject = message.get("tool_calls");
            if (toolCallsObject instanceof List<?> rawToolCalls) {
                for (Object toolCallObject : rawToolCalls) {
                    if (toolCallObject instanceof Map<?, ?> rawToolCall) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> toolCall = (Map<String, Object>) rawToolCall;
                        inputItems.add(mapFunctionCall(toolCall));
                    }
                }
            }
        }
        return inputItems;
    }

    private Object mapUserContent(Object contentObject) {
        if (contentObject instanceof String text) {
            return text;
        }
        if (!(contentObject instanceof List<?> rawParts)) {
            return "";
        }

        List<Map<String, Object>> contentParts = new ArrayList<>();
        for (Object partObject : rawParts) {
            if (!(partObject instanceof Map<?, ?> rawPart)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> part = (Map<String, Object>) rawPart;
            String type = valueAsString(part.get(TYPE));
            switch (type) {
                case "text" -> {
                    Map<String, Object> inputText = new LinkedHashMap<>();
                    inputText.put(TYPE, "input_text");
                    inputText.put("text", valueAsString(part.get("text")));
                    contentParts.add(inputText);
                }
                case "image_url" -> contentParts.add(mapInputImage(part.get("image_url")));
                default -> {
                }
            }
        }
        if (contentParts.isEmpty()) {
            return "";
        }
        return contentParts;
    }

    private Map<String, Object> mapInputImage(Object imageUrlObject) {
        if (!(imageUrlObject instanceof Map<?, ?> rawImageUrl)) {
            throw ProtocolConversionException.badRequest("OpenAI normalized image_url must be an object.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> imageUrl = (Map<String, Object>) rawImageUrl;

        Map<String, Object> inputImage = new LinkedHashMap<>();
        inputImage.put(TYPE, "input_image");
        inputImage.put("image_url", valueAsString(imageUrl.get("url")));
        if (imageUrl.get("detail") != null) {
            inputImage.put("detail", imageUrl.get("detail"));
        }
        return inputImage;
    }

    private Map<String, Object> mapFunctionCall(Map<String, Object> toolCall) {
        Map<String, Object> functionCall = new LinkedHashMap<>();
        functionCall.put(TYPE, "function_call");
        functionCall.put("call_id", valueAsString(toolCall.get("id")));

        Object functionObject = toolCall.get("function");
        if (functionObject instanceof Map<?, ?> rawFunction) {
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) rawFunction;
            functionCall.put("name", valueAsString(function.get("name")));
            functionCall.put("arguments", valueAsString(function.get("arguments")));
        }
        functionCall.put("status", "completed");
        return functionCall;
    }

    private Map<String, Object> mapFunctionCallOutput(Map<String, Object> message) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put(TYPE, "function_call_output");
        output.put("call_id", valueAsString(message.get("tool_call_id")));
        output.put("output", valueAsString(message.get(CONTENT)));
        return output;
    }

    private void copyRemainingFields(Map<String, Object> source, Map<String, Object> target) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (MESSAGES.equals(key)
                    || TOOLS.equals(key)
                    || TOOL_CHOICE.equals(key)
                    || STREAM_OPTIONS.equals(key)
                    || "max_tokens".equals(key)
                    || "max_completion_tokens".equals(key)
                    || "output_config".equals(key)
                    || "context_management".equals(key)) {
                continue;
            }
            target.put(key, entry.getValue());
        }
    }

    private void mapTools(Object toolsObject, Map<String, Object> target) {
        if (!(toolsObject instanceof List<?> rawTools)) {
            return;
        }
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Object toolObject : rawTools) {
            if (!(toolObject instanceof Map<?, ?> rawTool)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> tool = (Map<String, Object>) rawTool;
            Map<String, Object> mappedTool = new LinkedHashMap<>();
            mappedTool.put(TYPE, valueAsString(tool.get(TYPE)));

            Object functionObject = tool.get("function");
            if (functionObject instanceof Map<?, ?> rawFunction) {
                @SuppressWarnings("unchecked")
                Map<String, Object> function = (Map<String, Object>) rawFunction;
                copyIfPresent(function, mappedTool, "name");
                copyIfPresent(function, mappedTool, "description");
                copyIfPresent(function, mappedTool, "parameters");
                copyIfPresent(function, mappedTool, "strict");
            }
            tools.add(mappedTool);
        }
        if (!tools.isEmpty()) {
            target.put(TOOLS, tools);
        }
    }

    private void mapToolChoice(Object toolChoiceObject, Map<String, Object> target) {
        if (toolChoiceObject == null) {
            return;
        }
        if (toolChoiceObject instanceof String) {
            target.put(TOOL_CHOICE, toolChoiceObject);
            return;
        }
        if (!(toolChoiceObject instanceof Map<?, ?> rawChoice)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> choice = (Map<String, Object>) rawChoice;
        Map<String, Object> mappedChoice = new LinkedHashMap<>();
        mappedChoice.put(TYPE, valueAsString(choice.get(TYPE)));

        Object functionObject = choice.get("function");
        if (functionObject instanceof Map<?, ?> rawFunction) {
            mappedChoice.put("name", valueAsString(rawFunction.get("name")));
        }
        target.put(TOOL_CHOICE, mappedChoice);
    }

    private void normalizeMaxOutputTokens(Map<String, Object> source, Map<String, Object> target) {
        Object maxTokens = source.get("max_completion_tokens");
        if (maxTokens == null) {
            maxTokens = source.get("max_tokens");
        }
        if (maxTokens != null) {
            target.put("max_output_tokens", maxTokens);
        }
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String field) {
        if (source.get(field) != null) {
            target.put(field, source.get(field));
        }
    }

    private String stringifyTextContent(Object contentObject) {
        if (contentObject == null) {
            return "";
        }
        if (contentObject instanceof String text) {
            return text;
        }
        if (contentObject instanceof List<?> rawParts) {
            StringBuilder builder = new StringBuilder();
            for (Object partObject : rawParts) {
                if (!(partObject instanceof Map<?, ?> rawPart)) {
                    continue;
                }
                Object type = rawPart.get(TYPE);
                if ("text".equals(String.valueOf(type)) && rawPart.get("text") != null) {
                    builder.append(rawPart.get("text"));
                }
            }
            return builder.toString();
        }
        return String.valueOf(contentObject);
    }

    private String valueAsString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record MappingResult(String instructions, List<Map<String, Object>> inputItems) {
    }
}
