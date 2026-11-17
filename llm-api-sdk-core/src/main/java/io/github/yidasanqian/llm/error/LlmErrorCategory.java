package io.github.yidasanqian.llm.error;

import java.util.Locale;

/**
 * 跨协议的错误分类。协议原生 type/code 仍保留在 {@link LlmApiError} 中。
 */
public enum LlmErrorCategory {

    INVALID_REQUEST,
    AUTHENTICATION,
    PERMISSION,
    NOT_FOUND,
    REQUEST_TOO_LARGE,
    RATE_LIMIT,
    BILLING,
    OVERLOADED,
    TIMEOUT,
    SERVER,
    UNKNOWN;

    public static LlmErrorCategory from(Integer statusCode, String type, String code, String providerStatus) {
        LlmErrorCategory fromCode = fromCode(code);
        if (fromCode != null) {
            return fromCode;
        }
        LlmErrorCategory fromType = fromType(type);
        if (fromType != null) {
            return fromType;
        }
        LlmErrorCategory fromProviderStatus = fromProviderStatus(providerStatus);
        if (fromProviderStatus != null) {
            return fromProviderStatus;
        }
        return fromStatus(statusCode);
    }

    public static LlmErrorCategory fromStatus(Integer statusCode) {
        if (statusCode == null) {
            return UNKNOWN;
        }
        return switch (statusCode) {
            case 400, 409, 422 -> INVALID_REQUEST;
            case 401 -> AUTHENTICATION;
            case 402 -> BILLING;
            case 403 -> PERMISSION;
            case 404 -> NOT_FOUND;
            case 408 -> TIMEOUT;
            case 413 -> REQUEST_TOO_LARGE;
            case 429 -> RATE_LIMIT;
            case 529 -> OVERLOADED;
            default -> statusCode >= 500 && statusCode <= 599 ? SERVER : UNKNOWN;
        };
    }

    private static LlmErrorCategory fromCode(String code) {
        if (code == null) {
            return null;
        }
        return switch (normalize(code)) {
            case "model_not_found" -> NOT_FOUND;
            case "insufficient_quota", "insufficient_balance", "insufficient_credit",
                    "billing_error" -> BILLING;
            case "rate_limit_exceeded", "periodic_quota_exceeded" -> RATE_LIMIT;
            default -> null;
        };
    }

    private static LlmErrorCategory fromType(String type) {
        if (type == null) {
            return null;
        }
        return switch (normalize(type)) {
            case "invalid_request_error", "invalid_request" -> INVALID_REQUEST;
            case "authentication_error", "authentication" -> AUTHENTICATION;
            case "permission_error", "permission_denied", "permission", "forbidden" -> PERMISSION;
            case "not_found_error", "not_found" -> NOT_FOUND;
            case "request_too_large", "payload_too_large" -> REQUEST_TOO_LARGE;
            case "rate_limit_error", "rate_limit_exceeded", "rate_limit" -> RATE_LIMIT;
            case "insufficient_quota", "billing_error" -> BILLING;
            case "overloaded_error", "overloaded" -> OVERLOADED;
            case "timeout_error", "timeout" -> TIMEOUT;
            case "api_error", "server_error", "internal_error" -> SERVER;
            default -> null;
        };
    }

    private static LlmErrorCategory fromProviderStatus(String status) {
        if (status == null) {
            return null;
        }
        return switch (normalize(status)) {
            case "invalid_argument", "out_of_range", "cancelled" -> INVALID_REQUEST;
            case "unauthenticated" -> AUTHENTICATION;
            case "permission_denied" -> PERMISSION;
            case "not_found" -> NOT_FOUND;
            case "resource_exhausted" -> RATE_LIMIT;
            case "failed_precondition" -> BILLING;
            case "unavailable", "aborted" -> OVERLOADED;
            case "deadline_exceeded" -> TIMEOUT;
            case "internal", "data_loss", "unknown" -> SERVER;
            default -> null;
        };
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
