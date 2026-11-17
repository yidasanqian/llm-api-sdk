package io.github.yidasanqian.llm.pricing;

/** Implement this interface for time-of-use, video or other pricing policies. No registry is required. */
@FunctionalInterface
public interface PricingStrategy {
    CostBreakdown calculate(PricingRequest request);
}
