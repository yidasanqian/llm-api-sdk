package io.github.yidasanqian.llm.client;

import org.springframework.http.HttpHeaders;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ResponseHeaders {
    private ResponseHeaders() { }
    static Map<String, List<String>> from(HttpHeaders headers) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((key, values) -> copy.put(key, List.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }
    static Map<String, List<String>> copy(Map<String, List<String>> headers) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((key, values) -> copy.put(key, List.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }
}
