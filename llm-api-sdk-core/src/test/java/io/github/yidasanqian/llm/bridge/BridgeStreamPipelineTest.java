package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.error.ErrorEnvelopeFamily;
import io.github.yidasanqian.llm.json.JsonUtil;
import org.junit.jupiter.api.Test;
import io.github.yidasanqian.llm.protocol.SseEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeStreamPipelineTest {

    @Test
    void shouldReplaceOpenAiResponseFailedWithClientErrorAndDone() {
        List<SseEvent<String>> events = BridgeStreamPipeline.map(
                        Flux.just(named("response.failed",
                                "{\"type\":\"response.failed\",\"response\":{\"status\":\"failed\","
                                        + "\"error\":{\"message\":\"upstream failed\",\"code\":\"server_error\"}}}")),
                        ErrorEnvelopeFamily.OPENAI, ErrorEnvelopeFamily.OPENAI, "req-1", passthroughMapper())
                .collectList().block();

        assertTrue(events != null && events.size() == 2);
        JsonNode error = JsonUtil.toObject(events.get(0).data(), JsonNode.class).path("error");
        assertEquals("upstream failed", error.path("message").asText());
        assertEquals("[DONE]", events.get(events.size() - 1).data());
    }

    @Test
    void shouldTranslateAnthropicErrorEventWithoutSuccessTerminal() {
        List<SseEvent<String>> events = BridgeStreamPipeline.map(
                        Flux.just(named("error",
                                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\","
                                        + "\"message\":\"busy\"}}")),
                        ErrorEnvelopeFamily.ANTHROPIC, ErrorEnvelopeFamily.ANTHROPIC, "req-2",
                        passthroughMapper())
                .collectList().block();

        assertTrue(events != null && events.size() == 1);
        assertEquals("error", events.get(0).event());
        JsonNode error = JsonUtil.toObject(events.get(0).data(), JsonNode.class).path("error");
        assertEquals("busy", error.path("message").asText());
        assertFalse(events.stream().anyMatch(event -> "message_stop".equals(event.event())));
    }

    @Test
    void shouldTranslateEmptyAnthropicErrorEventWithoutSuccessTerminal() {
        List<SseEvent<String>> events = BridgeStreamPipeline.map(
                        Flux.just(named("error", null)),
                        ErrorEnvelopeFamily.ANTHROPIC, ErrorEnvelopeFamily.ANTHROPIC, "req-empty",
                        passthroughMapper())
                .collectList().block();

        assertTrue(events != null && events.size() == 1);
        assertEquals("error", events.get(0).event());
        assertTrue(events.get(0).data().contains("without details"));
    }

    @Test
    void shouldConvertReactiveErrorAfterClientOutput() {
        List<SseEvent<String>> events = BridgeStreamPipeline.map(
                        Flux.concat(
                                Flux.just(data("{\"chunk\":\"hello\"}")),
                                Flux.error(new IllegalStateException("connection reset"))),
                        ErrorEnvelopeFamily.OPENAI, ErrorEnvelopeFamily.OPENAI, "req-3",
                        passthroughMapper())
                .collectList().block();

        assertTrue(events != null && events.size() == 3);
        assertEquals("hello", JsonUtil.toObject(events.get(0).data(), JsonNode.class).path("chunk").asText());
        assertTrue(events.get(1).data().contains("\"error\""));
        assertEquals("[DONE]", events.get(events.size() - 1).data());
        assertFalse(events.stream().anyMatch(event -> "success".equals(event.data())));
    }

    @Test
    void shouldPropagateReactiveErrorBeforeClientOutputForFailover() {
        StepVerifier.create(BridgeStreamPipeline.map(
                        Flux.concat(
                                Flux.just(data("ignored")),
                                Flux.error(new IllegalStateException("connect reset"))),
                        ErrorEnvelopeFamily.OPENAI, ErrorEnvelopeFamily.OPENAI, "req-4",
                        ignoredMapper()))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void shouldRunSuccessTerminalWhenUpstreamCompletesWithoutError() {
        List<SseEvent<String>> events = BridgeStreamPipeline.map(
                        Flux.just(data("normal")),
                        ErrorEnvelopeFamily.OPENAI, ErrorEnvelopeFamily.OPENAI, "req-5",
                        passthroughMapper())
                .collectList().block();

        assertTrue(events != null && events.size() == 2);
        assertEquals("normal", events.get(0).data());
        assertEquals("success", events.get(events.size() - 1).data());
    }

    @Test
    void shouldFailReadinessWhenErrorEventArrivesBeforeClientOutput() {
        BridgeStreamException failure = assertThrows(BridgeStreamException.class, () ->
                BridgeStreamPipeline.readiness(
                        ErrorEnvelopeFamily.ANTHROPIC, passthroughMapper(), "req-6")
                        .test(named("error",
                                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\","
                                        + "\"message\":\"busy\"}}")));

        assertEquals("busy", failure.error().message());
    }

    private static BridgeStreamMapper passthroughMapper() {
        return new BridgeStreamMapper() {
            @Override
            public BridgeStreamSession newSession() {
                return new BridgeStreamSession() {
                    @Override
                    public List<SseEvent<String>> map(SseEvent<String> event) {
                        return List.of(event);
                    }

                    @Override
                    public List<SseEvent<String>> finishSuccess() {
                        return List.of(data("success"));
                    }
                };
            }

            @Override
            public Predicate<SseEvent<String>> createStreamReadiness() {
                return ignored -> true;
            }
        };
    }

    private static BridgeStreamMapper ignoredMapper() {
        return new BridgeStreamMapper() {
            @Override
            public BridgeStreamSession newSession() {
                return new BridgeStreamSession() {
                    @Override
                    public List<SseEvent<String>> map(SseEvent<String> event) {
                        return List.of();
                    }

                    @Override
                    public List<SseEvent<String>> finishSuccess() {
                        return List.of(data("success"));
                    }
                };
            }

            @Override
            public Predicate<SseEvent<String>> createStreamReadiness() {
                return ignored -> false;
            }
        };
    }

    private static SseEvent<String> named(String event, String data) {
        return SseEvent.<String>builder().event(event).data(data).build();
    }

    private static SseEvent<String> data(String data) {
        return SseEvent.<String>builder().data(data).build();
    }
}
