package io.github.yidasanqian.llm.error;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * 错误信封 codec 注册表。
 */
public final class LlmErrorCodecs {

    private static final Map<ErrorEnvelopeFamily, LlmErrorCodec> CODECS = codecs();

    private LlmErrorCodecs() {
    }

    private static LlmErrorCodec codec(ErrorEnvelopeFamily family) {
        return CODECS.get(family == null ? ErrorEnvelopeFamily.OPENAI : family);
    }

    public static Optional<LlmApiError> parse(ErrorEnvelopeFamily family, int statusCode, String body) {
        return codec(family).parse(statusCode, body);
    }

    public static String render(
            ErrorEnvelopeFamily family,
            LlmApiError error,
            int statusCode,
            String fallbackRequestId) {
        return codec(family).render(error, statusCode, fallbackRequestId);
    }

    private static Map<ErrorEnvelopeFamily, LlmErrorCodec> codecs() {
        Map<ErrorEnvelopeFamily, LlmErrorCodec> codecs = new EnumMap<>(ErrorEnvelopeFamily.class);
        codecs.put(ErrorEnvelopeFamily.OPENAI, new OpenAiErrorCodec());
        codecs.put(ErrorEnvelopeFamily.ANTHROPIC, new AnthropicErrorCodec());
        codecs.put(ErrorEnvelopeFamily.GEMINI, new GeminiErrorCodec());
        return Map.copyOf(codecs);
    }
}
