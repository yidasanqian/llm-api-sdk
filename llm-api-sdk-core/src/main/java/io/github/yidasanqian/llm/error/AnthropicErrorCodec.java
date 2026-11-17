package io.github.yidasanqian.llm.error;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

final class AnthropicErrorCodec implements LlmErrorCodec {

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
            String message = error.asText();
            return Optional.of(new LlmApiError(
                    ErrorEnvelopeFamily.ANTHROPIC,
                    LlmErrorCategory.fromStatus(statusCode),
                    message,
                    null,
                    null,
                    null,
                    null,
                    null,
                    ErrorCodecSupport.firstText(root, "request_id", "requestId")
            ));
        }
        if (!error.isObject()) {
            return Optional.empty();
        }

        String message = ErrorCodecSupport.firstText(error, "message");
        if (message == null) {
            return Optional.empty();
        }
        String type = ErrorCodecSupport.firstText(error, "type");
        String requestId = ErrorCodecSupport.firstText(root, "request_id", "requestId");
        return Optional.of(new LlmApiError(
                ErrorEnvelopeFamily.ANTHROPIC,
                LlmErrorCategory.from(statusCode, type, null, null),
                message,
                type,
                null,
                null,
                null,
                null,
                requestId
        ));
    }

    @Override
    public String render(LlmApiError error, int statusCode, String fallbackRequestId) {
        ObjectNode root = ErrorCodecSupport.OBJECT_MAPPER.createObjectNode();
        root.put("type", "error");
        ObjectNode body = root.putObject("error");
        boolean preserveNative = error.sourceFamily() == ErrorEnvelopeFamily.ANTHROPIC;
        body.put("type", preserveNative && hasText(error.type())
                ? error.type()
                : anthropicType(error.category()));
        body.put("message", error.message());

        String requestId = hasText(error.requestId()) ? error.requestId() : fallbackRequestId;
        if (hasText(requestId)) {
            root.put("request_id", requestId);
        }
        return ErrorCodecSupport.writeJson(root);
    }

    private static String anthropicType(LlmErrorCategory category) {
        return switch (category) {
            case INVALID_REQUEST -> "invalid_request_error";
            case AUTHENTICATION -> "authentication_error";
            case PERMISSION -> "permission_error";
            case NOT_FOUND -> "not_found_error";
            case REQUEST_TOO_LARGE -> "request_too_large";
            case RATE_LIMIT -> "rate_limit_error";
            case BILLING -> "billing_error";
            case OVERLOADED -> "overloaded_error";
            case TIMEOUT, SERVER, UNKNOWN -> "api_error";
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
