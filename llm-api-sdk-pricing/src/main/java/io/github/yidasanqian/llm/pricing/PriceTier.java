package io.github.yidasanqian.llm.pricing;

import java.util.Map;
import java.util.Objects;

/** Non-progressive context tier: inclusive lower bound, exclusive upper bound; null upper means unbounded. */
public record PriceTier(String id, long fromInclusive, Long toExclusive, Map<String, UnitPrice> rates) {
    public PriceTier {
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || fromInclusive < 0 || toExclusive != null && toExclusive <= fromInclusive) throw new IllegalArgumentException("Invalid price tier");
        rates = Map.copyOf(rates);
    }
    public boolean matches(long tokens) { return tokens >= fromInclusive && (toExclusive == null || tokens < toExclusive); }
}
