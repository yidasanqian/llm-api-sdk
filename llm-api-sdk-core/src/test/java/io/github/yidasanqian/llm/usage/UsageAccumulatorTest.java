package io.github.yidasanqian.llm.usage;

import io.github.yidasanqian.llm.protocol.Protocol;
import io.github.yidasanqian.llm.protocol.SseEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicInteger;
import static io.github.yidasanqian.llm.usage.UsageDimensions.*;
import static org.junit.jupiter.api.Assertions.*;

class UsageAccumulatorTest {
    private void accept(UsageAccumulator state, String data) { state.accept(SseEvent.builder(data).build()); }
    @Test void capturesChatUsageAfterToolCallsAndBeforeDone() {
        UsageAccumulator state = new UsageAccumulator(new UsageContext(Protocol.OPENAI_CHAT));
        accept(state, "{\"choices\":[{\"finish_reason\":\"tool_calls\"}]}");
        assertFalse(state.terminal());
        accept(state, "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3}}");
        assertEquals(3L, state.confirmedUsage().orElseThrow().token(OUTPUT_TOKENS));
        accept(state, "[DONE]");
        assertTrue(state.terminal());
        assertEquals(10L, state.confirmedUsage().orElseThrow().token(INPUT_TOKENS));
    }
    @Test void responsesDoneDoesNotHideIncompleteUsage() {
        UsageAccumulator state = new UsageAccumulator(new UsageContext(Protocol.OPENAI_RESPONSES));
        accept(state, "[DONE]");
        assertFalse(state.terminal());
        accept(state, "{\"type\":\"response.incomplete\",\"response\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}}");
        assertTrue(state.terminal());
        assertEquals(5L, state.confirmedUsage().orElseThrow().token(OUTPUT_TOKENS));
    }
    @Test void anthropicMergesStartAndCumulativeDeltaNotTheirSum() {
        UsageAccumulator state = new UsageAccumulator(new UsageContext(Protocol.ANTHROPIC_MESSAGES));
        accept(state, "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":0,\"cache_read_input_tokens\":20}}}");
        assertTrue(state.confirmedUsage().isEmpty());
        assertEquals(10L, state.latestUsage().orElseThrow().token(INPUT_TOKENS));
        accept(state, "{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":3}}");
        accept(state, "{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":7}}");
        Usage usage = state.confirmedUsage().orElseThrow();
        assertEquals(7L, usage.token(OUTPUT_TOKENS));
        assertEquals(37L, usage.token(TOTAL_TOKENS));
        accept(state, "{\"type\":\"message_stop\"}");
        assertTrue(state.terminal());
    }

    @Test void anthropicSnapshotRetainsOnlySupportedCounters() {
        var maxRetainedFields = new AtomicInteger();
        var maxRetainedCharacters = new AtomicInteger();
        UsageParser defaults = new UsageParser();
        UsageParser observing = defaults.withDecoder(Protocol.ANTHROPIC_MESSAGES, (root, context) -> {
            maxRetainedFields.accumulateAndGet(root.path("usage").size(), Math::max);
            maxRetainedCharacters.accumulateAndGet(root.toString().length(), Math::max);
            return defaults.parse(root, context);
        });
        UsageAccumulator state = new UsageAccumulator(new UsageContext(Protocol.ANTHROPIC_MESSAGES), observing);
        accept(state, "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}");
        for (int i = 0; i < 128; i++) {
            accept(state, "{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":" + i
                    + ",\"unknown_" + i + "\":\"" + "x".repeat(1024) + "\"}}");
        }
        assertEquals(127L, state.confirmedUsage().orElseThrow().token(OUTPUT_TOKENS));
        assertEquals(2, maxRetainedFields.get());
        assertTrue(maxRetainedCharacters.get() < 100, "Unknown JSON must not be retained between events");
    }

    @Test void streamParsingHonorsConfiguredDepthConstraints() {
        var mapper = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(8).build()).build()).build();
        UsageParser parser = new UsageParser(mapper);
        String nested = "{\"unused\":".repeat(32) + "0" + "}".repeat(32);
        for (Protocol protocol : new Protocol[]{Protocol.OPENAI_CHAT, Protocol.OPENAI_RESPONSES,
                Protocol.ANTHROPIC_MESSAGES, Protocol.GEMINI}) {
            String body = "{\"type\":\"message_delta\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1},"
                    + "\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":1},\"unknown\":" + nested + "}";
            UsageContext context = new UsageContext(protocol);
            assertTrue(parser.parse(body, context).isEmpty());
            UsageAccumulator state = new UsageAccumulator(context, parser);
            accept(state, body);
            assertTrue(state.latestUsage().isEmpty(), "Stream constraints must match JSON constraints: " + protocol);
            assertTrue(state.confirmedUsage().isEmpty());
        }
    }

    @Test void anthropicKeepsIterationAndTtlSemanticsAcrossSnapshots() {
        UsageAccumulator state = new UsageAccumulator(new UsageContext(Protocol.ANTHROPIC_MESSAGES));
        accept(state, """
            {"type":"message_start","message":{"usage":{"input_tokens":99,"output_tokens":0,
              "iterations":[{"input_tokens":3,"output_tokens":0},{"input_tokens":5,"output_tokens":0}],
              "cache_read_input_tokens":10,"cache_creation":{"ephemeral_5m_input_tokens":20}}}}
            """);
        assertEquals(8L, state.latestUsage().orElseThrow().token(INPUT_TOKENS));
        accept(state, """
            {"type":"message_delta","usage":{"output_tokens":99,
              "iterations":[{"input_tokens":4,"output_tokens":2},{"input_tokens":6,"output_tokens":4}],
              "cache_creation":{"ephemeral_1h_input_tokens":30}}}
            """);
        Usage usage = state.confirmedUsage().orElseThrow();
        assertEquals(10L, usage.token(INPUT_TOKENS));
        assertEquals(6L, usage.token(OUTPUT_TOKENS));
        assertEquals(50L, usage.token(CACHE_WRITE_TOKENS));
        assertEquals(20L, usage.token(CACHE_WRITE_5M_TOKENS));
        assertEquals(30L, usage.token(CACHE_WRITE_1H_TOKENS));
        assertEquals(70L, usage.token(TIER_BASIS_TOKENS));
        assertEquals(76L, usage.token(TOTAL_TOKENS));
        accept(state, "{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":7,\"iterations\":[]}}");
        usage = state.confirmedUsage().orElseThrow();
        assertEquals(99L, usage.token(INPUT_TOKENS));
        assertEquals(7L, usage.token(OUTPUT_TOKENS));
        assertEquals(166L, usage.token(TOTAL_TOKENS));
    }
    @Test void geminiRequiresTerminalBeforeConfirmingCumulativeUsage() {
        UsageAccumulator state = new UsageAccumulator(new UsageContext(Protocol.GEMINI));
        accept(state, "{\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":2}}");
        assertTrue(state.confirmedUsage().isEmpty());
        accept(state, "{\"candidates\":[{\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":6}}");
        assertEquals(6L, state.confirmedUsage().orElseThrow().token(OUTPUT_TOKENS));
        assertTrue(state.terminal());
    }
    @Test void responsesIncompleteWithoutUsageDoesNotConfirmAnEarlierSnapshot() {
        UsageAccumulator state = new UsageAccumulator(new UsageContext(Protocol.OPENAI_RESPONSES));
        accept(state, "{\"type\":\"response.created\",\"response\":{\"usage\":{\"input_tokens\":10}}}");
        accept(state, "{\"type\":\"response.incomplete\",\"response\":{}}");
        assertTrue(state.confirmedUsage().isEmpty());
        assertTrue(state.latestUsage().isPresent());
    }
    @Test void geminiTerminalWithoutUsageCannotConfirmEarlierPartialCount() {
        UsageAccumulator state = new UsageAccumulator(new UsageContext(Protocol.GEMINI));
        accept(state, "{\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":2}}");
        accept(state, "{\"candidates\":[{\"finishReason\":\"STOP\"}]}");
        assertTrue(state.terminal());
        assertTrue(state.latestUsage().isPresent());
        assertTrue(state.confirmedUsage().isEmpty());
    }
    @Test void unknownProtocolDoesNotAssumeEveryDecodedChunkIsFinal() {
        Protocol custom = Protocol.of("example-stream");
        UsageParser parser = new UsageParser().withDecoder(custom, (root, context) -> java.util.Optional.of(Usage.tokens(1, 2)));
        UsageAccumulator state = new UsageAccumulator(new UsageContext(custom), parser);
        accept(state, "{}");
        assertTrue(state.latestUsage().isPresent());
        assertTrue(state.confirmedUsage().isEmpty());
    }

    @Test void customStreamDecoderControlsAuthorityAndHasIndependentCallState() {
        Protocol custom = Protocol.of("example-metered-stream");
        var created = new java.util.concurrent.atomic.AtomicInteger();
        UsageParser parser = new UsageParser().withStreamDecoder(custom, () -> {
            created.incrementAndGet();
            var count = new java.util.concurrent.atomic.AtomicInteger();
            return (event, context) -> {
                int current = count.incrementAndGet();
                boolean done = "finished".equals(event.event());
                return java.util.Optional.of(new StreamUsageUpdate(Usage.tokens(1, current), done, done));
            };
        });
        UsageAccumulator first = new UsageAccumulator(new UsageContext(custom), parser);
        UsageAccumulator second = new UsageAccumulator(new UsageContext(custom), parser);
        accept(first, "{}");
        assertTrue(first.confirmedUsage().isEmpty());
        first.accept(SseEvent.builder("{}").event("finished").build());
        second.accept(SseEvent.builder("{}").event("finished").build());
        assertTrue(first.terminal());
        assertEquals(2L, first.confirmedUsage().orElseThrow().token(OUTPUT_TOKENS));
        assertEquals(1L, second.confirmedUsage().orElseThrow().token(OUTPUT_TOKENS));
        assertEquals(2, created.get());
    }

}
