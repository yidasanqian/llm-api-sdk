package io.github.yidasanqian.llm.bridge;

import tools.jackson.databind.ObjectMapper;
import io.github.yidasanqian.llm.bridge.anthropic.OpenAIResponsesToAnthropicResponseMapper;
import org.junit.jupiter.api.Test;
import io.github.yidasanqian.llm.protocol.SseEvent;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAIResponsesToAnthropicResponseMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAIResponsesToAnthropicResponseMapper mapper =
            new OpenAIResponsesToAnthropicResponseMapper(objectMapper);

    @Test
    void shouldMapNonStreamResponsesPayloadWithFunctionCall() throws Exception {
        String openAIResponse = """
                {
                  "id": "resp_123",
                  "model": "gpt-5",
                  "output": [
                    {
                      "id": "msg_123",
                      "type": "message",
                      "role": "assistant",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "我来查一下"
                        }
                      ]
                    },
                    {
                      "type": "function_call",
                      "id": "fc_1",
                      "call_id": "call_1",
                      "name": "get_weather",
                      "arguments": "{\\"city\\":\\"Hangzhou\\"}",
                      "status": "completed"
                    }
                  ],
                  "usage": {
                    "input_tokens": 11,
                    "output_tokens": 7,
                    "input_tokens_details": {
                      "cache_write_tokens": 4
                    }
                  }
                }
                """;

        Map<?, ?> result = objectMapper.readValue(mapper.mapNonStream(openAIResponse), Map.class);
        assertEquals("message", result.get("type"));
        assertEquals("assistant", result.get("role"));
        assertEquals("tool_use", result.get("stop_reason"));
        assertEquals("我来查一下", ((Map<?, ?>) ((List<?>) result.get("content")).get(0)).get("text"));
        assertEquals("tool_use", ((Map<?, ?>) ((List<?>) result.get("content")).get(1)).get("type"));
        assertEquals(11, ((Map<?, ?>) result.get("usage")).get("input_tokens"));
        assertEquals(4, ((Map<?, ?>) result.get("usage")).get("cache_creation_input_tokens"));
    }

    @Test
    void shouldMapResponsesStreamToAnthropicEvents() {
        FluxWrapper wrapper = new FluxWrapper(List.of(
                sse("""
                        {"type":"response.created","response":{"id":"resp_1","model":"gpt-5","output":[]}}
                        """),
                sse("""
                        {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_1","type":"message","role":"assistant","content":[]}}
                        """),
                sse("""
                        {"type":"response.content_part.added","item_id":"msg_1","output_index":0,"content_index":0,"part":{"type":"output_text","text":"","annotations":[]}}
                        """),
                sse("""
                        {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"你好"}
                        """),
                sse("""
                        {"type":"response.output_item.added","output_index":1,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"get_weather","arguments":"","status":"in_progress"}}
                        """),
                sse("""
                        {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":1,"delta":"{\\"city\\":\\"Hang"}
                        """),
                sse("""
                        {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":1,"delta":"zhou\\"}"}
                        """),
                sse("""
                        {"type":"response.completed","response":{"id":"resp_1","model":"gpt-5","output":[{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"你好","annotations":[]}]},{"id":"fc_1","type":"function_call","call_id":"call_1","name":"get_weather","arguments":"{\\"city\\":\\"Hangzhou\\"}","status":"completed"}],"usage":{"input_tokens":12,"output_tokens":8,"input_tokens_details":{"cache_write_tokens":6}}}}
                        """)
        ));

        List<SseEvent<String>> events = BridgeStreamTestSupport.mapStream(
                mapper, wrapper.flux()).collectList().block();

        assertEquals("message_start", events.get(0).event());
        assertEquals("content_block_start", events.get(1).event());
        assertEquals("content_block_delta", events.get(2).event());
        assertEquals("content_block_start", events.get(3).event());
        assertEquals("content_block_delta", events.get(4).event());
        assertEquals("content_block_delta", events.get(5).event());
        assertEquals("content_block_stop", events.get(6).event());
        assertEquals("content_block_stop", events.get(7).event());
        assertEquals("message_delta", events.get(8).event());
        assertEquals("message_stop", events.get(9).event());
        assertTrue(events.get(8).data().contains("\"stop_reason\":\"tool_use\""));
        assertTrue(events.get(8).data().contains("\"input_tokens\":12"));
        assertTrue(events.get(8).data().contains("\"cache_read_input_tokens\":0"));
        assertTrue(events.get(8).data().contains("\"cache_creation_input_tokens\":6"));
    }

    @Test
    void shouldMapNonStreamResponsesRefusal() throws Exception {
        String openAIResponse = """
                {
                  "id": "resp_456",
                  "model": "gpt-5",
                  "output": [
                    {
                      "id": "msg_456",
                      "type": "message",
                      "role": "assistant",
                      "content": [
                        {
                          "type": "refusal",
                          "refusal": "对不起，这个请求无法完成。"
                        }
                      ]
                    }
                  ],
                  "usage": {
                    "input_tokens": 5,
                    "output_tokens": 2
                  }
                }
                """;

        Map<?, ?> result = objectMapper.readValue(mapper.mapNonStream(openAIResponse), Map.class);
        assertEquals("refusal", result.get("stop_reason"));
        assertEquals("对不起，这个请求无法完成。", ((Map<?, ?>) ((List<?>) result.get("content")).get(0)).get("text"));
        assertEquals(5, ((Map<?, ?>) result.get("usage")).get("input_tokens"));
        assertEquals(0, ((Map<?, ?>) result.get("usage")).get("cache_creation_input_tokens"));
    }

    @Test
    void shouldMapNonStreamResponsesMaxOutputTokensStopReason() throws Exception {
        String openAIResponse = """
                {
                  "id": "resp_457",
                  "model": "gpt-5",
                  "incomplete_details": {
                    "reason": "max_output_tokens"
                  },
                  "output": [
                    {
                      "id": "msg_457",
                      "type": "message",
                      "role": "assistant",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "输出被截断"
                        }
                      ]
                    }
                  ],
                  "usage": {
                    "input_tokens": 7,
                    "output_tokens": 3
                  }
                }
                """;

        Map<?, ?> result = objectMapper.readValue(mapper.mapNonStream(openAIResponse), Map.class);
        assertEquals("max_tokens", result.get("stop_reason"));
        assertEquals("输出被截断", ((Map<?, ?>) ((List<?>) result.get("content")).get(0)).get("text"));
    }

    @Test
    void shouldMapFunctionCallArgumentsDoneWithoutDelta() {
        FluxWrapper wrapper = new FluxWrapper(List.of(
                sse("""
                        {"type":"response.created","response":{"id":"resp_2","model":"gpt-5","output":[]}}
                        """),
                sse("""
                        {"type":"response.output_item.added","output_index":0,"item":{"id":"fc_2","type":"function_call","call_id":"call_2","name":"get_weather","arguments":"","status":"in_progress"}}
                        """),
                sse("""
                        {"type":"response.function_call_arguments.done","item_id":"fc_2","output_index":0,"arguments":"{\\"city\\":\\"Shanghai\\"}"}
                        """),
                sse("""
                        {"type":"response.completed","response":{"id":"resp_2","model":"gpt-5","output":[{"id":"fc_2","type":"function_call","call_id":"call_2","name":"get_weather","arguments":"{\\"city\\":\\"Shanghai\\"}","status":"completed"}],"usage":{"input_tokens":9,"output_tokens":4}}}
                        """)
        ));

        List<SseEvent<String>> events = BridgeStreamTestSupport.mapStream(
                mapper, wrapper.flux()).collectList().block();

        assertEquals("message_start", events.get(0).event());
        assertEquals("content_block_start", events.get(1).event());
        assertEquals("content_block_delta", events.get(2).event());
        assertEquals("content_block_stop", events.get(3).event());
        assertEquals("message_delta", events.get(4).event());
        assertEquals("message_stop", events.get(5).event());
        assertTrue(events.get(2).data().contains("\\\"city\\\":\\\"Shanghai\\\""));
        assertTrue(events.get(4).data().contains("\"stop_reason\":\"tool_use\""));
    }

    @Test
    void shouldMapOutputTextDoneWithoutDeltaAndMaxTokensStopReason() {
        FluxWrapper wrapper = new FluxWrapper(List.of(
                sse("""
                        {"type":"response.created","response":{"id":"resp_3","model":"gpt-5","output":[]}}
                        """),
                sse("""
                        {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_3","type":"message","role":"assistant","content":[]}}
                        """),
                sse("""
                        {"type":"response.content_part.added","item_id":"msg_3","output_index":0,"content_index":0,"part":{"type":"output_text","text":"","annotations":[]}}
                        """),
                sse("""
                        {"type":"response.output_text.done","item_id":"msg_3","output_index":0,"content_index":0,"text":"这是补偿文本"}
                        """),
                sse("""
                        {"type":"response.completed","response":{"id":"resp_3","model":"gpt-5","incomplete_details":{"reason":"max_output_tokens"},"output":[{"id":"msg_3","type":"message","role":"assistant","content":[{"type":"output_text","text":"这是补偿文本","annotations":[]}]}],"usage":{"input_tokens":6,"output_tokens":3}}}
                        """)
        ));

        List<SseEvent<String>> events = BridgeStreamTestSupport.mapStream(
                mapper, wrapper.flux()).collectList().block();

        assertEquals("message_start", events.get(0).event());
        assertEquals("content_block_start", events.get(1).event());
        assertEquals("content_block_delta", events.get(2).event());
        assertEquals("content_block_stop", events.get(3).event());
        assertEquals("message_delta", events.get(4).event());
        assertEquals("message_stop", events.get(5).event());
        assertTrue(events.get(2).data().contains("这是补偿文本"));
        assertTrue(events.get(4).data().contains("\"stop_reason\":\"max_tokens\""));
        assertTrue(events.get(4).data().contains("\"output_tokens\":3"));
        assertTrue(events.get(4).data().contains("\"cache_creation_input_tokens\":0"));
    }

    private SseEvent<String> sse(String data) {
        return SseEvent.<String>builder().data(data.trim()).build();
    }

    private record FluxWrapper(List<SseEvent<String>> events) {
        reactor.core.publisher.Flux<SseEvent<String>> flux() {
            return reactor.core.publisher.Flux.fromIterable(events);
        }
    }
}
