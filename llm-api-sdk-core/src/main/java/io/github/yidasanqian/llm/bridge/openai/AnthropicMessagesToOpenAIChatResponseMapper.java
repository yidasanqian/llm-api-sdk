package io.github.yidasanqian.llm.bridge.openai;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.yidasanqian.llm.error.ProtocolConversionException;
import io.github.yidasanqian.llm.bridge.BridgeStreamMapper;
import io.github.yidasanqian.llm.bridge.BridgeStreamSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.yidasanqian.llm.protocol.SseEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

@Slf4j
@RequiredArgsConstructor
public class AnthropicMessagesToOpenAIChatResponseMapper implements BridgeStreamMapper {

    private final ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Non-streaming
    // -------------------------------------------------------------------------

    public String mapNonStream(String anthropicResponse) {
        try {
            JsonNode root = objectMapper.readTree(anthropicResponse);

            ObjectNode response = objectMapper.createObjectNode();
            response.put("id", "chatcmpl-" + textOrDefault(root.get("id"), String.valueOf(System.currentTimeMillis())));
            response.put("object", "chat.completion");
            response.put("created", Instant.now().getEpochSecond());
            response.put("model", textOrDefault(root.get("model"), ""));

            // choices
            ArrayNode choices = objectMapper.createArrayNode();
            ObjectNode choice = objectMapper.createObjectNode();
            choice.put("index", 0);
            choice.set("message", buildNonStreamMessage(root.get("content")));
            choice.put("finish_reason", mapStopReason(textOrDefault(root.get("stop_reason"), null)));
            choices.add(choice);
            response.set("choices", choices);

            // usage
            response.set("usage", buildNonStreamUsage(root.get("usage")));

            return objectMapper.writeValueAsString(response);
        } catch (JacksonException e) {
            throw ProtocolConversionException.of(
                    "Failed to map Anthropic messages response: " + e.getMessage(), 500);
        }
    }

    private ObjectNode buildNonStreamMessage(JsonNode contentArray) throws JacksonException {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "assistant");

        StringBuilder textBuilder = new StringBuilder();
        StringBuilder thinkingBuilder = new StringBuilder();
        ArrayNode toolCallsArray = objectMapper.createArrayNode();

        if (contentArray != null && contentArray.isArray()) {
            for (JsonNode block : contentArray) {
                String type = textOrDefault(block.get("type"), "");
                switch (type) {
                    case "text" -> textBuilder.append(textOrDefault(block.get("text"), ""));
                    case "thinking" -> thinkingBuilder.append(textOrDefault(block.get("thinking"), ""));
                    case "redacted_thinking" -> thinkingBuilder.append(textOrDefault(block.get("data"), ""));
                    case "tool_use" -> {
                        ObjectNode toolCall = objectMapper.createObjectNode();
                        toolCall.put("id", textOrDefault(block.get("id"), ""));
                        toolCall.put("type", "function");

                        ObjectNode function = objectMapper.createObjectNode();
                        function.put("name", textOrDefault(block.get("name"), ""));
                        JsonNode inputNode = block.get("input");
                        String arguments = (inputNode != null && !inputNode.isNull())
                                ? objectMapper.writeValueAsString(inputNode)
                                : "{}";
                        function.put("arguments", arguments);
                        toolCall.set("function", function);
                        toolCallsArray.add(toolCall);
                    }
                    default -> { /* ignore unknown types */ }
                }
            }
        }

        // content: null if no text; also null if tool_calls present and text is empty
        String textContent = textBuilder.toString();
        boolean hasToolCalls = !toolCallsArray.isEmpty();
        if (textContent.isEmpty() || (hasToolCalls && textContent.isEmpty())) {
            message.putNull("content");
        } else {
            message.put("content", textContent);
        }

        // reasoning_content: only if non-empty
        String thinkingContent = thinkingBuilder.toString();
        if (!thinkingContent.isEmpty()) {
            message.put("reasoning_content", thinkingContent);
        }

        // tool_calls: only if non-empty
        if (hasToolCalls) {
            message.set("tool_calls", toolCallsArray);
        }

        return message;
    }

    private ObjectNode buildNonStreamUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isNull()) {
            return buildOpenAiUsage(new UsageTokens(0, 0, 0, 0));
        }

        long inputTokens = usageNode.path("input_tokens").asLong(0);
        long outputTokens = usageNode.path("output_tokens").asLong(0);
        long cacheRead = usageNode.path("cache_read_input_tokens").asLong(0);
        long cacheCreation = cacheCreationInputTokens(usageNode);

        return buildOpenAiUsage(new UsageTokens(inputTokens, outputTokens, cacheRead, cacheCreation));
    }

    private ObjectNode buildOpenAiUsage(UsageTokens tokens) {
        long promptTokens = tokens.inputTokens() + tokens.cacheReadTokens() + tokens.cacheCreationTokens();
        ObjectNode usage = objectMapper.createObjectNode();

        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", tokens.outputTokens());
        usage.put("total_tokens", promptTokens + tokens.outputTokens());

        if (tokens.cacheReadTokens() > 0 || tokens.cacheCreationTokens() > 0) {
            ObjectNode promptDetails = objectMapper.createObjectNode();
            if (tokens.cacheReadTokens() > 0) {
                promptDetails.put("cached_tokens", tokens.cacheReadTokens());
            }
            if (tokens.cacheCreationTokens() > 0) {
                promptDetails.put("cache_write_tokens", tokens.cacheCreationTokens());
            }
            usage.set("prompt_tokens_details", promptDetails);
        }

        return usage;
    }

    private long cacheCreationInputTokens(JsonNode usageNode) {
        Long cacheCreation = findCacheCreationInputTokens(usageNode);
        return cacheCreation != null ? cacheCreation : 0;
    }

    private Long findCacheCreationInputTokens(JsonNode usageNode) {
        JsonNode legacyTotal = usageNode.get("cache_creation_input_tokens");
        if (legacyTotal != null && !legacyTotal.isNull()) {
            return legacyTotal.asLong(0);
        }

        JsonNode cacheCreation = usageNode.get("cache_creation");
        if (cacheCreation == null || cacheCreation.isNull()) {
            return null;
        }

        JsonNode fiveMinuteTokens = cacheCreation.get("ephemeral_5m_input_tokens");
        JsonNode oneHourTokens = cacheCreation.get("ephemeral_1h_input_tokens");
        if ((fiveMinuteTokens == null || fiveMinuteTokens.isNull())
                && (oneHourTokens == null || oneHourTokens.isNull())) {
            return null;
        }
        return (fiveMinuteTokens != null ? fiveMinuteTokens.asLong(0) : 0)
                + (oneHourTokens != null ? oneHourTokens.asLong(0) : 0);
    }

    // -------------------------------------------------------------------------
    // Streaming
    // -------------------------------------------------------------------------

    @Override
    public Predicate<SseEvent<String>> createStreamReadiness() {
        StreamState state = new StreamState();
        return sse -> !mapChunk(sse, state).isEmpty();
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
                    throw ProtocolConversionException.of("Anthropic stream ended without message_stop", 502);
                }
                return List.of();
            }
        };
    }

    private List<SseEvent<String>> mapChunk(SseEvent<String> sse, StreamState state) {
        if (sse == null || state.completed) {
            return List.of();
        }

        String eventType = sse.event(); // Anthropic uses event: field
        String data = sse.data();

        // ignore ping
        if ("ping".equals(eventType)) {
            return List.of();
        }

        // message_stop → emit [DONE]
        if ("message_stop".equals(eventType)) {
            state.completed = true;
            return List.of(SseEvent.<String>builder().data("[DONE]").build());
        }

        if (data == null || data.isBlank()) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(data);
            List<SseEvent<String>> events = new ArrayList<>();

            switch (eventType == null ? "" : eventType) {
                case "message_start" -> handleMessageStart(root, state, events);
                case "content_block_start" -> handleContentBlockStart(root, state, events);
                case "content_block_delta" -> handleContentBlockDelta(root, state, events);
                case "content_block_stop" -> { /* no output needed */ }
                case "message_delta" -> handleMessageDelta(root, state, events);
                default -> { /* ignore unknown events */ }
            }

            return events;
        } catch (JacksonException e) {
            log.warn("Failed to parse Anthropic stream chunk (event={}): {}", eventType, e.getMessage());
            return List.of();
        }
    }

    private void handleMessageStart(JsonNode root, StreamState state, List<SseEvent<String>> events) throws JacksonException {
        JsonNode message = root.get("message");
        if (message != null) {
            state.messageId = textOrDefault(message.get("id"), state.messageId);
            state.model = textOrDefault(message.get("model"), state.model);

            // Extract initial usage if present
            JsonNode usage = message.get("usage");
            if (usage != null && !usage.isNull()) {
                updateStreamUsage(state, usage);
            }
        }

        // Emit initial chunk with role: assistant
        ObjectNode delta = objectMapper.createObjectNode();
        delta.put("role", "assistant");
        events.add(buildChunk(state, delta, null, false));
    }

    private void handleContentBlockStart(JsonNode root, StreamState state, List<SseEvent<String>> events) throws JacksonException {
        int blockIndex = root.path("index").asInt(0);
        JsonNode contentBlock = root.get("content_block");
        if (contentBlock == null) {
            return;
        }
        String type = textOrDefault(contentBlock.get("type"), "");

        switch (type) {
            case "text" -> state.activeTextBlockIndex = blockIndex;
            case "thinking", "redacted_thinking" -> state.activeThinkingBlockIndex = blockIndex;
            case "tool_use" -> {
                // Emit tool_calls delta chunk
                int toolIndex = state.toolIndex++;
                state.blockIndexToToolIndex.put(blockIndex, toolIndex);

                ObjectNode delta = objectMapper.createObjectNode();
                ArrayNode toolCallsArray = objectMapper.createArrayNode();

                ObjectNode toolCall = objectMapper.createObjectNode();
                toolCall.put("index", toolIndex);
                toolCall.put("id", textOrDefault(contentBlock.get("id"), ""));
                toolCall.put("type", "function");

                ObjectNode function = objectMapper.createObjectNode();
                function.put("name", textOrDefault(contentBlock.get("name"), ""));
                function.put("arguments", "");
                toolCall.set("function", function);

                toolCallsArray.add(toolCall);
                delta.set("tool_calls", toolCallsArray);
                events.add(buildChunk(state, delta, null, false));
            }
            default -> { /* ignore unknown block types */ }
        }
    }

    private void handleContentBlockDelta(JsonNode root, StreamState state, List<SseEvent<String>> events) throws JacksonException {
        int blockIndex = root.path("index").asInt(0);
        JsonNode delta = root.get("delta");
        if (delta == null) {
            return;
        }
        String deltaType = textOrDefault(delta.get("type"), "");

        switch (deltaType) {
            case "text_delta" -> {
                String text = textOrDefault(delta.get("text"), "");
                if (!text.isEmpty()) {
                    ObjectNode chunkDelta = objectMapper.createObjectNode();
                    chunkDelta.put("content", text);
                    events.add(buildChunk(state, chunkDelta, null, false));
                }
            }
            case "input_json_delta" -> {
                String partialJson = textOrDefault(delta.get("partial_json"), "");
                Integer toolIndex = state.blockIndexToToolIndex.get(blockIndex);
                if (toolIndex != null && !partialJson.isEmpty()) {
                    ObjectNode chunkDelta = objectMapper.createObjectNode();
                    ArrayNode toolCallsArray = objectMapper.createArrayNode();

                    ObjectNode toolCall = objectMapper.createObjectNode();
                    toolCall.put("index", toolIndex);

                    ObjectNode function = objectMapper.createObjectNode();
                    function.put("arguments", partialJson);
                    toolCall.set("function", function);

                    toolCallsArray.add(toolCall);
                    chunkDelta.set("tool_calls", toolCallsArray);
                    events.add(buildChunk(state, chunkDelta, null, false));
                }
            }
            case "thinking_delta" -> {
                String thinking = textOrDefault(delta.get("thinking"), "");
                if (!thinking.isEmpty()) {
                    ObjectNode chunkDelta = objectMapper.createObjectNode();
                    chunkDelta.put("reasoning_content", thinking);
                    events.add(buildChunk(state, chunkDelta, null, false));
                }
            }
            default -> { /* ignore unknown delta types */ }
        }
    }

    private void handleMessageDelta(JsonNode root, StreamState state, List<SseEvent<String>> events) throws JacksonException {
        JsonNode delta = root.get("delta");
        String stopReason = null;
        if (delta != null && !delta.isNull()) {
            stopReason = mapStopReason(textOrDefault(delta.get("stop_reason"), null));
        }

        // Extract usage from message_delta
        JsonNode usageNode = root.get("usage");
        if (usageNode != null && !usageNode.isNull()) {
            updateStreamUsage(state, usageNode);
        }

        // Emit final chunk with finish_reason and usage
        ObjectNode chunkDelta = objectMapper.createObjectNode();
        // empty delta object for final chunk

        ObjectNode chunk = buildChunkNode(state, chunkDelta, stopReason);

        // Add usage to final chunk
        chunk.set("usage", buildOpenAiUsage(new UsageTokens(
                state.inputTokens,
                state.outputTokens,
                state.cacheReadTokens,
                state.cacheCreationTokens)));

        events.add(SseEvent.<String>builder()
                .data(objectMapper.writeValueAsString(chunk))
                .build());
    }

    // -------------------------------------------------------------------------
    // Chunk builders
    // -------------------------------------------------------------------------

    private SseEvent<String> buildChunk(StreamState state, ObjectNode delta, String finishReason, boolean withUsage) throws JacksonException {
        ObjectNode chunk = buildChunkNode(state, delta, finishReason);
        return SseEvent.<String>builder()
                .data(objectMapper.writeValueAsString(chunk))
                .build();
    }

    private ObjectNode buildChunkNode(StreamState state, ObjectNode delta, String finishReason) {
        ObjectNode chunk = objectMapper.createObjectNode();
        chunk.put("id", "chatcmpl-" + (state.messageId != null ? state.messageId : String.valueOf(System.currentTimeMillis())));
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", state.created);
        chunk.put("model", state.model != null ? state.model : "");

        ArrayNode choices = objectMapper.createArrayNode();
        ObjectNode choice = objectMapper.createObjectNode();
        choice.put("index", 0);
        choice.set("delta", delta);
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        } else {
            choice.putNull("finish_reason");
        }
        choices.add(choice);
        chunk.set("choices", choices);

        return chunk;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String mapStopReason(String stopReason) {
        if (stopReason == null) {
            return null;
        }
        return switch (stopReason) {
            case "end_turn" -> "stop";
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            default -> stopReason;
        };
    }

    private String textOrDefault(JsonNode node, String defaultValue) {
        return node == null || node.isNull() ? defaultValue : node.asText(defaultValue);
    }

    private void updateStreamUsage(StreamState state, JsonNode usageNode) {
        JsonNode inputTokens = usageNode.get("input_tokens");
        if (inputTokens != null && !inputTokens.isNull()) {
            state.inputTokens = inputTokens.asLong(0);
        }

        JsonNode outputTokens = usageNode.get("output_tokens");
        if (outputTokens != null && !outputTokens.isNull()) {
            state.outputTokens = outputTokens.asLong(0);
        }

        JsonNode cacheReadTokens = usageNode.get("cache_read_input_tokens");
        if (cacheReadTokens != null && !cacheReadTokens.isNull()) {
            state.cacheReadTokens = cacheReadTokens.asLong(0);
        }

        Long cacheCreationTokens = findCacheCreationInputTokens(usageNode);
        if (cacheCreationTokens != null) {
            state.cacheCreationTokens = cacheCreationTokens;
        }
    }

    // -------------------------------------------------------------------------
    // StreamState
    // -------------------------------------------------------------------------

    private static final class StreamState {
        boolean completed;
        String messageId;
        String model;
        long created = Instant.now().getEpochSecond();
        long inputTokens;
        long outputTokens;
        long cacheReadTokens;
        long cacheCreationTokens;
        Integer activeTextBlockIndex;
        Integer activeThinkingBlockIndex;
        int toolIndex;
        // maps Anthropic block index → OpenAI tool_calls index
        final java.util.Map<Integer, Integer> blockIndexToToolIndex = new java.util.LinkedHashMap<>();
    }

    private record UsageTokens(long inputTokens, long outputTokens, long cacheReadTokens, long cacheCreationTokens) {
    }
}
