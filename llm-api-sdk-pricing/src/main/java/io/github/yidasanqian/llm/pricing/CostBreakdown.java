package io.github.yidasanqian.llm.pricing;

import io.github.yidasanqian.llm.usage.UsageSource;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/** Amounts retain calculation precision; the host applies settlement rounding and currency conversion. */
public record CostBreakdown(Currency currency, List<CostLine> lines, String priceTier, UsageSource usageSource) {
    public CostBreakdown {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(usageSource, "usageSource");
        lines = List.copyOf(lines);
    }
    public BigDecimal total() { return lines.stream().map(CostLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add); }
}
