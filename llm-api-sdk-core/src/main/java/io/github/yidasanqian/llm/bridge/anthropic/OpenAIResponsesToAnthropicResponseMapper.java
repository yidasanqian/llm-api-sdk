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
public class OpenAIResponsesToAnthropicResponseMapper implements BridgeStreamMapper {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public String mapNonStream(String openAIResponse) {
        try {
            JsonNode root = objectMapper.readTree(openAIResponse);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", resolveAnthropicMessageId(root));
            response.put("type", "message");
            response.put("role", "assistant");
            response.put("model", textOrDefault(root.get("model"), ""));
            response.put("content", buildNonStreamContent(root.path("output")));
            response.put("stop_reason", determineStopReason(root));
            response.put("stop_sequence", null);
            response.put("usage", buildUsage(root.path("usage")));
            return objectMapper.writeValueAsString(response);
        } catch (JacksonException e) {
            throw ProtocolConversionException.of(
                    "Failed to map OpenAI responses payload: " + e.getMessage(), 500);
        }
    }

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
                    throw ProtocolConversionException.of("Responses stream ended without a terminal event", 502);
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
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(data);
            List<SseEvent<String>> events = new ArrayList<>();
            String type = root.path("type").asText("");

            switch (type) {
                case "response.created", "response.in_progress" -> {
                    JsonNode responseNode = root.path("response");
                    updateResponseMetadata(responseNode, state);
                    ensureMessageStart(state, events);
                }
                case "response.output_item.added" -> {
                    ensureMessageStart(state, events);
                    handleOutputItem(root.path("item"), state, events, true);
                }
                case "response.content_part.added" -> {
                    ensureMessageStart(state, events);
                    handleContentPart(root.path("part"), root.path("item_id").asText(""), root.path("content_index").asInt(0), state, events, true);
                }
                case "response.output_text.delta" -> {
                    ensureMessageStart(state, events);
                    appendTextDelta(root.path("item_id").asText(""), root.path("content_index").asInt(0), root.path("delta").asText(""), state, events);
                }
                case "response.output_text.done" -> {
                    ensureMessageStart(state, events);
                    appendTextIfNeeded(root.path("item_id").asText(""), root.path("content_index").asInt(0), root.path("text").asText(""), state, events);
                }
                case "response.function_call_arguments.delta" -> {
                    ensureMessageStart(state, events);
                    appendFunctionArgumentsDelta(root, state, events, root.path("delta").asText(""));
                }
                case "response.function_call_arguments.done" -> {
                    ensureMessageStart(state, events);
                    appendFunctionArgumentsIfNeeded(root, state, events, root.path("arguments").asText(""));
                }
                case "response.output_item.done" -> {
                    ensureMessageStart(state, events);
                    handleOutputItem(root.path("item"), state, events, false);
                }
                case "response.completed", "response.incomplete" -> {
                    JsonNode responseNode = root.path("response");
                    String status = responseNode.path("status").asText("");
                    if ("in_progress".equals(status) || "queued".equals(status)) break;
                    updateResponseMetadata(responseNode, state);
                    ensureMessageStart(state, events);
                    JsonNode outputNode = responseNode.path("output");
                    if (outputNode.isArray()) {
                        for (JsonNode itemNode : outputNode) {
                            handleOutputItem(itemNode, state, events, false);
                        }
                    }
                    state.stopReason = determineStopReason(responseNode);
                    events.addAll(buildStopEvents(state));
                }
                default -> {
                }
            }
            return events;
        } catch (JacksonException e) {
            log.warn("Failed to parse OpenAI responses stream chunk: {}", e.getMessage());
            return List.of();
        }
    }

    private void updateResponseMetadata(JsonNode responseNode, StreamState state) {
        if (responseNode == null || responseNode.isMissingNode()) {
            return;
        }
        state.responseId = textOrDefault(responseNode.get("id"), state.responseId);
        state.model = textOrDefault(responseNode.get("model"), state.model);
        if (state.messageId == null || state.messageId.isBlank()) {
            state.messageId = resolveAnthropicMessageId(responseNode);
        }
        updateUsage(responseNode.path("usage"), state);
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

    private void handleOutputItem(
            JsonNode itemNode,
            StreamState state,
            List<SseEvent<String>> events,
            boolean startOnly
    ) throws JacksonException {
        if (itemNode == null || itemNode.isMissingNode()) {
            return;
        }
        String itemType = itemNode.path("type").asText("");
        switch (itemType) {
            case "message" -> {
                if (state.messageId == null || state.messageId.isBlank()) {
                    state.messageId = textOrDefault(itemNode.get("id"), state.responseId);
                }
                if (!startOnly) {
                    JsonNode content = itemNode.path("content");
                    if (content.isArray()) {
                        for (int i = 0; i < content.size(); i++) {
                            handleContentPart(content.get(i), itemNode.path("id").asText(""), i, state, events, false);
                        }
                    }
                }
            }
            case "function_call" -> {
                ToolState toolState = ensureToolState(itemNode, state, events);
                if (!startOnly) {
                    appendFunctionArguments(toolState, itemNode.path("arguments").asText(""), events);
                }
            }
            case "reasoning" -> {
            }
            default ->
                    log.warn("Ignoring unsupported OpenAI responses output item type '{}' while bridging to Anthropic.", itemType);
        }
    }

    private void handleContentPart(
            JsonNode partNode,
            String itemId,
            int contentIndex,
            StreamState state,
            List<SseEvent<String>> events,
            boolean startOnly
    ) throws JacksonException {
        if (partNode == null || partNode.isMissingNode()) {
            return;
        }
        String partType = partNode.path("type").asText("");
        if (!isTextualPart(partType)) {
            return;
        }

        TextBlockState textBlockState = ensureTextBlockState(itemId, contentIndex, state, events);
        if (startOnly) {
            return;
        }
        appendTextIfNeeded(itemId, contentIndex, extractPartText(partNode), state, events);
    }

    private void appendTextDelta(
            String itemId,
            int contentIndex,
            String deltaText,
            StreamState state,
            List<SseEvent<String>> events
    ) throws JacksonException {
        if (deltaText == null || deltaText.isEmpty()) {
            return;
        }
        TextBlockState textBlockState = ensureTextBlockState(itemId, contentIndex, state, events);
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_delta");
        event.put("index", textBlockState.blockIndex);

        ObjectNode delta = objectMapper.createObjectNode();
        delta.put("type", "text_delta");
        delta.put("text", deltaText);
        event.set("delta", delta);

        events.add(new AnthropicSseEvent("content_block_delta", objectMapper.writeValueAsString(event)).toSseEvent());
        textBlockState.hasDelta = true;
    }

    private void appendTextIfNeeded(
            String itemId,
            int contentIndex,
            String text,
            StreamState state,
            List<SseEvent<String>> events
    ) throws JacksonException {
        if (text == null || text.isEmpty()) {
            return;
        }
        TextBlockState textBlockState = ensureTextBlockState(itemId, contentIndex, state, events);
        if (textBlockState.hasDelta) {
            return;
        }
        appendTextDelta(itemId, contentIndex, text, state, events);
    }

    private void appendFunctionArgumentsDelta(
            JsonNode root,
            StreamState state,
            List<SseEvent<String>> events,
            String delta
    ) throws JacksonException {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        ToolState toolState = ensureToolStateForEvent(root, state, events);
        emitFunctionArgumentsDelta(toolState, delta, events);
        toolState.hasArgumentsDelta = true;
    }

    private void appendFunctionArgumentsIfNeeded(
            JsonNode root,
            StreamState state,
            List<SseEvent<String>> events,
            String arguments
    ) throws JacksonException {
        if (arguments == null || arguments.isEmpty()) {
            return;
        }
        ToolState toolState = ensureToolStateForEvent(root, state, events);
        appendFunctionArguments(toolState, arguments, events);
    }

    private void appendFunctionArguments(
            ToolState toolState,
            String arguments,
            List<SseEvent<String>> events
    ) throws JacksonException {
        if (arguments == null || arguments.isEmpty() || toolState.hasArgumentsDelta) {
            return;
        }
        emitFunctionArgumentsDelta(toolState, arguments, events);
        toolState.hasArgumentsDelta = true;
    }

    private void emitFunctionArgumentsDelta(
            ToolState toolState,
            String arguments,
            List<SseEvent<String>> events
    ) throws JacksonException {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_delta");
        event.put("index", toolState.blockIndex);

        ObjectNode delta = objectMapper.createObjectNode();
        delta.put("type", "input_json_delta");
        delta.put("partial_json", arguments);
        event.set("delta", delta);

        events.add(new AnthropicSseEvent("content_block_delta", objectMapper.writeValueAsString(event)).toSseEvent());
    }

    private TextBlockState ensureTextBlockState(
            String itemId,
            int contentIndex,
            StreamState state,
            List<SseEvent<String>> events
    ) throws JacksonException {
        String key = textBlockKey(itemId, contentIndex);
        TextBlockState textBlockState = state.textBlockStates.computeIfAbsent(
                key,
                ignored -> new TextBlockState(state.nextBlockIndex++)
        );
        if (textBlockState.started) {
            return textBlockState;
        }
        events.add(buildTextBlockStartEvent(textBlockState.blockIndex));
        textBlockState.started = true;
        return textBlockState;
    }

    private ToolState ensureToolState(JsonNode itemNode, StreamState state, List<SseEvent<String>> events) throws JacksonException {
        return ensureToolState(toolKey(itemNode), itemNode, state, events);
    }

    private ToolState ensureToolStateForEvent(JsonNode eventNode, StreamState state, List<SseEvent<String>> events) throws JacksonException {
        return ensureToolState(toolKeyFromEvent(eventNode), eventNode, state, events);
    }

    private ToolState ensureToolState(
            String key,
            JsonNode metadataNode,
            StreamState state,
            List<SseEvent<String>> events
    ) throws JacksonException {
        ToolState toolState = state.toolStates.computeIfAbsent(
                key,
                ignored -> new ToolState(state.nextBlockIndex++)
        );
        updateToolStateMetadata(metadataNode, toolState);
        if (toolState.started) {
            return toolState;
        }
        events.add(buildToolUseBlockStartEvent(toolState));
        toolState.started = true;
        return toolState;
    }

    private void updateToolStateMetadata(JsonNode itemNode, ToolState toolState) {
        toolState.callId = textOrDefault(itemNode.get("call_id"), toolState.callId);
        toolState.name = textOrDefault(itemNode.get("name"), toolState.name);
    }

    private void updateUsage(JsonNode usageNode, StreamState state) {
        if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
            return;
        }
        state.inputTokens = usageNode.path("input_tokens").asLong(state.inputTokens);
        state.outputTokens = usageNode.path("output_tokens").asLong(state.outputTokens);
        JsonNode inputDetails = usageNode.path("input_tokens_details");
        if (inputDetails.hasNonNull("cached_tokens")) {
            state.cacheReadInputTokens = inputDetails.get("cached_tokens").asLong(0);
        }
        if (inputDetails.hasNonNull("cache_write_tokens")) {
            state.cacheCreationInputTokens = inputDetails.get("cache_write_tokens").asLong(0);
        }
    }

    private List<SseEvent<String>> buildStopEvents(StreamState state) {
        if (!state.messageStarted || state.completed) {
            return List.of();
        }
        state.completed = true;

        List<SseEvent<String>> events = new ArrayList<>();
        try {
            state.textBlockStates.values().stream()
                    .sorted(Comparator.comparingInt(textState -> textState.blockIndex))
                    .filter(textState -> textState.started && !state.closedBlockIndexes.contains(textState.blockIndex))
                    .forEach(textState -> {
                        events.add(buildContentBlockStop(textState.blockIndex));
                        state.closedBlockIndexes.add(textState.blockIndex);
                    });

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
            log.warn("Failed to build anthropic stop events from OpenAI responses stream: {}", e.getMessage());
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

    private SseEvent<String> buildTextBlockStartEvent(int blockIndex) throws JacksonException {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_start");
        event.put("index", blockIndex);

        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "text");
        block.put("text", "");
        event.set("content_block", block);
        return new AnthropicSseEvent("content_block_start", objectMapper.writeValueAsString(event)).toSseEvent();
    }

    private SseEvent<String> buildToolUseBlockStartEvent(ToolState toolState) throws JacksonException {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_start");
        event.put("index", toolState.blockIndex);

        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "tool_use");
        block.put("id", toolState.callId == null || toolState.callId.isBlank() ? "toolu_" + toolState.blockIndex : toolState.callId);
        block.put("name", toolState.name == null || toolState.name.isBlank() ? "unknown_tool" : toolState.name);
        block.set("input", objectMapper.createObjectNode());
        event.set("content_block", block);
        return new AnthropicSseEvent("content_block_start", objectMapper.writeValueAsString(event)).toSseEvent();
    }

    private List<Map<String, Object>> buildNonStreamContent(JsonNode outputNode) throws JacksonException {
        List<Map<String, Object>> content = new ArrayList<>();
        if (outputNode != null && outputNode.isArray()) {
            for (JsonNode itemNode : outputNode) {
                String itemType = itemNode.path("type").asText("");
                switch (itemType) {
                    case "message" -> appendMessageBlocks(itemNode.path("content"), content);
                    case "function_call" -> content.add(buildToolUseBlock(itemNode));
                    case "reasoning" -> {
                    }
                    default ->
                            log.warn("Ignoring unsupported OpenAI responses output item type '{}' in non-stream mapping.", itemType);
                }
            }
        }

        if (content.isEmpty()) {
            content.add(buildTextBlock(""));
        }
        return content;
    }

    private void appendMessageBlocks(JsonNode contentNode, List<Map<String, Object>> content) {
        if (contentNode == null || !contentNode.isArray()) {
            return;
        }
        for (JsonNode partNode : contentNode) {
            String partType = partNode.path("type").asText("");
            if (!isTextualPart(partType)) {
                continue;
            }
            String text = extractPartText(partNode);
            if (!text.isEmpty()) {
                content.add(buildTextBlock(text));
            }
        }
    }

    private Map<String, Object> buildToolUseBlock(JsonNode functionCallNode) throws JacksonException {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_use");
        block.put("id", textOrDefault(functionCallNode.get("call_id"), textOrDefault(functionCallNode.get("id"), "")));
        block.put("name", textOrDefault(functionCallNode.get("name"), ""));
        block.put("input", parseArguments(functionCallNode.path("arguments").asText("{}")));
        return block;
    }

    private Map<String, Object> buildUsage(JsonNode usageNode) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", usageNode.path("input_tokens").asInt(0));
        usage.put("output_tokens", usageNode.path("output_tokens").asInt(0));
        JsonNode inputDetails = usageNode.path("input_tokens_details");
        if (inputDetails.hasNonNull("cached_tokens")) {
            usage.put("cache_read_input_tokens", inputDetails.get("cached_tokens").asInt(0));
        }
        usage.put("cache_creation_input_tokens", inputDetails.path("cache_write_tokens").asInt(0));
        return usage;
    }

    private String determineStopReason(JsonNode responseNode) {
        JsonNode outputNode = responseNode.path("output");
        if (containsFunctionCall(outputNode)) {
            return "tool_use";
        }
        if (containsRefusal(outputNode)) {
            return "refusal";
        }
        String incompleteReason = responseNode.path("incomplete_details").path("reason").asText("");
        if ("max_output_tokens".equals(incompleteReason) || "max_tokens".equals(incompleteReason)) {
            return "max_tokens";
        }
        return "end_turn";
    }

    private boolean containsFunctionCall(JsonNode outputNode) {
        if (outputNode == null || !outputNode.isArray()) {
            return false;
        }
        for (JsonNode itemNode : outputNode) {
            if ("function_call".equals(itemNode.path("type").asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsRefusal(JsonNode outputNode) {
        if (outputNode == null || !outputNode.isArray()) {
            return false;
        }
        for (JsonNode itemNode : outputNode) {
            if (!"message".equals(itemNode.path("type").asText())) {
                continue;
            }
            JsonNode contentNode = itemNode.path("content");
            if (!contentNode.isArray()) {
                continue;
            }
            for (JsonNode partNode : contentNode) {
                if ("refusal".equals(partNode.path("type").asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isTextualPart(String partType) {
        return "output_text".equals(partType) || "refusal".equals(partType);
    }

    private String extractPartText(JsonNode partNode) {
        String partType = partNode.path("type").asText("");
        if ("refusal".equals(partType)) {
            return textOrDefault(partNode.get("refusal"), textOrDefault(partNode.get("text"), ""));
        }
        return textOrDefault(partNode.get("text"), "");
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

    private String resolveAnthropicMessageId(JsonNode responseNode) {
        JsonNode outputNode = responseNode.path("output");
        if (outputNode.isArray()) {
            for (JsonNode itemNode : outputNode) {
                if ("message".equals(itemNode.path("type").asText()) && itemNode.hasNonNull("id")) {
                    return itemNode.get("id").asText();
                }
            }
        }
        return textOrDefault(responseNode.get("id"), "msg_" + System.currentTimeMillis());
    }

    private String toolKey(JsonNode itemNode) {
        String itemId = textOrDefault(itemNode.get("id"), "");
        if (!itemId.isBlank()) {
            return itemId;
        }
        String callId = textOrDefault(itemNode.get("call_id"), "");
        if (!callId.isBlank()) {
            return callId;
        }
        return "tool_" + System.nanoTime();
    }

    private String toolKeyFromEvent(JsonNode eventNode) {
        String itemId = textOrDefault(eventNode.get("item_id"), "");
        if (!itemId.isBlank()) {
            return itemId;
        }
        String outputIndex = eventNode.has("output_index") ? eventNode.get("output_index").asText("") : "";
        if (!outputIndex.isBlank()) {
            return "output_index_" + outputIndex;
        }
        return toolKey(eventNode);
    }

    private String textBlockKey(String itemId, int contentIndex) {
        return itemId + ":" + contentIndex;
    }

    private String textOrDefault(JsonNode node, String defaultValue) {
        return node == null || node.isNull() ? defaultValue : node.asText(defaultValue);
    }

    private static final class StreamState {
        private boolean messageStarted;
        private boolean completed;
        private String responseId;
        private String messageId;
        private String model;
        private String stopReason;
        private long inputTokens;
        private long outputTokens;
        private long cacheReadInputTokens;
        private long cacheCreationInputTokens;
        private int nextBlockIndex;
        private final Map<String, TextBlockState> textBlockStates = new LinkedHashMap<>();
        private final Map<String, ToolState> toolStates = new LinkedHashMap<>();
        private final List<Integer> closedBlockIndexes = new ArrayList<>();
    }

    private static final class TextBlockState {
        private final int blockIndex;
        private boolean started;
        private boolean hasDelta;

        private TextBlockState(int blockIndex) {
            this.blockIndex = blockIndex;
        }
    }

    private static final class ToolState {
        private final int blockIndex;
        private boolean started;
        private boolean hasArgumentsDelta;
        private String callId;
        private String name;

        private ToolState(int blockIndex) {
            this.blockIndex = blockIndex;
        }
    }
}
