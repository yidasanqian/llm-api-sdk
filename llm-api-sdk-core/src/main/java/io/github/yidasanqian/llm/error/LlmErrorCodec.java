package io.github.yidasanqian.llm.error;

import java.util.Optional;

/**
 * 单一协议族的错误信封解析与渲染策略。
 */
interface LlmErrorCodec {

    Optional<LlmApiError> parse(int statusCode, String body);

    String render(LlmApiError error, int statusCode, String fallbackRequestId);
}
