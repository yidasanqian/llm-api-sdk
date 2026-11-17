package io.github.yidasanqian.llm.pricing;

import io.github.yidasanqian.llm.usage.Usage;
import io.github.yidasanqian.llm.usage.UsageMeasurement;
import io.github.yidasanqian.llm.usage.UsageSource;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.yidasanqian.llm.usage.UsageDimensions.OUTPUT_TOKENS;
import static io.github.yidasanqian.llm.usage.UsageDimensions.TOKEN_UNIT;
import static io.github.yidasanqian.llm.usage.UsageDimensions.TOTAL_TOKENS;

/**
 * Prices successful Seedance video output tokens from four caller-supplied rates.
 * Currency conversion and settlement rounding remain host responsibilities.
 */
public final class SeedanceVideoPricingStrategy implements PricingStrategy {
    public static final String STANDARD_WITHOUT_VIDEO = "standardWithoutVideo";
    public static final String STANDARD_WITH_VIDEO = "standardWithVideo";
    public static final String FULL_HD_WITHOUT_VIDEO = "fullHdWithoutVideo";
    public static final String FULL_HD_WITH_VIDEO = "fullHdWithVideo";
    public static final String HAS_VIDEO_INPUT = "has_video_input";

    private static final String RESOLUTION = "resolution";
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final List<String> PRICE_KEYS = List.of(STANDARD_WITHOUT_VIDEO, STANDARD_WITH_VIDEO,
            FULL_HD_WITHOUT_VIDEO, FULL_HD_WITH_VIDEO);
    private static final Set<String> ALLOWED_METERS = Set.of(OUTPUT_TOKENS, TOTAL_TOKENS);

    /** Builds an explicit per-million-output-token rate card from the four required prices. */
    public static PriceSpec priceSpec(Currency currency, Map<String, ? extends Number> prices) {
        if (prices == null || prices.size() != PRICE_KEYS.size() || !prices.keySet().containsAll(PRICE_KEYS)) {
            throw new IllegalArgumentException("Seedance video pricing requires exactly four prices");
        }
        Map<String, UnitPrice> rates = new LinkedHashMap<>();
        for (String key : PRICE_KEYS) {
            Number value = prices.get(key);
            if (value == null) throw new IllegalArgumentException("Missing Seedance video price: " + key);
            BigDecimal amount = value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
            rates.put(key, UnitPrice.perMillionTokens(amount));
        }
        return new PriceSpec(currency, rates);
    }

    @Override
    public CostBreakdown calculate(PricingRequest request) {
        if (!request.prices().tiers().isEmpty()) {
            throw new PricingException("Seedance video pricing does not support context tiers");
        }
        Usage usage = request.usage();
        if (usage.source() != UsageSource.UPSTREAM) {
            throw new PricingException("Seedance video usage must come from upstream");
        }
        UsageMeasurement output = usage.measurements().get(OUTPUT_TOKENS);
        if (output == null) throw new PricingException("Seedance video output token usage is missing");
        if (!TOKEN_UNIT.equals(output.unit())) throw new PricingException("Seedance video output usage must be tokens");
        try {
            output.quantity().longValueExact();
        } catch (ArithmeticException error) {
            throw new PricingException("Seedance video output token usage must be an integer");
        }
        usage.measurements().forEach((dimension, measurement) -> {
            if (!ALLOWED_METERS.contains(dimension) && measurement.quantity().signum() != 0) {
                throw new PricingException("Unsupported Seedance video billing meter: " + dimension);
            }
        });
        String resolution = output.attributes().get(RESOLUTION);
        if (resolution == null || resolution.isBlank()) {
            throw new PricingException("Seedance video output resolution is missing");
        }
        boolean hasVideoInput = hasVideoInput(request.context().attributes().get(HAS_VIDEO_INPUT));
        String priceKey = priceKey(resolution, hasVideoInput);
        for (String key : PRICE_KEYS) {
            UnitPrice rate = request.prices().rates().get(key);
            if (rate == null) throw new PricingException("Missing Seedance video price: " + key);
            if (!TOKEN_UNIT.equals(rate.unit()) || rate.perQuantity().compareTo(MILLION) != 0) {
                throw new PricingException("Seedance video price must be per million tokens: " + key);
            }
        }
        UnitPrice selected = request.prices().rates().get(priceKey);
        return new CostBreakdown(request.prices().currency(),
                List.of(new CostLine(OUTPUT_TOKENS, output, selected)), priceKey, usage.source());
    }

    private static String priceKey(String resolution, boolean hasVideoInput) {
        String tier = switch (resolution) {
            case "480p", "720p" -> "standard";
            case "1080p" -> "fullHd";
            default -> throw new PricingException("Unsupported Seedance video resolution: " + resolution);
        };
        return tier + (hasVideoInput ? "WithVideo" : "WithoutVideo");
    }

    private static boolean hasVideoInput(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new PricingException("Pricing attribute has_video_input must be true or false");
    }
}
