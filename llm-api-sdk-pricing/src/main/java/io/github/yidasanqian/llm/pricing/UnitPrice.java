package io.github.yidasanqian.llm.pricing;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/** amount in PriceSpec.currency per perQuantity units; a zero amount is an explicit free price. */
public record UnitPrice(BigDecimal amount, BigDecimal perQuantity, String unit) {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    public UnitPrice {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(perQuantity, "perQuantity");
        Objects.requireNonNull(unit, "unit");
        if (amount.signum() < 0 || perQuantity.signum() <= 0 || unit.isBlank()) throw new IllegalArgumentException("Invalid unit price");
    }
    public static UnitPrice perMillionTokens(BigDecimal amount) { return new UnitPrice(amount, MILLION, "token"); }
    public static UnitPrice perUnit(BigDecimal amount, String unit) { return new UnitPrice(amount, BigDecimal.ONE, unit); }
    public BigDecimal charge(BigDecimal quantity) {
        if (quantity.signum() < 0) throw new IllegalArgumentException("Negative quantity");
        BigDecimal numerator = amount.multiply(quantity);
        try {
            return numerator.divide(perQuantity);
        } catch (ArithmeticException nonTerminatingDivision) {
            return numerator.divide(perQuantity, MathContext.DECIMAL128);
        }
    }
}
