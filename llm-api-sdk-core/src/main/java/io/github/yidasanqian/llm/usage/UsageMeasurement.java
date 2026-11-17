package io.github.yidasanqian.llm.usage;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/** A measured quantity, for example tokens, video seconds, frames or requests. */
public record UsageMeasurement(BigDecimal quantity, String unit, Map<String, String> attributes) {
    public UsageMeasurement {
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unit, "unit");
        if (quantity.signum() < 0 || unit.isBlank()) throw new IllegalArgumentException("Invalid measurement");
        attributes = Map.copyOf(attributes);
    }
    public UsageMeasurement(BigDecimal quantity, String unit) { this(quantity, unit, Map.of()); }
    public static UsageMeasurement tokens(long quantity) {
        return new UsageMeasurement(BigDecimal.valueOf(quantity), UsageDimensions.TOKEN_UNIT);
    }
}
