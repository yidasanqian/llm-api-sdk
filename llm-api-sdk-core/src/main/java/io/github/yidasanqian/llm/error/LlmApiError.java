package io.github.yidasanqian.llm.error;

import tools.jackson.databind.JsonNode;

/**
 * 内部统一错误模型。协议字段只在协议边界解析和渲染。
 *
 * <p>{@code sourceFamily} 为 {@code null} 表示网关本地错误，其
 * {@code type/code} 按历史 OpenAI 风格字段保存；跨协议渲染时由分类推导目标字段。</p>
 */
public record LlmApiError(
        ErrorEnvelopeFamily sourceFamily,
        LlmErrorCategory category,
        String message,
        String type,
        String code,
        JsonNode param,
        String providerStatus,
        JsonNode details,
        String requestId) {

    public LlmApiError {
        message = message == null ? "" : message;
        category = category == null ? LlmErrorCategory.UNKNOWN : category;
    }

    public LlmApiError withMessage(String newMessage) {
        return new LlmApiError(sourceFamily, category, newMessage, type, code, param, providerStatus, details, requestId);
    }

    public static LlmApiError fallback(
            ErrorEnvelopeFamily sourceFamily,
            Integer statusCode,
            String message) {
        return new LlmApiError(
                sourceFamily,
                LlmErrorCategory.fromStatus(statusCode),
                message,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
