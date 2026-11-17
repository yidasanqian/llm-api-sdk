package io.github.yidasanqian.llm.bridge.anthropic;

import java.util.LinkedHashMap;
import java.util.Map;

/** Request option mappings shared by Anthropic-to-OpenAI bridges. */
final class AnthropicRequestOptions {
    private AnthropicRequestOptions() { }

    static void ensureStreamIncludeUsage(Map<String, Object> request) {
        Map<String, Object> options = new LinkedHashMap<>();
        if (request.get("stream_options") instanceof Map<?, ?> existing) {
            existing.forEach((key, value) -> {
                if (key != null) options.put(String.valueOf(key), value);
            });
        }
        options.put("include_usage", true);
        request.put("stream_options", options);
    }

    static void applyOutputConfig(Object outputConfig, Map<String, Object> target) {
        if (!(outputConfig instanceof Map<?, ?> config)) return;
        Object effort = config.get("effort");
        if (effort != null && !target.containsKey("reasoning_effort")) {
            target.put("reasoning_effort", effort);
        }
        Object format = config.get("format");
        if (format instanceof Map<?, ?> formatMap && !target.containsKey("response_format")
                && "json_schema".equals(String.valueOf(formatMap.get("type")))) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("name", "response");
            schema.put("strict", true);
            schema.put("schema", formatMap.get("schema"));
            target.put("response_format", Map.of("type", "json_schema", "json_schema", schema));
        }
    }
}
