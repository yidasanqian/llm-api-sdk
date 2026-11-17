package io.github.yidasanqian.llm.usage;

import io.github.yidasanqian.llm.protocol.Protocol;
import org.junit.jupiter.api.Test;
import static io.github.yidasanqian.llm.usage.UsageDimensions.*;
import static org.junit.jupiter.api.Assertions.*;

class UsageParserTest {
    private final UsageParser parser = new UsageParser();
    private Usage parse(String body, Protocol protocol) {
        return parser.parse(body, new UsageContext(protocol)).orElseThrow();
    }
    @Test void preservesMissingVersusAuthoritativeZero() {
        assertTrue(parser.parse("{}", new UsageContext(Protocol.OPENAI_CHAT)).isEmpty());
        assertTrue(parser.parse("{\"usage\":{}}", new UsageContext(Protocol.OPENAI_CHAT)).isEmpty());
        Usage usage = parse("{\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}}", Protocol.OPENAI_CHAT);
        assertEquals(0L, usage.token(INPUT_TOKENS));
        assertEquals(0L, usage.token(OUTPUT_TOKENS));
        assertNull(usage.token(TOTAL_TOKENS));
    }
    @Test void openAiSeparatesBillableInputAndTierBasis() {
        Usage usage = parse("""
            {"usage":{"input_tokens":1000,"output_tokens":30,"input_tokens_details":{"cached_tokens":600,"cache_write_tokens":100}}}
            """, Protocol.OPENAI_RESPONSES);
        assertEquals(1000L, usage.token(RAW_INPUT_TOKENS));
        assertEquals(300L, usage.token(INPUT_TOKENS));
        assertEquals(1000L, usage.token(TIER_BASIS_TOKENS));
        assertEquals(600L, usage.token(CACHE_READ_TOKENS));
    }
    @Test void anthropicCombinesTtlAndIterationCountersWithoutDoubleCounting() {
        Usage usage = parse("""
            {"usage":{"input_tokens":99,"output_tokens":99,"iterations":[{"input_tokens":3,"output_tokens":4},{"input_tokens":5,"output_tokens":6}],"cache_read_input_tokens":10,"cache_creation":{"ephemeral_5m_input_tokens":20,"ephemeral_1h_input_tokens":30}}}
            """, Protocol.ANTHROPIC_MESSAGES);
        assertEquals(8L, usage.token(INPUT_TOKENS));
        assertEquals(10L, usage.token(OUTPUT_TOKENS));
        assertEquals(50L, usage.token(CACHE_WRITE_TOKENS));
        assertEquals(68L, usage.token(TIER_BASIS_TOKENS));
        assertEquals(78L, usage.token(TOTAL_TOKENS));
    }
    @Test void geminiIncludesThoughtsAndExcludesCachedInput() {
        Usage usage = parse("""
            {"usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":40,"candidatesTokenCount":10,"thoughtsTokenCount":20,"totalTokenCount":130}}
            """, Protocol.GEMINI);
        assertEquals(60L, usage.token(INPUT_TOKENS));
        assertEquals(30L, usage.token(OUTPUT_TOKENS));
    }
    @Test void inputOnlyEndpointsAndProviderSpecificRerankArePreserved() {
        Usage embedding = parse("{\"usage\":{\"prompt_tokens\":12,\"total_tokens\":12}}", Protocol.EMBEDDINGS);
        assertEquals(0L, embedding.token(OUTPUT_TOKENS));
        Usage rerank = parser.parse("{\"usage\":{\"total_tokens\":25}}", new UsageContext(Protocol.RERANK, "aliyun-bailian")).orElseThrow();
        assertEquals(25L, rerank.token(INPUT_TOKENS));
        Usage silicon = parser.parse("{\"meta\":{\"billed_units\":{\"input_tokens\":7},\"tokens\":{\"input_tokens\":20}}}", new UsageContext(Protocol.RERANK, "siliconflow")).orElseThrow();
        assertEquals(7L, silicon.token(INPUT_TOKENS));
    }
    @Test void invalidNumbersAreNotConvertedToFreeUsage() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"usage\":{\"prompt_tokens\":-1}}", Protocol.OPENAI_CHAT));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"usage\":{\"prompt_tokens\":\"unknown\"}}", Protocol.OPENAI_CHAT));
        assertTrue(parser.parse("not-json", new UsageContext(Protocol.OPENAI_CHAT)).isEmpty());
    }
    @Test void longCountersDoNotOverflowAtIntegerMaxValue() {
        Usage usage = parse("{\"usage\":{\"prompt_tokens\":3000000000,\"completion_tokens\":1}}", Protocol.OPENAI_CHAT);
        assertEquals(3000000000L, usage.token(INPUT_TOKENS));
    }
    @Test void customProtocolUsageDecoderWorksWithoutCoreChanges() {
        Protocol video = Protocol.of("example-video");
        UsageContext context = new UsageContext(video);
        String body = "{\"duration_seconds\":2.5}";
        assertTrue(parser.parse(body, context).isEmpty());
        UsageParser extension = parser.withDecoder(video, (root, ignored) -> java.util.Optional.of(
                Usage.upstream(java.util.Map.of("video_seconds", new UsageMeasurement(root.path("duration_seconds").decimalValue(), "second")))));
        Usage usage = extension.parse(body, context).orElseThrow();
        assertEquals(new java.math.BigDecimal("2.5"), usage.measurements().get("video_seconds").quantity());
        assertTrue(parser.parse(body, context).isEmpty());
    }

    @Test void seedanceVideoUsageIsAuthoritativeOnlyAfterSucceeded() {
        Usage usage = parse("""
            {"id":"cgt-1","status":"succeeded","resolution":"720p","usage":{"completion_tokens":108900,"total_tokens":108900}}
            """, Protocol.SEEDANCE_VIDEO);
        assertEquals(108900L, usage.token(OUTPUT_TOKENS));
        assertEquals(108900L, usage.token(TOTAL_TOKENS));
        assertNull(usage.token(INPUT_TOKENS));
        assertEquals("720p", usage.measurements().get(OUTPUT_TOKENS).attributes().get("resolution"));
        assertTrue(parser.parse("""
            {"id":"cgt-1","status":"running","resolution":"720p","usage":{"completion_tokens":108900}}
            """, new UsageContext(Protocol.SEEDANCE_VIDEO)).isEmpty());
        assertTrue(parser.parse("""
            {"id":"cgt-1","status":"succeeded","resolution":"720p"}
            """, new UsageContext(Protocol.SEEDANCE_VIDEO)).isEmpty());
    }

    @Test void seedanceVideoKeepsExplicitZeroAndRejectsInvalidCounters() {
        Usage zero = parse("""
            {"id":"cgt-1","status":"succeeded","resolution":"1080p","usage":{"completion_tokens":0}}
            """, Protocol.SEEDANCE_VIDEO);
        assertEquals(0L, zero.token(OUTPUT_TOKENS));
        assertNull(zero.token(TOTAL_TOKENS));
        assertThrows(IllegalArgumentException.class, () -> parse("""
            {"id":"cgt-1","status":"succeeded","resolution":"720p","usage":{"completion_tokens":-1}}
            """, Protocol.SEEDANCE_VIDEO));
        assertThrows(IllegalArgumentException.class, () -> parse("""
            {"id":"cgt-1","status":"succeeded","resolution":"720p","usage":{"completion_tokens":1.5}}
            """, Protocol.SEEDANCE_VIDEO));
        assertThrows(IllegalArgumentException.class, () -> parse("""
            {"id":"cgt-1","status":"succeeded","resolution":"720p","usage":{"completion_tokens":"100"}}
            """, Protocol.SEEDANCE_VIDEO));
    }

    @Test void chatKeepsInputDetailsCacheWhenPromptDetailsHaveOtherFields() {
        Usage usage = parse("""
            {"usage":{"prompt_tokens":100,"completion_tokens":5,"prompt_tokens_details":{"audio_tokens":0},"input_tokens_details":{"cached_tokens":40}}}
            """, Protocol.OPENAI_CHAT);
        assertEquals(60L, usage.token(INPUT_TOKENS));
        assertEquals(40L, usage.token(CACHE_READ_TOKENS));
    }

}
