package io.github.yidasanqian.llm.pricing;

import java.util.ArrayList;

/** Prices every supplied meter. Useful for video seconds, frames and other extensible billing units. */
public final class MeteredPricingStrategy implements PricingStrategy {
    @Override public CostBreakdown calculate(PricingRequest request) {
        if (!request.prices().tiers().isEmpty()) throw new PricingException("Select context tiers before generic metered pricing");
        if (request.usage().measurements().isEmpty()) throw new PricingException("Usage is missing");
        var lines = new ArrayList<CostLine>();
        request.usage().measurements().forEach((dimension, usage) -> {
            UnitPrice price = request.prices().rates().get(dimension);
            if (price == null) {
                if (usage.quantity().signum() != 0) throw new PricingException("Missing price: " + dimension);
            } else lines.add(new CostLine(dimension, usage, price));
        });
        return new CostBreakdown(request.prices().currency(), lines, null, request.usage().source());
    }
}
