package io.github.yidasanqian.llm.error;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

final class OpenAiErrorCodec implements LlmErrorCodec {

    @Override
    public Optional<LlmApiError> parse(int statusCode, String body) {
        JsonNode root = ErrorCodecSupport.readTree(body);
        if (root == null) {
            return Optional.empty();
        }
        String rootType = ErrorCodecSupport.text(root, "type");
        JsonNode errorNode = root.get("error");
        JsonNode errorSource = root;
        if (errorNode == null) {
            JsonNode response = root.get("response");
            if (response != null && response.isObject()) {
                JsonNode responseError = response.get("error");
                if (responseError != null && !responseError.isNull()) {
                    errorNode = responseError;
                    errorSource = response;
                } else if ("response.failed".equals(rootType)) {
                    errorSource = response;
                }
            }
        }
        if (errorNode == null && !"error".equals(rootType) && !"response.failed".equals(rootType)) {
            return Optional.empty();
        }

        String message;
        String type;
        String code;
        JsonNode param;
        String providerStatus;
        JsonNode details;
        if (errorNode != null && errorNode.isTextual()) {
            message = errorNode.asText();
            type = null;
            code = null;
            param = null;
            providerStatus = null;
            details = null;
        } else {
            JsonNode error = errorNode != null && errorNode.isObject() ? errorNode : errorSource;
            message = ErrorCodecSupport.firstText(error, "message");
            type = ErrorCodecSupport.firstText(error, "type");
            code = ErrorCodecSupport.firstText(error, "code");
            if ("error".equalsIgnoreCase(type)) {
                type = null;
            }
            param = ErrorCodecSupport.node(error, "param");
            providerStatus = ErrorCodecSupport.firstText(error, "status");
            details = ErrorCodecSupport.node(error, "details");
        }
        if (message == null) {
            return Optional.empty();
        }

        String requestId = ErrorCodecSupport.firstText(root, "request_id", "requestId");
        if (requestId == null) {
            requestId = ErrorCodecSupport.firstText(errorSource, "request_id", "requestId");
        }
        LlmErrorCategory category = LlmErrorCategory.from(statusCode, type, code, providerStatus);
        return Optional.of(new LlmApiError(
                ErrorEnvelopeFamily.OPENAI,
                category,
                message,
                type,
                code,
                param,
                providerStatus,
                details,
                requestId
        ));
    }

    @Override
    public String render(LlmApiError error, int statusCode, String fallbackRequestId) {
        ObjectNode root = ErrorCodecSupport.OBJECT_MAPPER.createObjectNode();
        ObjectNode body = root.putObject("error");
        body.put("message", error.message());

        boolean preserveNative = error.sourceFamily() == null || error.sourceFamily() == ErrorEnvelopeFamily.OPENAI;
        body.put("type", preserveNative && hasText(error.type())
                ? error.type()
                : openAiType(error.category()));
        if (preserveNative && error.param() != null) {
            body.set("param", error.param());
        }
        if (preserveNative && hasText(error.code())) {
            body.put("code", error.code());
        }
        return ErrorCodecSupport.writeJson(root);
    }

    private static String openAiType(LlmErrorCategory category) {
        return switch (category) {
            case INVALID_REQUEST, REQUEST_TOO_LARGE -> "invalid_request_error";
            case AUTHENTICATION -> "authentication_error";
            case PERMISSION -> "permission_denied";
            case NOT_FOUND -> "not_found";
            case RATE_LIMIT -> "rate_limit_exceeded";
            case BILLING -> "insufficient_quota";
            case OVERLOADED -> "overloaded_error";
            case TIMEOUT, SERVER, UNKNOWN -> "api_error";
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
