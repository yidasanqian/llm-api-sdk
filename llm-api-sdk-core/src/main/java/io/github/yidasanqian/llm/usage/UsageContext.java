package io.github.yidasanqian.llm.usage;

import io.github.yidasanqian.llm.protocol.Protocol;
import java.util.Objects;

/** The protocol actually used upstream, never the downstream client protocol. */
public record UsageContext(Protocol protocol, String providerId) {
    public UsageContext { Objects.requireNonNull(protocol, "protocol"); }
    public UsageContext(Protocol protocol) { this(protocol, null); }
}
