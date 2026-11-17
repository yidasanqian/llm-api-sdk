package io.github.yidasanqian.llm.pricing;

import io.github.yidasanqian.llm.usage.Usage;
import io.github.yidasanqian.llm.usage.UsageMeasurement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import static io.github.yidasanqian.llm.usage.UsageDimensions.*;

/** Prices disjoint input/output/cache meters; context tiers select one rate set for all tokens. */
public final class TokenPricingStrategy implements PricingStrategy {
    private static final Set<String> SUPPORTED = Set.of(RAW_INPUT_TOKENS, INPUT_TOKENS, OUTPUT_TOKENS,
            TOTAL_TOKENS, CACHE_READ_TOKENS, CACHE_WRITE_TOKENS, CACHE_WRITE_5M_TOKENS, CACHE_WRITE_1H_TOKENS, TIER_BASIS_TOKENS);

    @Override public CostBreakdown calculate(PricingRequest request) {
        Usage usage = request.usage();
        if (usage.token(INPUT_TOKENS) == null || usage.token(OUTPUT_TOKENS) == null) throw new PricingException("Input/output usage is incomplete");
        usage.measurements().forEach((dimension, value) -> {
            if (!SUPPORTED.contains(dimension) && value.quantity().signum() != 0) throw new PricingException("Unsupported token billing meter: " + dimension);
        });
        Map<String, UnitPrice> rates = request.prices().rates();
        String tierId = null;
        if (!request.prices().tiers().isEmpty()) {
            Long basis = usage.token(TIER_BASIS_TOKENS);
            if (basis == null) throw new PricingException("Context tier basis is missing");
            PriceTier tier = request.prices().tiers().stream().filter(value -> value.matches(basis)).findFirst()
                    .orElseThrow(() -> new PricingException("No matching context tier"));
            rates = tier.rates();
            tierId = tier.id();
        }
        var billable = new LinkedHashMap<String, UsageMeasurement>();
        billable.put(INPUT_TOKENS, usage.measurements().get(INPUT_TOKENS));
        billable.put(OUTPUT_TOKENS, usage.measurements().get(OUTPUT_TOKENS));
        include(billable, usage, CACHE_READ_TOKENS);
        long five = zero(usage.token(CACHE_WRITE_5M_TOKENS));
        long hour = zero(usage.token(CACHE_WRITE_1H_TOKENS));
        long breakdown = Math.addExact(five, hour);
        Long totalWrite = usage.token(CACHE_WRITE_TOKENS);
        if (totalWrite != null && breakdown > totalWrite) throw new PricingException("Cache TTL quantities exceed total cache writes");
        if (breakdown > 0) {
            include(billable, usage, CACHE_WRITE_5M_TOKENS);
            include(billable, usage, CACHE_WRITE_1H_TOKENS);
            if (totalWrite != null) billable.put(CACHE_WRITE_TOKENS, UsageMeasurement.tokens(totalWrite - breakdown));
        } else include(billable, usage, CACHE_WRITE_TOKENS);
        var lines = new ArrayList<CostLine>();
        for (var entry : billable.entrySet()) {
            UnitPrice price = rates.get(entry.getKey());
            if (price == null) {
                if (entry.getValue().quantity().signum() != 0) throw new PricingException("Missing price: " + entry.getKey());
            } else lines.add(new CostLine(entry.getKey(), entry.getValue(), price));
        }
        return new CostBreakdown(request.prices().currency(), lines, tierId, usage.source());
    }
    private static long zero(Long value) { return value == null ? 0 : value; }
    private static void include(Map<String, UsageMeasurement> values, Usage usage, String key) {
        UsageMeasurement value = usage.measurements().get(key);
        if (value != null) values.put(key, value);
    }
}
