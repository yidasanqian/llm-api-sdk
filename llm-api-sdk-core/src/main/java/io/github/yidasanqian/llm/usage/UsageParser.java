package io.github.yidasanqian.llm.usage;

import io.github.yidasanqian.llm.protocol.Protocol;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import static io.github.yidasanqian.llm.usage.UsageDimensions.*;

/** Normalizes original upstream usage. Does not estimate missing counters or apply billing policies. */
public class UsageParser {
    private final ObjectMapper mapper;
    private final Map<Protocol, UsageDecoder> decoders;
    private final Map<Protocol, Supplier<? extends StreamUsageDecoder>> streamDecoders;
    public UsageParser() { this(JsonMapper.builder().build()); }
    public UsageParser(ObjectMapper mapper) { this(mapper, Map.of(), Map.of()); }
    private UsageParser(ObjectMapper mapper, Map<Protocol, UsageDecoder> decoders,
                        Map<Protocol, Supplier<? extends StreamUsageDecoder>> streamDecoders) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.decoders = Map.copyOf(decoders);
        this.streamDecoders = Map.copyOf(streamDecoders);
    }
    /** Returns an independent parser with the decoder registered; no process-global registry is modified. */
    public UsageParser withDecoder(Protocol protocol, UsageDecoder decoder) {
        var registered = new LinkedHashMap<>(decoders);
        registered.put(Objects.requireNonNull(protocol, "protocol"), Objects.requireNonNull(decoder, "decoder"));
        return new UsageParser(mapper, registered, streamDecoders);
    }

    /** Creates independent decoder state per call, so cumulative meters never leak across requests. */
    public UsageParser withStreamDecoder(Protocol protocol, Supplier<? extends StreamUsageDecoder> factory) {
        var registered = new LinkedHashMap<>(streamDecoders);
        registered.put(Objects.requireNonNull(protocol, "protocol"), Objects.requireNonNull(factory, "factory"));
        return new UsageParser(mapper, decoders, registered);
    }
    StreamUsageDecoder newStreamDecoder(Protocol protocol) {
        Supplier<? extends StreamUsageDecoder> factory = streamDecoders.get(protocol);
        return factory == null ? null : Objects.requireNonNull(factory.get(), "stream decoder");
    }

    public Optional<Usage> parse(String body, UsageContext context) {
        Objects.requireNonNull(context, "context");
        if (body == null || body.isBlank() || "[DONE]".equals(body.trim())) return Optional.empty();
        JsonNode root;
        try { root = readTree(body); }
        catch (JacksonException malformed) { return Optional.empty(); }
        return parse(root, context);
    }

    JsonNode readTree(String body) {
        return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
    }

    /** Retains only bounded numeric state; iteration arrays are reduced before storing between events. */
    ObjectNode mergeAnthropicUsage(ObjectNode previous, JsonNode incoming) {
        ObjectNode snapshot = previous == null ? mapper.createObjectNode() : previous;
        copyCounter(snapshot, incoming, "input_tokens");
        copyCounter(snapshot, incoming, "output_tokens");
        copyCounter(snapshot, incoming, "cache_creation_input_tokens");
        copyCounter(snapshot, incoming, "cache_read_input_tokens");
        if (incoming.has("cache_creation")) {
            JsonNode cache = incoming.get("cache_creation");
            if (cache.isObject()) {
                ObjectNode retainedCache = snapshot.get("cache_creation") instanceof ObjectNode object
                        ? object : mapper.createObjectNode();
                copyCounter(retainedCache, cache, "ephemeral_5m_input_tokens");
                copyCounter(retainedCache, cache, "ephemeral_1h_input_tokens");
                snapshot.set("cache_creation", retainedCache);
            } else snapshot.putNull("cache_creation");
        }
        if (incoming.has("iterations")) {
            ObjectNode totals = mapper.createObjectNode();
            Long input = iterationTotal(incoming.path("iterations"), "input_tokens");
            Long output = iterationTotal(incoming.path("iterations"), "output_tokens");
            if (input != null) totals.put("input_tokens", input.longValue());
            if (output != null) totals.put("output_tokens", output.longValue());
            snapshot.set("iterations", mapper.createArrayNode().add(totals));
        }
        return snapshot;
    }

    private static void copyCounter(ObjectNode target, JsonNode source, String field) {
        if (!source.has(field)) return;
        Long value = number(source, field);
        if (value == null) target.putNull(field);
        else target.put(field, value.longValue());
    }

    public Optional<Usage> parse(JsonNode root, UsageContext context) {
        Objects.requireNonNull(context, "context");
        if (root == null || !root.isObject()) return Optional.empty();
        UsageDecoder custom = decoders.get(context.protocol());
        if (custom != null) return Objects.requireNonNull(custom.decode(root, context), "decoder result");
        Map<String, UsageMeasurement> values = new LinkedHashMap<>();
        Protocol protocol = context.protocol();
        if (Protocol.OPENAI_CHAT.equals(protocol)) openAi(root.get("usage"), values, true);
        else if (Protocol.OPENAI_RESPONSES.equals(protocol)) openAi(root.has("response") ? root.path("response").get("usage") : root.get("usage"), values, false);
        else if (Protocol.ANTHROPIC_MESSAGES.equals(protocol)) anthropic(root.get("usage"), values);
        else if (Protocol.GEMINI.equals(protocol)) gemini(root.get("usageMetadata"), values);
        else if (Protocol.SEEDANCE_VIDEO.equals(protocol)) seedanceVideo(root, values);
        else if (Protocol.EMBEDDINGS.equals(protocol)) inputOnly(root.get("usage"), values);
        else if (Protocol.RERANK.equals(protocol)) rerank(root, context.providerId(), values);
        return values.isEmpty() ? Optional.empty() : Optional.of(Usage.upstream(values));
    }

    private void openAi(JsonNode usage, Map<String, UsageMeasurement> values, boolean chat) {
        if (!present(usage)) return;
        Long raw = chat ? first(number(usage, "prompt_tokens"), number(usage, "input_tokens")) : number(usage, "input_tokens");
        Long output = chat ? first(number(usage, "completion_tokens"), number(usage, "output_tokens")) : number(usage, "output_tokens");
        Long read = number(usage.get("input_tokens_details"), "cached_tokens");
        Long write = number(usage.get("input_tokens_details"), "cache_write_tokens");
        if (chat) {
            read = first(number(usage.get("prompt_tokens_details"), "cached_tokens"), read);
            write = first(number(usage.get("prompt_tokens_details"), "cache_write_tokens"), write);
        }
        add(values, RAW_INPUT_TOKENS, raw);
        add(values, INPUT_TOKENS, raw == null ? null : Math.max(Math.max(raw - zero(read), 0) - zero(write), 0));
        add(values, OUTPUT_TOKENS, output);
        add(values, TOTAL_TOKENS, number(usage, "total_tokens"));
        add(values, CACHE_READ_TOKENS, read);
        add(values, CACHE_WRITE_TOKENS, write);
        add(values, TIER_BASIS_TOKENS, raw);
    }

    private void anthropic(JsonNode usage, Map<String, UsageMeasurement> values) {
        if (!present(usage)) return;
        Long input = iterations(usage, "input_tokens");
        Long output = iterations(usage, "output_tokens");
        Long five = number(usage.path("cache_creation"), "ephemeral_5m_input_tokens");
        Long hour = number(usage.path("cache_creation"), "ephemeral_1h_input_tokens");
        Long write = first(number(usage, "cache_creation_input_tokens"), sum(five, hour));
        Long read = number(usage, "cache_read_input_tokens");
        add(values, RAW_INPUT_TOKENS, input);
        add(values, INPUT_TOKENS, input);
        add(values, OUTPUT_TOKENS, output);
        add(values, CACHE_READ_TOKENS, read);
        add(values, CACHE_WRITE_TOKENS, write);
        add(values, CACHE_WRITE_5M_TOKENS, five);
        add(values, CACHE_WRITE_1H_TOKENS, hour);
        add(values, TIER_BASIS_TOKENS, sum(input, write, read));
        // A partial input-only stream snapshot must not manufacture a final total.
        if (input != null && output != null) add(values, TOTAL_TOKENS, sum(input, output, write, read));
    }

    private void gemini(JsonNode usage, Map<String, UsageMeasurement> values) {
        if (!present(usage)) return;
        Long raw = number(usage, "promptTokenCount");
        Long cache = number(usage, "cachedContentTokenCount");
        add(values, RAW_INPUT_TOKENS, raw);
        add(values, INPUT_TOKENS, raw == null ? null : Math.max(raw - zero(cache), 0));
        add(values, OUTPUT_TOKENS, sum(number(usage, "candidatesTokenCount"), number(usage, "thoughtsTokenCount")));
        add(values, TOTAL_TOKENS, number(usage, "totalTokenCount"));
        add(values, CACHE_READ_TOKENS, cache);
        add(values, TIER_BASIS_TOKENS, raw);
    }

    private void seedanceVideo(JsonNode root, Map<String, UsageMeasurement> values) {
        if (!"succeeded".equals(root.path("status").asString(""))) return;
        JsonNode usage = root.get("usage");
        if (!present(usage)) return;
        Long completion = number(usage, "completion_tokens");
        if (completion != null) {
            String resolution = root.path("resolution").asString("").trim();
            values.put(OUTPUT_TOKENS, new UsageMeasurement(BigDecimal.valueOf(completion), TOKEN_UNIT,
                    resolution.isEmpty() ? Map.of() : Map.of("resolution", resolution)));
        }
        add(values, TOTAL_TOKENS, number(usage, "total_tokens"));
    }

    private void inputOnly(JsonNode usage, Map<String, UsageMeasurement> values) {
        if (!present(usage)) return;
        Long input = first(number(usage, "prompt_tokens"), number(usage, "input_tokens"));
        Long total = number(usage, "total_tokens");
        inputOnly(values, input, first(total, input));
    }

    private void inputOnly(Map<String, UsageMeasurement> values, Long input, Long total) {
        if (input == null && total == null) return;
        add(values, RAW_INPUT_TOKENS, input);
        add(values, INPUT_TOKENS, input);
        add(values, OUTPUT_TOKENS, 0L);
        add(values, TOTAL_TOKENS, total);
        add(values, TIER_BASIS_TOKENS, input);
    }

    private void rerank(JsonNode root, String providerId, Map<String, UsageMeasurement> values) {
        if ("aliyun-bailian".equals(providerId)) {
            Long tokens = number(root.get("usage"), "total_tokens");
            inputOnly(values, tokens, tokens);
        } else if ("siliconflow".equals(providerId)) {
            JsonNode billed = root.path("meta").path("billed_units");
            JsonNode tokens = root.path("meta").path("tokens");
            Long input = first(number(billed, "input_tokens"), number(tokens, "input_tokens"));
            inputOnly(values, input, input);
            for (String dimension : new String[]{"image_tokens", "search_units", "classifications"}) {
                Long amount = first(number(billed, dimension), number(tokens, dimension));
                if (amount != null) values.put(dimension, new UsageMeasurement(BigDecimal.valueOf(amount), dimension));
            }
        } else inputOnly(root.get("usage"), values);
    }

    private Long iterations(JsonNode usage, String field) {
        Long result = iterationTotal(usage.path("iterations"), field);
        return result == null ? number(usage, field) : result;
    }
    private static Long iterationTotal(JsonNode iterations, String field) {
        Long result = null;
        for (JsonNode iteration : iterations) {
            Long value = number(iteration, field);
            if (value != null) result = result == null ? value : Math.addExact(result, value);
        }
        return result;
    }
    private static boolean present(JsonNode node) { return node != null && node.isObject() && !node.isEmpty(); }
    private static Long number(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) {
            throw new IllegalArgumentException("Usage counter must be a non-negative integer: " + field);
        }
        return value.asLong();
    }
    private static void add(Map<String, UsageMeasurement> values, String dimension, Long value) {
        if (value != null) values.put(dimension, UsageMeasurement.tokens(value));
    }
    private static Long first(Long first, Long second) { return first != null ? first : second; }
    private static long zero(Long value) { return value == null ? 0 : value; }
    private static Long sum(Long... values) {
        Long total = null;
        for (Long value : values) if (value != null) total = total == null ? value : Math.addExact(total, value);
        return total;
    }
}
