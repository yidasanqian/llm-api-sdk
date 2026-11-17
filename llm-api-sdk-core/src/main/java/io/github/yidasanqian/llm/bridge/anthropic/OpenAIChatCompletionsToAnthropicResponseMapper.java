package io.github.yidasanqian.llm.bridge.anthropic;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.github.yidasanqian.llm.error.ProtocolConversionException;
import io.github.yidasanqian.llm.bridge.BridgeStreamMapper;
import io.github.yidasanqian.llm.bridge.BridgeStreamSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.yidasanqian.llm.protocol.SseEvent;
import java.util.*;
import java.util.function.Predicate;

@Slf4j
@RequiredArgsConstructor
public class OpenAIChatCompletionsToAnthropicResponseMapper implements BridgeStreamMapper {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public String mapNonStream(String openAIResponse) {
        try {
            JsonNode root = objectMapper.readTree(openAIResponse);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", textOrDefault(root.get("id"), "msg_" + System.currentTimeMillis()));
            response.put("type", "message");
            response.put("role", "assistant");
            response.put("model", textOrDefault(root.get("model"), ""));
            response.put("content", buildNonStreamContent(root));
            response.put("stop_reason", convertFinishReason(extractFinishReason(root)));
            response.put("stop_sequence", null);
            response.put("usage", buildUsage(root.get("usage")));
            return objectMapper.writeValueAsString(response);
        } catch (JacksonException e) {
            throw ProtocolConversionException.of(
                    "Failed to map OpenAI chat completion response: " + e.getMessage(), 500);
        }
    }

    @Override
    public Predicate<SseEvent<String>> createStreamReadiness() {
        StreamState state = new StreamState();
        return sse -> !mapChunk(sse, state).isEmpty() || state.completed;
    }

    @Override
    public BridgeStreamSession newSession() {
        StreamState state = new StreamState();
        return new BridgeStreamSession() {
            @Override
            public List<SseEvent<String>> map(SseEvent<String> event) {
                return mapChunk(event, state);
            }

            @Override
            public List<SseEvent<String>> finishSuccess() {
                if (!state.completed) {
                    throw ProtocolConversionException.of("Chat stream ended without [DONE]", 502);
                }
                return List.of();
            }
        };
    }

    private List<SseEvent<String>> mapChunk(SseEvent<String> sse, StreamState state) {
        if (state.completed) return List.of();
        if (sse != null && "ping".equals(sse.event())) {
            return List.of(buildPingEvent());
        }

        String data = sse != null ? sse.data() : null;
        if (data == null || data.isBlank()) {
            return List.of();
        }
        if ("[DONE]".equals(data.trim())) {
            return buildStopEvents(state);
        }

        try {
            JsonNode root = objectMapper.readTree(data);
            List<SseEvent<String>> events = new ArrayList<>();

            state.messageId = textOrDefault(root.get("id"), state.messageId);
            state.model = textOrDefault(root.get("model"), state.model);
            ensureMessageStart(state, events);

            JsonNode choice = extractFirstChoice(root);
            if (choice != null) {
                appendTextDelta(choice.path("delta").get("content"), state, events);
                appendToolDeltas(choice.path("delta").get("tool_calls"), state, events);
                if (choice.hasNonNull("finish_reason")) {
                    state.stopReason = convertFinishReason(choice.get("finish_reason").asText());
                }
            }

            updateUsage(root.get("usage"), state);
            return events;
        } catch (JacksonException e) {
            log.warn("Failed to parse OpenAI stream chunk: {}", e.getMessage());
            return List.of();
        }
    }

    private void ensureMessageStart(StreamState state, List<SseEvent<String>> events) throws JacksonException {
        if (state.messageStarted) {
            return;
        }
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "message_start");

        ObjectNode message = objectMapper.createObjectNode();
        message.put("id", state.messageId == null ? "msg_" + System.currentTimeMillis() : state.messageId);
        message.put("type", "message");
        message.put("role", "assistant");
        message.set("content", objectMapper.createArrayNode());
        message.put("model", state.model == null ? "" : state.model);
        message.putNull("stop_reason");
        message.putNull("stop_sequence");

        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("input_tokens", 0);
        usage.put("output_tokens", 0);
        message.set("usage", usage);

        event.set("message", message);
        events.add(new AnthropicSseEvent("message_start", objectMapper.writeValueAsString(event)).toSseEvent());
        state.messageStarted = true;
    }

    private void appendTextDelta(JsonNode contentNode, StreamState state, List<SseEvent<String>> events) throws JacksonException {
        if (contentNode == null || contentNode.isNull()) {
            return;
        }

        String text;
        if (contentNode.isTextual()) {
            text = contentNode.asText();
        } else if (contentNode.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : contentNode) {
                if ("text".equals(part.path("type").asText())) {
                    builder.append(part.path("text").asText(""));
                }
            }
            text = builder.toString();
        } else {
            text = "";
        }

        if (text.isEmpty()) {
            return;
        }

        if (state.activeTextBlockIndex == null) {
            int index = state.nextBlockIndex++;
            state.activeTextBlockIndex = index;

            ObjectNode event = objectMapper.createObjectNode();
            event.put("type", "content_block_start");
            event.put("index", index);

            ObjectNode block = objectMapper.createObjectNode();
            block.put("type", "text");
            block.put("text", "");
            event.set("content_block", block);

            events.add(new AnthropicSseEvent("content_block_start", objectMapper.writeValueAsString(event)).toSseEvent());
        }

        ObjectNode deltaEvent = objectMapper.createObjectNode();
        deltaEvent.put("type", "content_block_delta");
        deltaEvent.put("index", state.activeTextBlockIndex);

        ObjectNode delta = objectMapper.createObjectNode();
        delta.put("type", "text_delta");
        delta.put("text", text);
        deltaEvent.set("delta", delta);

        events.add(new AnthropicSseEvent("content_block_delta", objectMapper.writeValueAsString(deltaEvent)).toSseEvent());
    }

    private void appendToolDeltas(JsonNode toolCallsNode, StreamState state, List<SseEvent<String>> events) throws JacksonException {
        if (toolCallsNode == null || !toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
            return;
        }

        if (state.activeTextBlockIndex != null) {
            events.add(buildContentBlockStop(state.activeTextBlockIndex));
            state.closedBlockIndexes.add(state.activeTextBlockIndex);
            state.activeTextBlockIndex = null;
        }

        for (JsonNode toolCallNode : toolCallsNode) {
            int openAIIndex = toolCallNode.path("index").asInt(state.toolStates.size());
            ToolState toolState = state.toolStates.computeIfAbsent(openAIIndex, key -> new ToolState(state.nextBlockIndex++));
            if (toolCallNode.hasNonNull("id")) {
                toolState.id = toolCallNode.get("id").asText();
            }

            JsonNode functionNode = toolCallNode.get("function");
            if (functionNode != null && functionNode.hasNonNull("name")) {
                toolState.name = functionNode.get("name").asText();
            }

            if (!toolState.started) {
                ObjectNode event = objectMapper.createObjectNode();
                event.put("type", "content_block_start");
                event.put("index", toolState.blockIndex);

                ObjectNode block = objectMapper.createObjectNode();
                block.put("type", "tool_use");
                block.put("id", toolState.id == null || toolState.id.isBlank() ? "toolu_" + openAIIndex : toolState.id);
                block.put("name", toolState.name == null || toolState.name.isBlank() ? "unknown_tool" : toolState.name);
                block.set("input", objectMapper.createObjectNode());
                event.set("content_block", block);

                events.add(new AnthropicSseEvent("content_block_start", objectMapper.writeValueAsString(event)).toSseEvent());
                toolState.started = true;
            }

            JsonNode functionArguments = functionNode == null ? null : functionNode.get("arguments");
            if (functionArguments != null && functionArguments.isTextual() && !functionArguments.asText().isEmpty()) {
                ObjectNode event = objectMapper.createObjectNode();
                event.put("type", "content_block_delta");
                event.put("index", toolState.blockIndex);

                ObjectNode delta = objectMapper.createObjectNode();
                delta.put("type", "input_json_delta");
                delta.put("partial_json", functionArguments.asText());
                event.set("delta", delta);

                events.add(new AnthropicSseEvent("content_block_delta", objectMapper.writeValueAsString(event)).toSseEvent());
            }
        }
    }

    private void updateUsage(JsonNode usageNode, StreamState state) {
        if (usageNode == null || usageNode.isNull()) {
            return;
        }
        state.inputTokens = usageNode.path("prompt_tokens").asLong(state.inputTokens);
        state.outputTokens = usageNode.path("completion_tokens").asLong(state.outputTokens);

        JsonNode promptDetails = usageNode.get("prompt_tokens_details");
        if (promptDetails != null && promptDetails.hasNonNull("cached_tokens")) {
            state.cacheReadInputTokens = promptDetails.get("cached_tokens").asLong(0);
        }
        if (promptDetails != null && promptDetails.hasNonNull("cache_write_tokens")) {
            state.cacheCreationInputTokens = promptDetails.get("cache_write_tokens").asLong(0);
        }
    }

    private List<SseEvent<String>> buildStopEvents(StreamState state) {
        if (state.completed) {
            return List.of();
        }
        state.completed = true;
        if (!state.messageStarted) {
            return List.of();
        }

        List<SseEvent<String>> events = new ArrayList<>();
        try {
            if (state.activeTextBlockIndex != null && !state.closedBlockIndexes.contains(state.activeTextBlockIndex)) {
                events.add(buildContentBlockStop(state.activeTextBlockIndex));
                state.closedBlockIndexes.add(state.activeTextBlockIndex);
                state.activeTextBlockIndex = null;
            }

            state.toolStates.values().stream()
                    .sorted(Comparator.comparingInt(toolState -> toolState.blockIndex))
                    .filter(toolState -> toolState.started && !state.closedBlockIndexes.contains(toolState.blockIndex))
                    .forEach(toolState -> {
                        events.add(buildContentBlockStop(toolState.blockIndex));
                        state.closedBlockIndexes.add(toolState.blockIndex);
                    });

            ObjectNode deltaEvent = objectMapper.createObjectNode();
            deltaEvent.put("type", "message_delta");

            ObjectNode delta = objectMapper.createObjectNode();
            delta.put("stop_reason", state.stopReason == null ? "end_turn" : state.stopReason);
            delta.putNull("stop_sequence");
            deltaEvent.set("delta", delta);

            ObjectNode usage = objectMapper.createObjectNode();
            usage.put("input_tokens", state.inputTokens);
            usage.put("output_tokens", state.outputTokens);
            usage.put("cache_read_input_tokens", state.cacheReadInputTokens);
            usage.put("cache_creation_input_tokens", state.cacheCreationInputTokens);
            deltaEvent.set("usage", usage);

            events.add(new AnthropicSseEvent("message_delta", objectMapper.writeValueAsString(deltaEvent)).toSseEvent());

            ObjectNode stopEvent = objectMapper.createObjectNode();
            stopEvent.put("type", "message_stop");
            events.add(new AnthropicSseEvent("message_stop", objectMapper.writeValueAsString(stopEvent)).toSseEvent());
        } catch (JacksonException e) {
            log.warn("Failed to build anthropic stop events: {}", e.getMessage());
        }

        return events;
    }

    private SseEvent<String> buildContentBlockStop(int index) {
        try {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("type", "content_block_stop");
            event.put("index", index);
            return new AnthropicSseEvent("content_block_stop", objectMapper.writeValueAsString(event)).toSseEvent();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize content_block_stop", e);
        }
    }

    private SseEvent<String> buildPingEvent() {
        return new AnthropicSseEvent("ping", "{\"type\":\"ping\"}").toSseEvent();
    }

    private List<Map<String, Object>> buildNonStreamContent(JsonNode root) throws JacksonException {
        List<Map<String, Object>> content = new ArrayList<>();
        JsonNode message = root.path("choices").isArray() && !root.path("choices").isEmpty()
                ? root.path("choices").get(0).path("message")
                : null;
        if (message == null || message.isMissingNode()) {
            return content;
        }

        JsonNode messageContent = message.get("content");
        if (messageContent != null && !messageContent.isNull()) {
            if (messageContent.isTextual()) {
                String text = messageContent.asText();
                if (!text.isEmpty()) {
                    content.add(buildTextBlock(text));
                }
            } else if (messageContent.isArray()) {
                for (JsonNode part : messageContent) {
                    if ("text".equals(part.path("type").asText())) {
                        String text = part.path("text").asText("");
                        if (!text.isEmpty()) {
                            content.add(buildTextBlock(text));
                        }
                    }
                }
            }
        }

        JsonNode refusal = message.get("refusal");
        if (refusal != null && refusal.isTextual() && !refusal.asText().isEmpty()) {
            content.add(buildTextBlock(refusal.asText()));
        }

        JsonNode toolCalls = message.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray()) {
            for (JsonNode toolCall : toolCalls) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "tool_use");
                block.put("id", textOrDefault(toolCall.get("id"), ""));
                block.put("name", textOrDefault(toolCall.path("function").get("name"), ""));
                String arguments = toolCall.path("function").path("arguments").asText("{}");
                block.put("input", parseArguments(arguments));
                content.add(block);
            }
        }

        if (content.isEmpty()) {
            content.add(buildTextBlock(""));
        }
        return content;
    }

    private Map<String, Object> buildUsage(JsonNode usageNode) {
        Map<String, Object> usage = new LinkedHashMap<>();
        if (usageNode == null || usageNode.isNull()) {
            usage.put("input_tokens", 0);
            usage.put("output_tokens", 0);
            return usage;
        }

        usage.put("input_tokens", usageNode.path("prompt_tokens").asInt(0));
        usage.put("output_tokens", usageNode.path("completion_tokens").asInt(0));
        JsonNode promptDetails = usageNode.get("prompt_tokens_details");
        if (promptDetails != null && promptDetails.hasNonNull("cached_tokens")) {
            usage.put("cache_read_input_tokens", promptDetails.get("cached_tokens").asInt(0));
        }
        usage.put("cache_creation_input_tokens", promptDetails == null ? 0 : promptDetails.path("cache_write_tokens").asInt(0));
        return usage;
    }

    private Map<String, Object> parseArguments(String arguments) throws JacksonException {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(arguments, MAP_TYPE);
    }

    private Map<String, Object> buildTextBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    private JsonNode extractFirstChoice(JsonNode root) {
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return null;
        }
        return choices.get(0);
    }

    private String extractFinishReason(JsonNode root) {
        JsonNode choice = extractFirstChoice(root);
        return choice != null && choice.hasNonNull("finish_reason") ? choice.get("finish_reason").asText() : null;
    }

    private String convertFinishReason(String finishReason) {
        if (finishReason == null) {
            return null;
        }
        return switch (finishReason) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "tool_calls", "function_call" -> "tool_use";
            case "content_filter" -> "refusal";
            default -> finishReason;
        };
    }

    private String textOrDefault(JsonNode node, String defaultValue) {
        return node == null || node.isNull() ? defaultValue : node.asText(defaultValue);
    }

    private static final class StreamState {
        private boolean messageStarted;
        private boolean completed;
        private String messageId;
        private String model;
        private Integer activeTextBlockIndex;
        private int nextBlockIndex;
        private String stopReason;
        private long inputTokens;
        private long outputTokens;
        private long cacheReadInputTokens;
        private long cacheCreationInputTokens;
        private final Map<Integer, ToolState> toolStates = new LinkedHashMap<>();
        private final List<Integer> closedBlockIndexes = new ArrayList<>();
    }

    private static final class ToolState {
        private final int blockIndex;
        private boolean started;
        private String id;
        private String name;

        private ToolState(int blockIndex) {
            this.blockIndex = blockIndex;
        }
    }
}
