package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.bridge.anthropic.AnthropicMessagesToOpenAIChatRequestMapper;
import io.github.yidasanqian.llm.bridge.anthropic.AnthropicMessagesToOpenAIResponsesRequestMapper;
import io.github.yidasanqian.llm.bridge.anthropic.OpenAIChatCompletionsToAnthropicResponseMapper;
import io.github.yidasanqian.llm.bridge.anthropic.OpenAIResponsesToAnthropicResponseMapper;
import io.github.yidasanqian.llm.bridge.openai.AnthropicMessagesToOpenAIChatResponseMapper;
import io.github.yidasanqian.llm.bridge.openai.OpenAIChatToAnthropicMessagesRequestMapper;
import io.github.yidasanqian.llm.bridge.openai.OpenAIChatToResponsesRequestMapper;
import io.github.yidasanqian.llm.bridge.openai.OpenAIResponsesToChatCompletionsResponseMapper;
import io.github.yidasanqian.llm.error.ErrorEnvelopeFamily;
import io.github.yidasanqian.llm.protocol.Protocol;
import io.github.yidasanqian.llm.protocol.SseEvent;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

final class BuiltinProtocolAdapters {
    private BuiltinProtocolAdapters() { }

    static void register(ProtocolAdapterRegistry.Builder builder, ObjectMapper mapper) {
        var responsesToChat = new OpenAIResponsesToChatCompletionsResponseMapper(mapper);
        builder.register(Protocol.OPENAI_CHAT, Protocol.OPENAI_RESPONSES,
                new Adapter(new OpenAIChatToResponsesRequestMapper()::map, responsesToChat::mapNonStream,
                        responsesToChat, ErrorEnvelopeFamily.OPENAI, ErrorEnvelopeFamily.OPENAI));

        var messagesToChat = new AnthropicMessagesToOpenAIChatResponseMapper(mapper);
        builder.register(Protocol.OPENAI_CHAT, Protocol.ANTHROPIC_MESSAGES,
                new Adapter(new OpenAIChatToAnthropicMessagesRequestMapper()::map, messagesToChat::mapNonStream,
                        messagesToChat, ErrorEnvelopeFamily.ANTHROPIC, ErrorEnvelopeFamily.OPENAI));

        var chatToMessages = new OpenAIChatCompletionsToAnthropicResponseMapper(mapper);
        builder.register(Protocol.ANTHROPIC_MESSAGES, Protocol.OPENAI_CHAT,
                new Adapter(new AnthropicMessagesToOpenAIChatRequestMapper()::map, chatToMessages::mapNonStream,
                        chatToMessages, ErrorEnvelopeFamily.OPENAI, ErrorEnvelopeFamily.ANTHROPIC));

        var responsesToMessages = new OpenAIResponsesToAnthropicResponseMapper(mapper);
        builder.register(Protocol.ANTHROPIC_MESSAGES, Protocol.OPENAI_RESPONSES,
                new Adapter(new AnthropicMessagesToOpenAIResponsesRequestMapper(
                        new AnthropicMessagesToOpenAIChatRequestMapper())::map, responsesToMessages::mapNonStream,
                        responsesToMessages, ErrorEnvelopeFamily.OPENAI, ErrorEnvelopeFamily.ANTHROPIC));
    }

    private record Adapter(
            BiFunction<Map<String, Object>, Boolean, Map<String, Object>> requestMapper,
            Function<String, String> responseMapper,
            BridgeStreamMapper streamMapper,
            ErrorEnvelopeFamily upstreamErrors,
            ErrorEnvelopeFamily clientErrors) implements ProtocolAdapter {

        @Override
        public Map<String, Object> mapRequest(Map<String, Object> request, boolean stream) {
            return requestMapper.apply(request, stream);
        }

        @Override
        public String mapResponse(String body) { return responseMapper.apply(body); }

        @Override
        public Flux<SseEvent<String>> mapStream(Flux<SseEvent<String>> source, String requestId) {
            return BridgeStreamPipeline.map(source, upstreamErrors, clientErrors, requestId, streamMapper);
        }

        @Override
        public Predicate<SseEvent<String>> streamReadiness(String requestId) {
            return BridgeStreamPipeline.readiness(upstreamErrors, streamMapper, requestId);
        }

        @Override
        public String mapError(int statusCode, String body, String requestId) {
            return BridgeErrorTranslator.translate(upstreamErrors, clientErrors,
                    new BridgeErrorTranslator.ErrorResponse(statusCode, body, requestId));
        }
    }
}
