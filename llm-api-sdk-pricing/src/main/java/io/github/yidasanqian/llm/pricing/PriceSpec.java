package io.github.yidasanqian.llm.pricing;

import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit rates in one currency. A selected tier provides complete rates, without implicit fallbacks. */
public record PriceSpec(Currency currency, Map<String, UnitPrice> rates, List<PriceTier> tiers) {
    public PriceSpec {
        Objects.requireNonNull(currency, "currency");
        rates = Map.copyOf(rates);
        tiers = List.copyOf(tiers);
        for (int i = 0; i < tiers.size(); i++) {
            PriceTier a = tiers.get(i);
            for (int j = i + 1; j < tiers.size(); j++) {
                PriceTier b = tiers.get(j);
                if (a.id().equals(b.id())) throw new IllegalArgumentException("Duplicate tier id: " + a.id());
                if ((a.toExclusive() == null || b.fromInclusive() < a.toExclusive())
                        && (b.toExclusive() == null || a.fromInclusive() < b.toExclusive())) {
                    throw new IllegalArgumentException("Overlapping tiers");
                }
            }
        }
    }
    public PriceSpec(Currency currency, Map<String, UnitPrice> rates) { this(currency, rates, List.of()); }
}
