package io.github.yidasanqian.llm.usage;

import tools.jackson.databind.JsonNode;
import java.util.Optional;

/** Stateless/thread-safe provider usage extension. Stream authority is defined separately by StreamUsageDecoder. */
@FunctionalInterface
public interface UsageDecoder {
    Optional<Usage> decode(JsonNode response, UsageContext context);
}
