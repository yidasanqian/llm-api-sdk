package io.github.yidasanqian.llm.pricing;

import io.github.yidasanqian.llm.protocol.Protocol;
import io.github.yidasanqian.llm.usage.Usage;
import io.github.yidasanqian.llm.usage.UsageContext;
import io.github.yidasanqian.llm.usage.UsageParser;
import io.github.yidasanqian.llm.usage.UsageSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Map;

import static io.github.yidasanqian.llm.usage.UsageDimensions.OUTPUT_TOKENS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeedanceVideoPricingStrategyTest {
    private static final Currency CNY = Currency.getInstance("CNY");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant BILLABLE_AT = Instant.parse("2026-11-17T00:00:00Z");
    private final SeedanceVideoPricingStrategy strategy = new SeedanceVideoPricingStrategy();
    private final PriceSpec prices = SeedanceVideoPricingStrategy.priceSpec(CNY, Map.of(
            SeedanceVideoPricingStrategy.STANDARD_WITHOUT_VIDEO, 70,
            SeedanceVideoPricingStrategy.STANDARD_WITH_VIDEO, 42,
            SeedanceVideoPricingStrategy.FULL_HD_WITHOUT_VIDEO, 77,
            SeedanceVideoPricingStrategy.FULL_HD_WITH_VIDEO, 46));

    @Test void selectsTheFourTiersFromFinalResolutionAndVideoInput() {
        assertTier("480p", false, 70, SeedanceVideoPricingStrategy.STANDARD_WITHOUT_VIDEO);
        assertTier("720p", true, 42, SeedanceVideoPricingStrategy.STANDARD_WITH_VIDEO);
        assertTier("1080p", false, 77, SeedanceVideoPricingStrategy.FULL_HD_WITHOUT_VIDEO);
        assertTier("1080p", true, 46, SeedanceVideoPricingStrategy.FULL_HD_WITH_VIDEO);
    }

    @Test void chargesOnlyAuthoritativeOutputTokensAndKeepsResolutionForAudit() {
        Usage usage = usage(108900, "720p");
        CostBreakdown cost = strategy.calculate(request(usage, false));

        assertEquals(SeedanceVideoPricingStrategy.STANDARD_WITHOUT_VIDEO, cost.priceTier());
        assertEquals(1, cost.lines().size());
        assertEquals("720p", cost.lines().get(0).usage().attributes().get("resolution"));
        assertEquals(0, new BigDecimal("7.623").compareTo(cost.total()));
    }

    @Test void explicitZeroIsBillableButEstimatedOrIncompleteUsageIsNot() {
        CostBreakdown free = strategy.calculate(request(usage(0, "720p"), false));
        assertEquals(0, BigDecimal.ZERO.compareTo(free.total()));

        Usage estimated = new Usage(usage(108900, "720p").measurements(), UsageSource.ESTIMATED);
        assertThrows(PricingException.class, () -> strategy.calculate(request(estimated, false)));
        assertThrows(PricingException.class, () -> strategy.calculate(request(usageWithoutResolution(), false)));
    }

    @Test void videoInputMustComeFromAnExplicitPricingAttribute() {
        Usage usage = usage(1_000_000, "720p");
        assertThrows(PricingException.class, () -> strategy.calculate(new PricingRequest(usage, prices,
                new PricingContext(BILLABLE_AT, ZONE))));
        assertThrows(PricingException.class, () -> strategy.calculate(new PricingRequest(usage, prices,
                new PricingContext(BILLABLE_AT, ZONE, Map.of(SeedanceVideoPricingStrategy.HAS_VIDEO_INPUT, "yes")))));
    }

    @Test void unsupportedResolutionOrIncompleteRateCardFails() {
        assertThrows(PricingException.class, () -> strategy.calculate(request(usage(1, "4k"), false)));

        PriceSpec incomplete = new PriceSpec(CNY, Map.of(
                SeedanceVideoPricingStrategy.STANDARD_WITHOUT_VIDEO, UnitPrice.perMillionTokens(BigDecimal.ONE)));
        PricingRequest validRequest = request(usage(1, "720p"), false);
        assertThrows(PricingException.class, () -> strategy.calculate(new PricingRequest(validRequest.usage(),
                incomplete, validRequest.context())));

        PriceSpec wrongUnit = new PriceSpec(CNY, Map.of(
                SeedanceVideoPricingStrategy.STANDARD_WITHOUT_VIDEO, UnitPrice.perUnit(BigDecimal.ONE, "token"),
                SeedanceVideoPricingStrategy.STANDARD_WITH_VIDEO, UnitPrice.perUnit(BigDecimal.ONE, "token"),
                SeedanceVideoPricingStrategy.FULL_HD_WITHOUT_VIDEO, UnitPrice.perUnit(BigDecimal.ONE, "token"),
                SeedanceVideoPricingStrategy.FULL_HD_WITH_VIDEO, UnitPrice.perUnit(BigDecimal.ONE, "token")));
        assertThrows(PricingException.class, () -> strategy.calculate(new PricingRequest(validRequest.usage(),
                wrongUnit, validRequest.context())));
    }

    @Test void rateCardRequiresExactlyFourExplicitNonNegativePrices() {
        assertEquals(4, prices.rates().size());
        assertThrows(IllegalArgumentException.class, () -> SeedanceVideoPricingStrategy.priceSpec(CNY, Map.of(
                SeedanceVideoPricingStrategy.STANDARD_WITHOUT_VIDEO, 1)));
        assertThrows(IllegalArgumentException.class, () -> SeedanceVideoPricingStrategy.priceSpec(CNY, Map.of(
                SeedanceVideoPricingStrategy.STANDARD_WITHOUT_VIDEO, -1,
                SeedanceVideoPricingStrategy.STANDARD_WITH_VIDEO, 1,
                SeedanceVideoPricingStrategy.FULL_HD_WITHOUT_VIDEO, 1,
                SeedanceVideoPricingStrategy.FULL_HD_WITH_VIDEO, 1)));
        assertThrows(IllegalArgumentException.class, () -> SeedanceVideoPricingStrategy.priceSpec(CNY, Map.of(
                SeedanceVideoPricingStrategy.STANDARD_WITHOUT_VIDEO, 1,
                SeedanceVideoPricingStrategy.STANDARD_WITH_VIDEO, 1,
                SeedanceVideoPricingStrategy.FULL_HD_WITHOUT_VIDEO, 1,
                SeedanceVideoPricingStrategy.FULL_HD_WITH_VIDEO, 1,
                "extra", 1)));
    }

    private void assertTier(String resolution, boolean hasVideoInput, int expected, String expectedTier) {
        CostBreakdown cost = strategy.calculate(request(usage(1_000_000, resolution), hasVideoInput));
        assertEquals(expectedTier, cost.priceTier());
        assertEquals(0, BigDecimal.valueOf(expected).compareTo(cost.total()));
    }

    private PricingRequest request(Usage usage, boolean hasVideoInput) {
        return new PricingRequest(usage, prices, new PricingContext(BILLABLE_AT, ZONE,
                Map.of(SeedanceVideoPricingStrategy.HAS_VIDEO_INPUT, Boolean.toString(hasVideoInput))));
    }

    private static Usage usage(long completionTokens, String resolution) {
        return new UsageParser().parse("""
                {"id":"cgt-1","status":"succeeded","resolution":"%s","usage":{"completion_tokens":%d,"total_tokens":%d}}
                """.formatted(resolution, completionTokens, completionTokens),
                new UsageContext(Protocol.SEEDANCE_VIDEO)).orElseThrow();
    }

    private static Usage usageWithoutResolution() {
        return new UsageParser().parse("""
                {"id":"cgt-1","status":"succeeded","usage":{"completion_tokens":100}}
                """, new UsageContext(Protocol.SEEDANCE_VIDEO)).orElseThrow();
    }
}
