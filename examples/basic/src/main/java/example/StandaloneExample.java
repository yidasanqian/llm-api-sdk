package example;

import io.github.yidasanqian.llm.bridge.ProtocolBridge;
import io.github.yidasanqian.llm.client.LlmClient;
import io.github.yidasanqian.llm.client.LlmEndpoint;
import io.github.yidasanqian.llm.client.LlmRequest;
import io.github.yidasanqian.llm.pricing.CostBreakdown;
import io.github.yidasanqian.llm.pricing.PriceSpec;
import io.github.yidasanqian.llm.pricing.PricingContext;
import io.github.yidasanqian.llm.pricing.PricingRequest;
import io.github.yidasanqian.llm.pricing.SeedanceVideoPricingStrategy;
import io.github.yidasanqian.llm.pricing.TokenPricingStrategy;
import io.github.yidasanqian.llm.pricing.UnitPrice;
import io.github.yidasanqian.llm.protocol.Protocol;
import io.github.yidasanqian.llm.usage.UsageContext;
import io.github.yidasanqian.llm.usage.UsageParser;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static io.github.yidasanqian.llm.usage.UsageDimensions.INPUT_TOKENS;
import static io.github.yidasanqian.llm.usage.UsageDimensions.OUTPUT_TOKENS;

/** Standalone consumer, with no dependency on the SDK parent or a Spring application. */
public final class StandaloneExample {
    private StandaloneExample() { }

    public static CostBreakdown quoteFixture() {
        var usage = new UsageParser().parse("""
                {"usage":{"input_tokens":1000,"output_tokens":100,"total_tokens":1100}}
                """, new UsageContext(Protocol.OPENAI_RESPONSES)).orElseThrow();
        var prices = new PriceSpec(Currency.getInstance("USD"), Map.of(
                INPUT_TOKENS, UnitPrice.perMillionTokens(new BigDecimal("2")),
                OUTPUT_TOKENS, UnitPrice.perMillionTokens(new BigDecimal("6"))));
        var context = new PricingContext(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);
        return new TokenPricingStrategy().calculate(new PricingRequest(usage, prices, context));
    }

    public static Map<String, Object> bridgeFixture() {
        return new ProtocolBridge(Protocol.OPENAI_CHAT, Protocol.OPENAI_RESPONSES)
                .mapRequest(Map.of("messages", List.of(Map.of("role", "user", "content", "Hello"))), false);
    }

    public static CostBreakdown quoteSeedanceFixture() {
        var usage = new UsageParser().parse("""
                {"id":"cgt-fixture","status":"succeeded","resolution":"720p",
                 "usage":{"completion_tokens":108900,"total_tokens":108900}}
                """, new UsageContext(Protocol.SEEDANCE_VIDEO)).orElseThrow();
        var prices = SeedanceVideoPricingStrategy.priceSpec(Currency.getInstance("CNY"), Map.of(
                SeedanceVideoPricingStrategy.STANDARD_WITHOUT_VIDEO, 70,
                SeedanceVideoPricingStrategy.STANDARD_WITH_VIDEO, 42,
                SeedanceVideoPricingStrategy.FULL_HD_WITHOUT_VIDEO, 77,
                SeedanceVideoPricingStrategy.FULL_HD_WITH_VIDEO, 46));
        var context = new PricingContext(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC,
                Map.of(SeedanceVideoPricingStrategy.HAS_VIDEO_INPUT, "false"));
        return new SeedanceVideoPricingStrategy().calculate(new PricingRequest(usage, prices, context));
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println(bridgeFixture());
            System.out.println("Illustrative fixture cost: " + quoteFixture().total() + " USD");
            System.out.println("Illustrative Seedance cost: " + quoteSeedanceFixture().total() + " CNY");
            return;
        }
        if (!"call".equals(args[0])) throw new IllegalArgumentException("Use no arguments or 'call'");
        var endpoint = LlmEndpoint.builder(Protocol.OPENAI_CHAT, URI.create(requiredEnv("LLM_ENDPOINT")))
                .model(requiredEnv("LLM_MODEL")).apiKey(requiredEnv("LLM_API_KEY")).build();
        try (var client = LlmClient.builder().build()) {
            var response = client.execute(new LlmRequest(endpoint,
                    Map.of("messages", List.of(Map.of("role", "user", "content", "Hello")))))
                    .block(Duration.ofSeconds(60));
            System.out.println("HTTP " + response.statusCode());
            System.out.println(response.body());
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + name);
        return value;
    }
}
