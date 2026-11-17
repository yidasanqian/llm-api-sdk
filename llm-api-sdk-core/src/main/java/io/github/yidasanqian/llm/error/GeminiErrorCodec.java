package io.github.yidasanqian.llm.error;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

final class GeminiErrorCodec implements LlmErrorCodec {

    @Override
    public Optional<LlmApiError> parse(int statusCode, String body) {
        JsonNode root = ErrorCodecSupport.readTree(body);
        if (root == null) {
            return Optional.empty();
        }
        JsonNode error = root.get("error");
        if (error == null || error.isNull()) {
            return Optional.empty();
        }
        if (error.isTextual()) {
            return Optional.of(new LlmApiError(
                    ErrorEnvelopeFamily.GEMINI,
                    LlmErrorCategory.fromStatus(statusCode),
                    error.asText(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            ));
        }
        if (!error.isObject()) {
            return Optional.empty();
        }

        String message = ErrorCodecSupport.firstText(error, "message");
        if (message == null) {
            return Optional.empty();
        }
        JsonNode codeNode = ErrorCodecSupport.node(error, "code");
        String code = codeNode == null ? null : codeNode.asText();
        String providerStatus = ErrorCodecSupport.firstText(error, "status");
        JsonNode details = ErrorCodecSupport.node(error, "details");
        LlmErrorCategory category = LlmErrorCategory.from(statusCode, null, code, providerStatus);
        return Optional.of(new LlmApiError(
                ErrorEnvelopeFamily.GEMINI,
                category,
                message,
                null,
                code,
                null,
                providerStatus,
                details,
                null
        ));
    }

    @Override
    public String render(LlmApiError error, int statusCode, String fallbackRequestId) {
        int code = statusCode > 0 ? statusCode : 500;
        ObjectNode root = ErrorCodecSupport.OBJECT_MAPPER.createObjectNode();
        ObjectNode body = root.putObject("error");
        body.put("code", code);
        body.put("message", error.message());
        boolean preserveNative = error.sourceFamily() == ErrorEnvelopeFamily.GEMINI;
        body.put("status", preserveNative && hasText(error.providerStatus())
                ? error.providerStatus()
                : geminiStatus(code, error.category()));
        if (preserveNative && error.details() != null) {
            body.set("details", error.details());
        }
        return ErrorCodecSupport.writeJson(root);
    }

    private static String geminiStatus(int statusCode, LlmErrorCategory category) {
        return switch (category) {
            case INVALID_REQUEST, REQUEST_TOO_LARGE -> "INVALID_ARGUMENT";
            case AUTHENTICATION -> "UNAUTHENTICATED";
            case PERMISSION -> "PERMISSION_DENIED";
            case NOT_FOUND -> "NOT_FOUND";
            case RATE_LIMIT -> "RESOURCE_EXHAUSTED";
            case BILLING -> "FAILED_PRECONDITION";
            case OVERLOADED -> "UNAVAILABLE";
            case TIMEOUT -> "DEADLINE_EXCEEDED";
            case SERVER -> statusCode == 500 ? "INTERNAL" : "UNAVAILABLE";
            case UNKNOWN -> "UNKNOWN";
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
