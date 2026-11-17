package io.github.yidasanqian.llm.pricing;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/** The host selects the billable instant (for example request start); pricing never reads a clock. */
public record PricingContext(Instant billableAt, ZoneId zoneId, Map<String, String> attributes) {
    public PricingContext {
        Objects.requireNonNull(billableAt, "billableAt");
        Objects.requireNonNull(zoneId, "zoneId");
        attributes = Map.copyOf(attributes);
    }
    public PricingContext(Instant billableAt, ZoneId zoneId) { this(billableAt, zoneId, Map.of()); }
}
