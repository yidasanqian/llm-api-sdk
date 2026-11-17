package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.error.ErrorEnvelopeFamily;
import io.github.yidasanqian.llm.error.LlmApiError;
import io.github.yidasanqian.llm.error.LlmErrorCodecs;

/** Converts error bodies only; the transport preserves status and manages representation headers. */
public final class BridgeErrorTranslator {
    private BridgeErrorTranslator() { }

    public static String translate(ErrorEnvelopeFamily source, ErrorEnvelopeFamily target, ErrorResponse response) {
        if (source == target) return response.body();
        LlmApiError error = LlmErrorCodecs.parse(source, response.statusCode(), response.body())
                .orElseGet(() -> fallback(source, response.statusCode()));
        return LlmErrorCodecs.render(target, error, response.statusCode(), response.requestId());
    }

    static LlmApiError fallback(ErrorEnvelopeFamily family, Integer statusCode) {
        return LlmApiError.fallback(family, statusCode,
                "Upstream request failed" + (statusCode == null ? "" : " (HTTP " + statusCode + ")"));
    }

    public record ErrorResponse(int statusCode, String body, String requestId) { }
}
