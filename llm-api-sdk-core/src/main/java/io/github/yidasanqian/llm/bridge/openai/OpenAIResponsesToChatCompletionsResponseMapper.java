package io.github.yidasanqian.llm.bridge.openai;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.github.yidasanqian.llm.error.ProtocolConversionException;
import io.github.yidasanqian.llm.bridge.BridgeStreamMapper;
import io.github.yidasanqian.llm.bridge.BridgeStreamSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.yidasanqian.llm.protocol.SseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 将 OpenAI Responses API 响应映射为 Chat Completions 格式。
 *
 * <p>支持非流式（JSON）和流式（SSE 命名事件 -> 匿名 data chunk）两种模式。
 */
@Slf4j
@RequiredArgsConstructor
public class OpenAIResponsesToChatCompletionsResponseMapper implements BridgeStreamMapper {

    private final ObjectMapper objectMapper;

    // ──────────────────────────────── 非流式 ────────────────────────────────

    public String mapNonStream(String responsesBody) {
        try {
            JsonNode root = objectMapper.readTree(responsesBody);
            Map<String, Object> chat = new LinkedHashMap<>();
            chat.put("id", textOrDefault(root.get("id"), "chatcmpl-" + System.currentTimeMillis()));
            chat.put("object", "chat.completion");
            chat.put("created", root.path("created_at").asLong(System.currentTimeMillis() / 1000L));
            chat.put("model", textOrDefault(root.get("model"), ""));
            chat.put("choices", buildNonStreamChoices(root));
            chat.put("usage", buildChatUsage(root.path("usage")));
            return objectMapper.writeValueAsString(chat);
        } catch (JacksonException e) {
            throw ProtocolConversionException.of(
                    "Failed to map OpenAI Responses to Chat Completions: " + e.getMessage(), 500);
        }
    }

    private List<Map<String, Object>> buildNonStreamChoices(JsonNode root) {
        List<Map<String, Object>> choices = new ArrayList<>();
        JsonNode outputNode = root.path("output");

        String textContent = extractTextContent(outputNode);
        List<Map<String, Object>> toolCalls = extractToolCalls(outputNode);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        if (!textContent.isEmpty()) {
            message.put("content", textContent);
        } else if (toolCalls.isEmpty()) {
            message.put("content", "");
        } else {
            message.put("content", null);
        }
        if (!toolCalls.isEmpty()) {
            message.put("tool_calls", toolCalls);
        }

        String finishReason = determineNonStreamFinishReason(root, toolCalls.isEmpty());

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", finishReason);
        choices.add(choice);
        return choices;
    }

    private String extractTextContent(JsonNode outputNode) {
        if (!outputNode.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : outputNode) {
            if (!"message".equals(item.path("type").asText())) {
                continue;
            }
            for (JsonNode part : item.path("content")) {
                String partType = part.path("type").asText("");
                if ("output_text".equals(partType) || "refusal".equals(partType)) {
                    String field = "refusal".equals(partType) ? "refusal" : "text";
                    String text = textOrDefault(part.get(field), textOrDefault(part.get("text"), ""));
                    sb.append(text);
                }
            }
        }
        return sb.toString();
    }

    private List<Map<String, Object>> extractToolCalls(JsonNode outputNode) {
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        if (!outputNode.isArray()) {
            return toolCalls;
        }
        for (JsonNode item : outputNode) {
            String itemType = item.path("type").asText();
            if (!isToolCallItem(itemType)) {
                continue;
            }
            String callId = textOrDefault(item.get("call_id"), textOrDefault(item.get("id"), ""));

            Map<String, Object> toolCall = new LinkedHashMap<>();
            toolCall.put("id", callId);
            if ("custom_tool_call".equals(itemType)) {
                Map<String, Object> custom = new LinkedHashMap<>();
                custom.put("name", textOrDefault(item.get("name"), ""));
                custom.put("input", textOrDefault(item.get("input"), ""));
                toolCall.put("type", "custom");
                toolCall.put("custom", custom);
            } else {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", textOrDefault(item.get("name"), ""));
                function.put("arguments", textOrDefault(item.get("arguments"), "{}"));
                toolCall.put("type", "function");
                toolCall.put("function", function);
            }
            toolCalls.add(toolCall);
        }
        return toolCalls;
    }

    private String determineNonStreamFinishReason(JsonNode root, boolean noToolCalls) {
        String status = textOrDefault(root.get("status"), "completed");
        if ("incomplete".equals(status)) {
            return "length";
        }
        if ("failed".equals(status)) {
            return "content_filter";
        }
        // completed
        JsonNode outputNode = root.path("output");
        if (outputNode.isArray()) {
            for (JsonNode item : outputNode) {
                if (isToolCallItem(item.path("type").asText())) {
                    return "tool_calls";
                }
            }
        }
        return "stop";
    }

    private Map<String, Object> buildChatUsage(JsonNode usageNode) {
        Map<String, Object> usage = new LinkedHashMap<>();
        if (usageNode.isMissingNode() || usageNode.isNull()) {
            usage.put("prompt_tokens", 0);
            usage.put("completion_tokens", 0);
            usage.put("total_tokens", 0);
            return usage;
        }
        int inputTokens = usageNode.path("input_tokens").asInt(0);
        int outputTokens = usageNode.path("output_tokens").asInt(0);
        usage.put("prompt_tokens", inputTokens);
        usage.put("completion_tokens", outputTokens);
        usage.put("total_tokens", usageNode.path("total_tokens").asInt(inputTokens + outputTokens));

        // 缓存 token 详情
        JsonNode inputDetails = usageNode.path("input_tokens_details");
        boolean hasCachedTokens = inputDetails.hasNonNull("cached_tokens");
        boolean hasCacheWriteTokens = inputDetails.hasNonNull("cache_write_tokens");
        if (hasCachedTokens || hasCacheWriteTokens) {
            Map<String, Object> promptDetails = new LinkedHashMap<>();
            if (hasCachedTokens) {
                promptDetails.put("cached_tokens", inputDetails.path("cached_tokens").asInt(0));
            }
            if (hasCacheWriteTokens) {
                promptDetails.put("cache_write_tokens", inputDetails.path("cache_write_tokens").asInt(0));
            }
            usage.put("prompt_tokens_details", promptDetails);
        }
        return usage;
    }

    // ──────────────────────────────── 流式 ────────────────────────────────

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
        // Responses API 使用命名事件（event 字段），从 event() 中读取类型
        String eventType = sse != null ? sse.event() : null;
        String data = sse != null ? sse.data() : null;

        if (data == null || data.isBlank()) {
            return List.of();
        }
        // Chat Completions 流不使用 [DONE] 来触发 Responses 流的结束，Responses 用 response.completed
        if ("[DONE]".equals(data.trim())) {
            return List.of();
        }

        // 若事件类型从 event 字段获取，则使用；否则从 data JSON 的 type 字段获取
        if (eventType == null || eventType.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(data);
                eventType = root.path("type").asText("");
            } catch (JacksonException e) {
                log.warn("Failed to parse Responses stream chunk: {}", e.getMessage());
                return List.of();
            }
        }

        try {
            JsonNode root = objectMapper.readTree(data);
            return handleEvent(eventType, root, state);
        } catch (JacksonException e) {
            log.warn("Failed to process Responses stream event '{}': {}", eventType, e.getMessage());
            return List.of();
        }
    }

    private List<SseEvent<String>> handleEvent(
            String eventType, JsonNode root, StreamState state
    ) throws JacksonException {
        List<SseEvent<String>> chunks = new ArrayList<>();

        switch (eventType) {
            case "response.created", "response.in_progress" -> {
                // 提取元数据，发送带 role 的首个 chunk
                JsonNode responseNode = root.path("response");
                updateStateMetadata(responseNode, state);
                updateUsage(responseNode.path("usage"), state);
                ensureFirstChunkSent(state, chunks);
            }
            case "response.output_item.added" -> {
                JsonNode item = root.path("item");
                String itemType = item.path("type").asText("");
                if (isToolCallItem(itemType)) {
                    // 开始一个新的工具调用
                    ensureFirstChunkSent(state, chunks);
                    int tcIndex = state.nextToolCallIndex++;
                    ToolCallState tcState = new ToolCallState(tcIndex);
                    tcState.type = "custom_tool_call".equals(itemType) ? "custom" : "function";
                    tcState.id = textOrDefault(item.get("call_id"), textOrDefault(item.get("id"), ""));
                    tcState.name = textOrDefault(item.get("name"), "");
                    String itemId = textOrDefault(item.get("id"), "item_" + tcIndex);
                    state.toolCallStates.put(itemId, tcState);
                    chunks.add(buildToolCallStartChunk(state, tcState));
                }
            }
            case "response.output_item.done" -> {
                JsonNode item = root.path("item");
                String itemType = item.path("type").asText("");
                if (isToolCallItem(itemType)) {
                    String itemId = textOrDefault(item.get("id"), "");
                    ToolCallState tcState = state.toolCallStates.get(itemId);
                    if (tcState != null) {
                        if (!tcState.nameEmitted) {
                            // 补发 name（如果之前没发）
                            tcState.name = textOrDefault(item.get("name"), tcState.name);
                            tcState.id = textOrDefault(item.get("call_id"), tcState.id);
                            chunks.add(buildToolCallStartChunk(state, tcState));
                        }
                        appendFinalToolInputIfNeeded(state, tcState,
                                toolInput(item), chunks);
                    }
                }
            }
            case "response.output_text.delta" -> {
                ensureFirstChunkSent(state, chunks);
                String delta = root.path("delta").asText("");
                if (!delta.isEmpty()) {
                    chunks.add(buildTextDeltaChunk(state, delta));
                }
            }
            case "response.function_call_arguments.delta" -> {
                ensureFirstChunkSent(state, chunks);
                String delta = root.path("delta").asText("");
                String itemId = root.path("item_id").asText("");
                int outputIndex = root.path("output_index").asInt(-1);
                ToolCallState tcState = resolveToolCallState(itemId, outputIndex, state);
                if (tcState != null && !delta.isEmpty()) {
                    appendToolInputDelta(state, tcState, delta, chunks);
                }
            }
            case "response.function_call_arguments.done" -> {
                String itemId = root.path("item_id").asText("");
                int outputIndex = root.path("output_index").asInt(-1);
                ToolCallState tcState = resolveToolCallState(itemId, outputIndex, state);
                if (tcState != null) {
                    appendFinalToolInputIfNeeded(state, tcState,
                            textOrDefault(root.get("arguments"), ""), chunks);
                }
            }
            case "response.custom_tool_call_input.delta" -> {
                ensureFirstChunkSent(state, chunks);
                String delta = root.path("delta").asText("");
                String itemId = root.path("item_id").asText("");
                int outputIndex = root.path("output_index").asInt(-1);
                ToolCallState tcState = resolveToolCallState(itemId, outputIndex, state);
                if (tcState != null && !delta.isEmpty()) {
                    appendToolInputDelta(state, tcState, delta, chunks);
                }
            }
            case "response.custom_tool_call_input.done" -> {
                String itemId = root.path("item_id").asText("");
                int outputIndex = root.path("output_index").asInt(-1);
                ToolCallState tcState = resolveToolCallState(itemId, outputIndex, state);
                if (tcState != null) {
                    appendFinalToolInputIfNeeded(state, tcState,
                            textOrDefault(root.get("input"), ""), chunks);
                }
            }
            case "response.completed" -> {
                JsonNode responseNode = root.path("response");
                // 非终止状态（in_progress / queued）说明异步后台响应尚未完成，跳过 finish 等待轮询补全
                String completedStatus = textOrDefault(responseNode.get("status"), "completed");
                if (!isTerminalStatus(completedStatus)) {
                    updateStateMetadata(responseNode, state);
                    break;
                }
                updateStateMetadata(responseNode, state);
                appendMissingFinalToolInputs(responseNode, state, chunks);
                state.finishReason = determineStreamFinishReason(responseNode, state);
                updateUsage(responseNode.path("usage"), state);
                chunks.addAll(buildFinishChunks(state));
            }
            case "response.incomplete" -> {
                JsonNode responseNode = root.path("response");
                updateStateMetadata(responseNode, state);
                appendMissingFinalToolInputs(responseNode, state, chunks);
                state.finishReason = determineStreamFinishReason(responseNode, state);
                updateUsage(responseNode.path("usage"), state);
                chunks.addAll(buildFinishChunks(state));
            }
            default -> {
                // response.content_part.added, response.output_text.done 等事件，忽略
            }
        }

        return chunks;
    }

    private void appendToolInputDelta(
            StreamState state, ToolCallState tcState, String delta, List<SseEvent<String>> chunks
    ) throws JacksonException {
        tcState.input.append(delta);
        chunks.add("custom".equals(tcState.type)
                ? buildCustomInputDeltaChunk(state, tcState, delta)
                : buildFunctionArgsDeltaChunk(state, tcState, delta));
    }

    private void appendFinalToolInputIfNeeded(
            StreamState state,
            ToolCallState tcState,
            String finalInput,
            List<SseEvent<String>> chunks
    ) throws JacksonException {
        if (finalInput == null) {
            return;
        }
        String missing = tcState.input.missingSuffix(finalInput);
        if (missing == null) {
            log.warn("Ignoring inconsistent final tool input for call '{}'.", tcState.id);
            return;
        }
        if (!missing.isEmpty()) {
            chunks.add("custom".equals(tcState.type)
                    ? buildCustomInputDeltaChunk(state, tcState, missing)
                    : buildFunctionArgsDeltaChunk(state, tcState, missing));
        }
    }

    private void appendMissingFinalToolInputs(
            JsonNode responseNode, StreamState state, List<SseEvent<String>> chunks
    ) throws JacksonException {
        JsonNode outputNode = responseNode.path("output");
        if (!outputNode.isArray()) {
            return;
        }
        for (JsonNode item : outputNode) {
            if (!isToolCallItem(item.path("type").asText())) {
                continue;
            }
            String itemId = textOrDefault(item.get("id"), "");
            ToolCallState tcState = state.toolCallStates.get(itemId);
            if (tcState == null) {
                continue;
            }
            appendFinalToolInputIfNeeded(state, tcState,
                    toolInput(item), chunks);
        }
    }

    private String toolInput(JsonNode item) {
        return "custom_tool_call".equals(item.path("type").asText())
                ? textOrDefault(item.get("input"), "")
                : textOrDefault(item.get("arguments"), "");
    }

    private boolean isToolCallItem(String itemType) {
        return "function_call".equals(itemType) || "custom_tool_call".equals(itemType);
    }

    private void ensureFirstChunkSent(StreamState state, List<SseEvent<String>> chunks) throws JacksonException {
        if (state.firstChunkSent) {
            return;
        }
        ObjectNode delta = objectMapper.createObjectNode();
        delta.put("role", "assistant");
        delta.putNull("content");
        chunks.add(buildChunk(state, delta, null));
        state.firstChunkSent = true;
    }

    private SseEvent<String> buildTextDeltaChunk(StreamState state, String text) throws JacksonException {
        ObjectNode delta = objectMapper.createObjectNode();
        delta.put("content", text);
        return buildChunk(state, delta, null);
    }

    private SseEvent<String> buildToolCallStartChunk(StreamState state, ToolCallState tcState) throws JacksonException {
        ObjectNode toolCallNode = objectMapper.createObjectNode();
        toolCallNode.put("index", tcState.index);
        if (!tcState.id.isBlank()) {
            toolCallNode.put("id", tcState.id);
        }
        toolCallNode.put("type", tcState.type);

        if ("custom".equals(tcState.type)) {
            ObjectNode customNode = objectMapper.createObjectNode();
            customNode.put("name", tcState.name);
            customNode.put("input", "");
            toolCallNode.set("custom", customNode);
        } else {
            ObjectNode functionNode = objectMapper.createObjectNode();
            functionNode.put("name", tcState.name);
            functionNode.put("arguments", "");
            toolCallNode.set("function", functionNode);
        }

        ObjectNode delta = objectMapper.createObjectNode();
        delta.set("tool_calls", objectMapper.createArrayNode().add(toolCallNode));

        tcState.nameEmitted = true;
        return buildChunk(state, delta, null);
    }

    private SseEvent<String> buildCustomInputDeltaChunk(
            StreamState state, ToolCallState tcState, String delta
    ) throws JacksonException {
        ObjectNode toolCallNode = objectMapper.createObjectNode();
        toolCallNode.put("index", tcState.index);

        ObjectNode customNode = objectMapper.createObjectNode();
        customNode.put("input", delta);
        toolCallNode.set("custom", customNode);

        ObjectNode deltaNode = objectMapper.createObjectNode();
        deltaNode.set("tool_calls", objectMapper.createArrayNode().add(toolCallNode));

        return buildChunk(state, deltaNode, null);
    }

    private SseEvent<String> buildFunctionArgsDeltaChunk(
            StreamState state, ToolCallState tcState, String delta
    ) throws JacksonException {
        ObjectNode toolCallNode = objectMapper.createObjectNode();
        toolCallNode.put("index", tcState.index);

        ObjectNode functionNode = objectMapper.createObjectNode();
        functionNode.put("arguments", delta);
        toolCallNode.set("function", functionNode);

        ObjectNode deltaNode = objectMapper.createObjectNode();
        deltaNode.set("tool_calls", objectMapper.createArrayNode().add(toolCallNode));

        return buildChunk(state, deltaNode, null);
    }

    private List<SseEvent<String>> buildFinishChunks(StreamState state) throws JacksonException {
        if (state.completed) {
            return List.of();
        }

        List<SseEvent<String>> chunks = new ArrayList<>();

        // 最终 chunk：包含 finish_reason 和 usage
        String finishReason = state.finishReason != null ? state.finishReason : "stop";
        ObjectNode delta = objectMapper.createObjectNode();
        SseEvent<String> finalChunk = buildChunkWithUsage(state, delta, finishReason);
        chunks.add(finalChunk);

        // [DONE]
        chunks.add(SseEvent.<String>builder().data("[DONE]").build());
        state.completed = true;
        return chunks;
    }

    private SseEvent<String> buildChunk(
            StreamState state, ObjectNode delta, String finishReason
    ) throws JacksonException {
        ObjectNode choice = objectMapper.createObjectNode();
        choice.put("index", 0);
        choice.set("delta", delta);
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        } else {
            choice.putNull("finish_reason");
        }

        ObjectNode chunk = objectMapper.createObjectNode();
        chunk.put("id", state.responseId != null ? state.responseId : "chatcmpl-" + System.currentTimeMillis());
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", state.createdAt);
        chunk.put("model", state.model != null ? state.model : "");
        chunk.set("choices", objectMapper.createArrayNode().add(choice));
        return SseEvent.<String>builder().data(objectMapper.writeValueAsString(chunk)).build();
    }

    private SseEvent<String> buildChunkWithUsage(
            StreamState state, ObjectNode delta, String finishReason
    ) throws JacksonException {
        ObjectNode choice = objectMapper.createObjectNode();
        choice.put("index", 0);
        choice.set("delta", delta);
        choice.put("finish_reason", finishReason != null ? finishReason : "stop");

        ObjectNode chunk = objectMapper.createObjectNode();
        chunk.put("id", state.responseId != null ? state.responseId : "chatcmpl-" + System.currentTimeMillis());
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", state.createdAt);
        chunk.put("model", state.model != null ? state.model : "");
        chunk.set("choices", objectMapper.createArrayNode().add(choice));

        // 附带 usage
        if (state.inputTokens > 0 || state.outputTokens > 0
                || state.cachedTokensProvided || state.cacheWriteTokensProvided) {
            ObjectNode usage = objectMapper.createObjectNode();
            usage.put("prompt_tokens", state.inputTokens);
            usage.put("completion_tokens", state.outputTokens);
            usage.put("total_tokens", state.inputTokens + state.outputTokens);
            if (state.cachedTokensProvided || state.cacheWriteTokensProvided) {
                ObjectNode promptDetails = objectMapper.createObjectNode();
                if (state.cachedTokensProvided) {
                    promptDetails.put("cached_tokens", state.cachedTokens);
                }
                if (state.cacheWriteTokensProvided) {
                    promptDetails.put("cache_write_tokens", state.cacheWriteTokens);
                }
                usage.set("prompt_tokens_details", promptDetails);
            }
            chunk.set("usage", usage);
        }

        return SseEvent.<String>builder().data(objectMapper.writeValueAsString(chunk)).build();
    }

    private void updateStateMetadata(JsonNode responseNode, StreamState state) {
        if (responseNode == null || responseNode.isMissingNode()) {
            return;
        }
        state.responseId = textOrDefault(responseNode.get("id"), state.responseId);
        state.model = textOrDefault(responseNode.get("model"), state.model);
        if (state.createdAt == 0) {
            state.createdAt = responseNode.path("created_at").asLong(System.currentTimeMillis() / 1000L);
        }
    }

    private void updateUsage(JsonNode usageNode, StreamState state) {
        if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
            return;
        }
        state.inputTokens = usageNode.path("input_tokens").asLong(state.inputTokens);
        state.outputTokens = usageNode.path("output_tokens").asLong(state.outputTokens);
        JsonNode inputDetails = usageNode.path("input_tokens_details");
        if (inputDetails.hasNonNull("cached_tokens")) {
            state.cachedTokens = inputDetails.path("cached_tokens").asLong(0);
            state.cachedTokensProvided = true;
        }
        if (inputDetails.hasNonNull("cache_write_tokens")) {
            state.cacheWriteTokens = inputDetails.path("cache_write_tokens").asLong(0);
            state.cacheWriteTokensProvided = true;
        }
    }

    private String determineStreamFinishReason(JsonNode responseNode, StreamState state) {
        String status = textOrDefault(responseNode.get("status"), "completed");
        if ("incomplete".equals(status)) {
            return "length";
        }
        if ("failed".equals(status)) {
            return "content_filter";
        }
        // 有 function_call / custom_tool_call 输出
        if (!state.toolCallStates.isEmpty()) {
            return "tool_calls";
        }
        // 检查 output 中是否含工具调用
        JsonNode outputNode = responseNode.path("output");
        if (outputNode.isArray()) {
            for (JsonNode item : outputNode) {
                if (isToolCallItem(item.path("type").asText())) {
                    return "tool_calls";
                }
            }
        }
        return "stop";
    }

    private ToolCallState resolveToolCallState(String itemId, int outputIndex, StreamState state) {
        if (!itemId.isBlank()) {
            return state.toolCallStates.get(itemId);
        }
        if (outputIndex >= 0) {
            for (ToolCallState tcs : state.toolCallStates.values()) {
                if (tcs.index == outputIndex) {
                    return tcs;
                }
            }
        }
        // fallback: 最后一个
        ToolCallState last = null;
        for (ToolCallState tcs : state.toolCallStates.values()) {
            last = tcs;
        }
        return last;
    }

    private static boolean isTerminalStatus(String status) {
        return switch (status) {
            case "completed", "failed", "incomplete", "cancelled" -> true;
            default -> false;
        };
    }

    private String textOrDefault(JsonNode node, String defaultValue) {
        return node == null || node.isNull() ? defaultValue : node.asText(defaultValue);
    }

    // ──────────────────────────────── 状态类 ────────────────────────────────

    private static final class StreamState {
        String responseId;
        String model;
        long createdAt;
        boolean firstChunkSent;
        boolean completed;
        String finishReason;
        long inputTokens;
        long outputTokens;
        long cachedTokens;
        long cacheWriteTokens;
        boolean cachedTokensProvided;
        boolean cacheWriteTokensProvided;
        int nextToolCallIndex;
        // itemId -> ToolCallState
        final Map<String, ToolCallState> toolCallStates = new LinkedHashMap<>();
    }

    private static final class ToolCallState {
        final int index;
        final ToolInputProgress input = new ToolInputProgress();
        String type = "function";
        String id = "";
        String name = "";
        boolean nameEmitted;

        ToolCallState(int index) {
            this.index = index;
        }
    }
}
