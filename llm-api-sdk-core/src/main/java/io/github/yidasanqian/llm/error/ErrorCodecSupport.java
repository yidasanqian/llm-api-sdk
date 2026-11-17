package io.github.yidasanqian.llm.error;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

final class ErrorCodecSupport {

    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ErrorCodecSupport() {
    }

    static JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            return root != null && root.isObject() ? root : null;
        } catch (JacksonException ignored) {
            return null;
        }
    }

    static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    static JsonNode node(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value;
    }

    static String writeJson(ObjectNode body) {
        try {
            return OBJECT_MAPPER.writeValueAsString(body);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize LLM error response", e);
        }
    }
}
