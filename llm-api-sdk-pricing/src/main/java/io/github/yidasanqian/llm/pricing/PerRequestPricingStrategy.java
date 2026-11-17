package io.github.yidasanqian.llm.pricing;

import io.github.yidasanqian.llm.usage.UsageMeasurement;
import java.math.BigDecimal;
import java.util.List;
import static io.github.yidasanqian.llm.usage.UsageDimensions.*;

/** Computes one invocation's fee; deciding whether an invocation is billable remains the host's policy. */
public final class PerRequestPricingStrategy implements PricingStrategy {
    @Override public CostBreakdown calculate(PricingRequest request) {
        if (!request.prices().tiers().isEmpty()) throw new PricingException("Per-request pricing does not use token tiers");
        UnitPrice price = request.prices().rates().get(REQUESTS);
        if (price == null) throw new PricingException("Missing request price");
        CostLine line = new CostLine(REQUESTS, new UsageMeasurement(BigDecimal.ONE, REQUEST_UNIT), price);
        return new CostBreakdown(request.prices().currency(), List.of(line), null, request.usage().source());
    }
}
