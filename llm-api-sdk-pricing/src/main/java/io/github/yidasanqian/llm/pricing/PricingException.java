package io.github.yidasanqian.llm.pricing;

/** Missing usage, missing prices or unsupported measurements prevent a complete quote. */
public final class PricingException extends IllegalArgumentException {
    public PricingException(String message) { super(message); }
}
