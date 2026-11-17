package io.github.yidasanqian.llm.json;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Shared immutable Jackson 3 mapper for protocol JSON, without storage repair rules. */
public final class JsonUtil {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private JsonUtil() { }

    public static String toJsonStr(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T toObject(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }
}
