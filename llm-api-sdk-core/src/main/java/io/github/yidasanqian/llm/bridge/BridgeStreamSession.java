package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.protocol.SseEvent;

import java.util.List;

public interface BridgeStreamSession {

    List<SseEvent<String>> map(SseEvent<String> event);

    List<SseEvent<String>> finishSuccess();
}
