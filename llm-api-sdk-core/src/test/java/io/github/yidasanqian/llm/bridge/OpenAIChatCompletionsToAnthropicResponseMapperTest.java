package io.github.yidasanqian.llm.bridge;

import tools.jackson.databind.ObjectMapper;
import io.github.yidasanqian.llm.bridge.anthropic.OpenAIChatCompletionsToAnthropicResponseMapper;
import io.github.yidasanqian.llm.error.ProtocolConversionException;
import org.junit.jupiter.api.Test;
import io.github.yidasanqian.llm.protocol.SseEvent;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAIChatCompletionsToAnthropicResponseMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAIChatCompletionsToAnthropicResponseMapper mapper =
            new OpenAIChatCompletionsToAnthropicResponseMapper(objectMapper);

    @Test
    void shouldMapNonStreamResponseWithToolCalls() throws Exception {
        String openAIResponse = """
                {
                  "id": "chatcmpl_123",
                  "model": "gpt-5",
                  "choices": [
                    {
                      "finish_reason": "tool_calls",
                      "message": {
                        "role": "assistant",
                        "content": "我来查一下",
                        "tool_calls": [
                          {
                            "id": "call_1",
                            "type": "function",
                            "function": {
                              "name": "get_weather",
                              "arguments": "{\\"city\\":\\"Hangzhou\\"}"
                            }
                          }
                        ]
                      }
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 11,
                    "completion_tokens": 7,
                    "prompt_tokens_details": {
                      "cached_tokens": 3,
                      "cache_write_tokens": 8
                    }
                  }
                }
                """;

        Map<?, ?> result = objectMapper.readValue(mapper.mapNonStream(openAIResponse), Map.class);
        assertEquals("message", result.get("type"));
        assertEquals("assistant", result.get("role"));
        assertEquals("tool_use", ((Map<?, ?>) ((List<?>) result.get("content")).get(1)).get("type"));
        assertEquals("tool_use", result.get("stop_reason"));
        assertEquals(3, ((Map<?, ?>) result.get("usage")).get("cache_read_input_tokens"));
        assertEquals(8, ((Map<?, ?>) result.get("usage")).get("cache_creation_input_tokens"));
    }

    @Test
    void shouldMapStreamChunksToAnthropicEvents() {
        FluxWrapper wrapper = new FluxWrapper(List.of(
                sse("{\"id\":\"chatcmpl_1\",\"model\":\"gpt-5\",\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}"),
                sse("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Hang\"}}]},\"finish_reason\":null}]}"),
                sse("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"zhou\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8,\"prompt_tokens_details\":{\"cached_tokens\":4,\"cache_write_tokens\":6}}}"),
                sse("[DONE]")
        ));

        List<SseEvent<String>> events = BridgeStreamTestSupport.mapStream(
                mapper, wrapper.flux()).collectList().block();

        assertEquals("message_start", events.get(0).event());
        assertEquals("content_block_start", events.get(1).event());
        assertEquals("content_block_delta", events.get(2).event());
        assertEquals("content_block_stop", events.get(3).event());
        assertEquals("content_block_start", events.get(4).event());
        assertEquals("content_block_delta", events.get(5).event());
        assertEquals("content_block_delta", events.get(6).event());
        assertEquals("content_block_stop", events.get(7).event());
        assertEquals("message_delta", events.get(8).event());
        assertEquals("message_stop", events.get(9).event());
        assertTrue(events.get(8).data().contains("\"stop_reason\":\"tool_use\""));
        assertTrue(events.get(8).data().contains("\"input_tokens\":12"));
        assertTrue(events.get(8).data().contains("\"cache_read_input_tokens\":4"));
        assertTrue(events.get(8).data().contains("\"cache_creation_input_tokens\":6"));
    }

    @Test
    void shouldPassThroughPingAndRejectStreamWithoutDoneChunk() {
        FluxWrapper wrapper = new FluxWrapper(List.of(
                SseEvent.<String>builder().event("ping").build(),
                sse("{\"id\":\"chatcmpl_2\",\"model\":\"gpt-5\",\"choices\":[{\"delta\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}")
        ));

        BridgeStreamSession session = mapper.newSession();
        List<SseEvent<String>> events = wrapper.flux().concatMapIterable(session::map).collectList().block();

        assertEquals("ping", events.get(0).event());
        assertEquals("{\"type\":\"ping\"}", events.get(0).data());
        assertEquals("message_start", events.get(1).event());
        assertEquals("content_block_start", events.get(2).event());
        assertEquals("content_block_delta", events.get(3).event());
        assertEquals(4, events.size());
        assertThrows(ProtocolConversionException.class, session::finishSuccess);
    }

    @Test
    void shouldMapNonStreamRefusalMessage() throws Exception {
        String openAIResponse = """
                {
                  "id": "chatcmpl_456",
                  "model": "gpt-5",
                  "choices": [
                    {
                      "finish_reason": "content_filter",
                      "message": {
                        "role": "assistant",
                        "content": "",
                        "refusal": "对不起，这个请求无法完成。"
                      }
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 5,
                    "completion_tokens": 2
                  }
                }
                """;

        Map<?, ?> result = objectMapper.readValue(mapper.mapNonStream(openAIResponse), Map.class);
        assertEquals("refusal", result.get("stop_reason"));
        assertEquals("对不起，这个请求无法完成。", ((Map<?, ?>) ((List<?>) result.get("content")).get(0)).get("text"));
    }

    private SseEvent<String> sse(String data) {
        return SseEvent.<String>builder().data(data).build();
    }

    private record FluxWrapper(List<SseEvent<String>> events) {
        reactor.core.publisher.Flux<SseEvent<String>> flux() {
            return reactor.core.publisher.Flux.fromIterable(events);
        }
    }
}
