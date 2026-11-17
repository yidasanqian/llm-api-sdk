package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.protocol.SseEvent;

import java.util.function.Predicate;

/**
 * 桥接流的正常事件 mapper。错误拦截与终止收尾不在 mapper 内处理。
 */
public interface BridgeStreamMapper {

    BridgeStreamSession newSession();

    Predicate<SseEvent<String>> createStreamReadiness();
}
