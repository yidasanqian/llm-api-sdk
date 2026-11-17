package io.github.yidasanqian.llm.client;

import io.github.yidasanqian.llm.usage.Usage;
import java.util.Optional;

/** At-most-once observation for one subscription/attempt; persistent billing idempotency belongs to the host. */
public record CallCompletion(CallOutcome outcome, int statusCode, Optional<Usage> usage,
                             Optional<Usage> partialUsage, boolean downstreamCancelled, Throwable error) { }
