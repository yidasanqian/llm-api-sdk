package io.github.yidasanqian.llm.usage;

import java.util.Map;
import java.util.Objects;

/** Missing meters remain absent; an explicit zero meter is authoritative zero usage. */
public record Usage(Map<String, UsageMeasurement> measurements, UsageSource source) {
    public Usage {
        measurements = Map.copyOf(measurements);
        Objects.requireNonNull(source, "source");
    }
    public static Usage upstream(Map<String, UsageMeasurement> measurements) {
        return new Usage(measurements, UsageSource.UPSTREAM);
    }
    public static Usage tokens(long input, long output) {
        return upstream(Map.of(UsageDimensions.RAW_INPUT_TOKENS, UsageMeasurement.tokens(input),
                UsageDimensions.INPUT_TOKENS, UsageMeasurement.tokens(input),
                UsageDimensions.OUTPUT_TOKENS, UsageMeasurement.tokens(output),
                UsageDimensions.TIER_BASIS_TOKENS, UsageMeasurement.tokens(input)));
    }
    public Long token(String dimension) {
        UsageMeasurement value = measurements.get(dimension);
        if (value == null) return null;
        if (!UsageDimensions.TOKEN_UNIT.equals(value.unit())) throw new IllegalArgumentException("Not a token meter: " + dimension);
        return value.quantity().longValueExact();
    }
}
