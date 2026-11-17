package io.github.yidasanqian.llm.pricing;

import io.github.yidasanqian.llm.usage.UsageMeasurement;
import java.math.BigDecimal;
import java.util.Objects;

public record CostLine(String dimension, UsageMeasurement usage, UnitPrice price) {
    public CostLine {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(price, "price");
        if (!usage.unit().equals(price.unit())) throw new IllegalArgumentException("Unit mismatch for " + dimension);
    }
    public BigDecimal amount() { return price.charge(usage.quantity()); }
}
