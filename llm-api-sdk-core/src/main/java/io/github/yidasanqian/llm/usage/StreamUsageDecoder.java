package io.github.yidasanqian.llm.usage;

import io.github.yidasanqian.llm.protocol.SseEvent;
import java.util.Optional;

/** Per-call extension that explicitly identifies cumulative usage authority and protocol termination. */
@FunctionalInterface
public interface StreamUsageDecoder {
    Optional<StreamUsageUpdate> decode(SseEvent<String> event, UsageContext context);
}
