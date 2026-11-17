package io.github.yidasanqian.llm.pricing;

import io.github.yidasanqian.llm.usage.Usage;
import java.util.Objects;

public record PricingRequest(Usage usage, PriceSpec prices, PricingContext context) {
    public PricingRequest {
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(prices, "prices");
        Objects.requireNonNull(context, "context");
    }
}
