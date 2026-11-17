package io.github.yidasanqian.llm.pricing;

import io.github.yidasanqian.llm.protocol.Protocol;
import io.github.yidasanqian.llm.usage.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import static io.github.yidasanqian.llm.usage.UsageDimensions.*;
import static org.junit.jupiter.api.Assertions.*;

class PricingStrategyTest {
    private static final Currency USD = Currency.getInstance("USD");
    private static final PricingContext CONTEXT = new PricingContext(Instant.parse("2026-11-16T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final TokenPricingStrategy tokens = new TokenPricingStrategy();
    private static UnitPrice perMillion(String price) { return UnitPrice.perMillionTokens(new BigDecimal(price)); }
    private PricingRequest request(Usage usage, Map<String, UnitPrice> rates) {
        return new PricingRequest(usage, new PriceSpec(USD, rates), CONTEXT);
    }
    @Test void computesDisjointInputOutputAndCacheTtlWithExplicitRates() {
        Usage usage = new UsageParser().parse("""
            {"usage":{"input_tokens":100,"output_tokens":40,"cache_read_input_tokens":1000,"cache_creation_input_tokens":100,"cache_creation":{"ephemeral_5m_input_tokens":30,"ephemeral_1h_input_tokens":50}}}
            """, new UsageContext(Protocol.ANTHROPIC_MESSAGES)).orElseThrow();
        CostBreakdown result = tokens.calculate(request(usage, Map.of(
                INPUT_TOKENS, perMillion("2"), OUTPUT_TOKENS, perMillion("10"),
                CACHE_READ_TOKENS, perMillion("0.2"), CACHE_WRITE_TOKENS, perMillion("3"),
                CACHE_WRITE_5M_TOKENS, perMillion("2.5"), CACHE_WRITE_1H_TOKENS, perMillion("4"))));
        assertEquals(0, new BigDecimal("0.001135").compareTo(result.total()));
        CostLine unknownTtl = result.lines().stream().filter(line -> line.dimension().equals(CACHE_WRITE_TOKENS)).findFirst().orElseThrow();
        assertEquals(new BigDecimal("20"), unknownTtl.usage().quantity());
        assertEquals(UsageSource.UPSTREAM, result.usageSource());
    }
    @Test void contextTierIsNonProgressiveAndSelectsByRawInputIncludingCache() {
        PriceSpec prices = new PriceSpec(USD, Map.of(), List.of(
                new PriceTier("short", 0, 272001L, Map.of(INPUT_TOKENS, perMillion("1"), OUTPUT_TOKENS, perMillion("2"))),
                new PriceTier("long", 272001, null, Map.of(INPUT_TOKENS, perMillion("3"), OUTPUT_TOKENS, perMillion("6")))));
        CostBreakdown shortCost = tokens.calculate(new PricingRequest(Usage.tokens(272000, 1), prices, CONTEXT));
        CostBreakdown longCost = tokens.calculate(new PricingRequest(Usage.tokens(272001, 1), prices, CONTEXT));
        assertEquals("short", shortCost.priceTier());
        assertEquals("long", longCost.priceTier());
        assertEquals(0, new BigDecimal("0.816009").compareTo(longCost.total()));
    }
    @Test void missingPriceIsDifferentFromExplicitZeroPrice() {
        PricingRequest missing = request(Usage.tokens(10, 5), Map.of(INPUT_TOKENS, perMillion("1")));
        assertThrows(PricingException.class, () -> tokens.calculate(missing));
        CostBreakdown freeOutput = tokens.calculate(request(Usage.tokens(10, 5), Map.of(
                INPUT_TOKENS, perMillion("1"), OUTPUT_TOKENS, perMillion("0"))));
        assertEquals(0, new BigDecimal("0.00001").compareTo(freeOutput.total()));
    }
    @Test void incompleteUsageCannotBeQuotedAsZero() {
        Usage partial = Usage.upstream(Map.of(INPUT_TOKENS, UsageMeasurement.tokens(0)));
        assertThrows(PricingException.class, () -> tokens.calculate(request(partial, Map.of())));
        assertEquals(0, tokens.calculate(request(Usage.tokens(0, 0), Map.of())).total().signum());
    }
    @Test void hostCanQuoteCostAndSaleSeparatelyThenApplyFxAndRounding() {
        Usage usage = Usage.tokens(1_000_000, 0);
        CostBreakdown cost = tokens.calculate(request(usage, Map.of(INPUT_TOKENS, perMillion("6"))));
        CostBreakdown sale = tokens.calculate(request(usage, Map.of(INPUT_TOKENS, perMillion("9"))));
        BigDecimal discounted = sale.total().multiply(new BigDecimal("0.8")).setScale(8, RoundingMode.HALF_UP);
        assertEquals(0, new BigDecimal("6").compareTo(cost.total()));
        assertEquals(new BigDecimal("7.20000000"), discounted);
    }
    @Test void missingTierAndOverlappingRangesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PriceSpec(USD, Map.of(), List.of(
                new PriceTier("one", 0, 100L, Map.of()), new PriceTier("two", 50, null, Map.of()))));
        PriceSpec prices = new PriceSpec(USD, Map.of(), List.of(new PriceTier("small", 0, 2L, Map.of())));
        assertThrows(PricingException.class, () -> tokens.calculate(new PricingRequest(Usage.tokens(3, 0), prices, CONTEXT)));
    }
    @Test void requestPricingDoesNotDependOnTokenUsage() {
        PricingRequest request = request(Usage.upstream(Map.of()), Map.of(REQUESTS, UnitPrice.perUnit(new BigDecimal("0.03"), REQUEST_UNIT)));
        assertEquals(0, new BigDecimal("0.03").compareTo(new PerRequestPricingStrategy().calculate(request).total()));
    }
    @Test void unsupportedRerankDimensionsAreNotSilentlyIgnored() {
        Usage usage = new UsageParser().parse("""
            {"meta":{"billed_units":{"input_tokens":10,"search_units":1}}}
            """, new UsageContext(Protocol.RERANK, "siliconflow")).orElseThrow();
        assertThrows(PricingException.class, () -> tokens.calculate(request(usage, Map.of(INPUT_TOKENS, perMillion("1")))));
    }
    @Test void unitsAreCheckedAndFractionalChargesAreNotRoundedEarly() {
        Usage usage = Usage.upstream(Map.of("video_seconds", new UsageMeasurement(new BigDecimal("0.125"), "second")));
        PricingRequest valid = request(usage, Map.of("video_seconds", UnitPrice.perUnit(new BigDecimal("0.0000001"), "second")));
        assertEquals(0, new BigDecimal("0.0000000125").compareTo(new MeteredPricingStrategy().calculate(valid).total()));
        PricingRequest invalid = request(usage, Map.of("video_seconds", UnitPrice.perUnit(BigDecimal.ONE, "frame")));
        assertThrows(IllegalArgumentException.class, () -> new MeteredPricingStrategy().calculate(invalid));
    }
    @Test void customTimeOfUsePolicyUsesExplicitInstantAndTimezone() {
        // Demonstration tariff only. Selection uses request-start time chosen by the host.
        PricingStrategy timeOfUse = request -> {
            int hour = request.context().billableAt().atZone(request.context().zoneId()).getHour();
            BigDecimal amount = hour >= 22 || hour < 8 ? new BigDecimal("1") : new BigDecimal("2");
            PriceSpec selected = new PriceSpec(USD, Map.of(INPUT_TOKENS, UnitPrice.perMillionTokens(amount)));
            return tokens.calculate(new PricingRequest(request.usage(), selected, request.context()));
        };
        Usage usage = Usage.tokens(1_000_000, 0);
        PriceSpec prices = new PriceSpec(USD, Map.of());
        var beforePeak = new PricingContext(Instant.parse("2026-11-15T23:59:59Z"), ZoneId.of("Asia/Shanghai"));
        var atPeak = new PricingContext(Instant.parse("2026-11-16T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        assertEquals(0, new BigDecimal("1").compareTo(timeOfUse.calculate(new PricingRequest(usage, prices, beforePeak)).total()));
        assertEquals(0, new BigDecimal("2").compareTo(timeOfUse.calculate(new PricingRequest(usage, prices, atPeak)).total()));
    }
    @Test void customVideoPolicyUsesFractionalSecondsAndResolutionWithoutSdkChanges() {
        // Demonstration only: not Seedance pricing or a claim about its actual upstream usage contract.
        PricingStrategy video = request -> {
            String resolution = request.usage().measurements().get("video_seconds").attributes().get("resolution");
            BigDecimal rate = switch (resolution) {
                case "720p" -> new BigDecimal("0.1");
                case "1080p" -> new BigDecimal("0.2");
                default -> throw new PricingException("Unsupported resolution");
            };
            PriceSpec selected = new PriceSpec(Currency.getInstance("CNY"), Map.of("video_seconds", UnitPrice.perUnit(rate, "second")));
            return new MeteredPricingStrategy().calculate(new PricingRequest(request.usage(), selected, request.context()));
        };
        Usage usage = Usage.upstream(Map.of("video_seconds", new UsageMeasurement(new BigDecimal("2.5"), "second", Map.of("resolution", "1080p"))));
        CostBreakdown result = video.calculate(request(usage, Map.of()));
        assertEquals(Currency.getInstance("CNY"), result.currency());
        assertEquals(0, new BigDecimal("0.5").compareTo(result.total()));
    }
    @Test void cacheHitsDoNotMoveLongContextIntoShortTier() {
        Usage usage = new UsageParser().parse("""
            {"usage":{"input_tokens":300000,"output_tokens":0,"input_tokens_details":{"cached_tokens":299000}}}
            """, new UsageContext(Protocol.OPENAI_RESPONSES)).orElseThrow();
        PriceSpec prices = new PriceSpec(USD, Map.of(), List.of(
                new PriceTier("short", 0, 272001L, Map.of(INPUT_TOKENS, perMillion("1"), CACHE_READ_TOKENS, perMillion("0.1"))),
                new PriceTier("long", 272001, null, Map.of(INPUT_TOKENS, perMillion("3"), CACHE_READ_TOKENS, perMillion("0.1")))));
        CostBreakdown result = tokens.calculate(new PricingRequest(usage, prices, CONTEXT));
        assertEquals("long", result.priceTier());
        assertEquals(0, new BigDecimal("0.0329").compareTo(result.total()));
    }

    @Test void perMillionChargesRemainExactAtSettlementHalfBoundary() {
        BigDecimal inputRate = new BigDecimal("0.004999999999999999999999999999999999");
        BigDecimal outputRate = new BigDecimal("9E-37");
        BigDecimal million = BigDecimal.valueOf(1_000_000);
        BigDecimal exactTotal = inputRate.add(outputRate).divide(million);
        CostBreakdown quote = tokens.calculate(request(Usage.tokens(1, 1), Map.of(
                INPUT_TOKENS, UnitPrice.perMillionTokens(inputRate),
                OUTPUT_TOKENS, UnitPrice.perMillionTokens(outputRate))));
        assertEquals(0, exactTotal.compareTo(quote.total()));
        // Combining rates with the same denominator must not introduce intermediate rounding.
        BigDecimal combinedCharge = UnitPrice.perMillionTokens(inputRate.add(outputRate)).charge(BigDecimal.ONE);
        assertEquals(0, exactTotal.compareTo(combinedCharge));
        BigDecimal legacy = inputRate.add(outputRate).divide(million, MathContext.DECIMAL128);
        BigDecimal hostRounded = quote.total().round(MathContext.DECIMAL128);
        assertEquals(0, legacy.compareTo(hostRounded));
        assertEquals(new BigDecimal("0.00000001"), hostRounded.setScale(8, RoundingMode.HALF_UP));
        assertEquals(new BigDecimal("0.00000000"), exactTotal.setScale(8, RoundingMode.HALF_UP));
    }
    @Test void nonTerminatingUnitDivisionUsesDecimal128() {
        UnitPrice price = new UnitPrice(BigDecimal.ONE, new BigDecimal("3"), "second");
        BigDecimal expected = BigDecimal.ONE.divide(new BigDecimal("3"), MathContext.DECIMAL128);
        assertEquals(expected, price.charge(BigDecimal.ONE));
    }

}
