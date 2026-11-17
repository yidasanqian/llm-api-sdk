package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.error.ErrorEnvelopeFamily;
import io.github.yidasanqian.llm.error.LlmApiError;
import io.github.yidasanqian.llm.error.LlmErrorCategory;
import io.github.yidasanqian.llm.error.LlmErrorCodecs;
import io.github.yidasanqian.llm.error.ProtocolConversionException;
import io.github.yidasanqian.llm.bridge.anthropic.AnthropicSseEvent;
import lombok.extern.slf4j.Slf4j;
import io.github.yidasanqian.llm.protocol.SseEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * 桥接流三段式处理：错误拦截、正常事件映射、成功/错误收尾。
 */
@Slf4j
public final class BridgeStreamPipeline {

    private static final int LOG_BODY_LIMIT = 4096;

    private BridgeStreamPipeline() {
    }

    public static Flux<SseEvent<String>> map(
            Flux<SseEvent<String>> source,
            ErrorEnvelopeFamily sourceFamily,
            ErrorEnvelopeFamily targetFamily,
            String requestId,
            BridgeStreamMapper mapper) {
        return Flux.defer(() -> {
            BridgeStreamSession session = mapper.newSession();
            AtomicReference<LlmApiError> errorRef = new AtomicReference<>();
            AtomicBoolean outputEmitted = new AtomicBoolean();
            Flux<StreamItem> intercepted = intercept(source, sourceFamily, requestId);
            Flux<SseEvent<String>> mapped = intercepted.concatMap(item -> {
                if (item.error() != null) {
                    errorRef.set(item.error());
                    return Flux.empty();
                }
                List<SseEvent<String>> mappedEvents = session.map(item.event());
                if (!mappedEvents.isEmpty()) {
                    outputEmitted.set(true);
                }
                return Flux.fromIterable(mappedEvents);
            }).onErrorResume(error -> {
                if (!outputEmitted.get()) {
                    return Flux.error(error);
                }
                if (errorRef.get() == null) {
                    log.warn("上游流式响应异常终止，转换为协议错误: requestId={}, sourceFamily={}, cause={}",
                            requestId, sourceFamily, error.toString());
                    errorRef.set(LlmApiError.fallback(
                            sourceFamily, null, "Upstream stream terminated unexpectedly"));
                }
                return Flux.empty();
            });
            return mapped.concatWith(Flux.defer(() -> {
                LlmApiError error = errorRef.get();
                if (error != null) return Flux.fromIterable(errorEvents(error, targetFamily, requestId));
                try {
                    return Flux.fromIterable(session.finishSuccess());
                } catch (ProtocolConversionException incomplete) {
                    if (!outputEmitted.get()) return Flux.error(incomplete);
                    return Flux.fromIterable(errorEvents(LlmApiError.fallback(sourceFamily, null,
                            "Upstream stream ended without a terminal event"), targetFamily, requestId));
                }
            }));
        });
    }

    public static Predicate<SseEvent<String>> readiness(
            ErrorEnvelopeFamily sourceFamily,
            BridgeStreamMapper mapper,
            String requestId) {
        Predicate<SseEvent<String>> normalReadiness = mapper.createStreamReadiness();
        return event -> {
            Optional<LlmApiError> error = detectError(event, sourceFamily, requestId);
            if (error.isPresent()) {
                throw new BridgeStreamException(error.get());
            }
            return normalReadiness.test(event);
        };
    }

    private static Flux<StreamItem> intercept(
            Flux<SseEvent<String>> source,
            ErrorEnvelopeFamily sourceFamily,
            String requestId) {
        return source.concatMap(event -> {
            Optional<LlmApiError> error = detectError(event, sourceFamily, requestId);
            return Flux.just(error.map(StreamItem::error).orElseGet(() -> StreamItem.event(event)));
        }).takeUntil(item -> item.error() != null);
    }

    private static Optional<LlmApiError> detectError(
            SseEvent<String> event,
            ErrorEnvelopeFamily sourceFamily,
            String requestId) {
        if (event == null) {
            return Optional.empty();
        }
        String eventName = event.event();
        String data = event.data();
        boolean errorEvent = "error".equals(eventName);
        if (sourceFamily == ErrorEnvelopeFamily.OPENAI && "response.failed".equals(resolveOpenAiType(eventName, data))) {
            return Optional.of(parseOpenAiResponseFailed(data, requestId));
        }
        if (!errorEvent) {
            return looksLikePotentialErrorPayload(data)
                    ? LlmErrorCodecs.parse(sourceFamily, 0, data)
                    : Optional.empty();
        }
        if (data == null || data.isBlank()) {
            log.warn("上游流式错误事件缺少响应体，按统一兜底信封转换: requestId={}, sourceFamily={}, event={}",
                    requestId, sourceFamily, eventName);
            return Optional.of(LlmApiError.fallback(
                    sourceFamily, null, "Upstream stream returned an error event without details"));
        }

        Optional<LlmApiError> parsed = LlmErrorCodecs.parse(sourceFamily, 0, data);
        if (parsed.isPresent()) {
            return parsed;
        }
        log.warn("上游流式错误无法按协议解析，按统一兜底信封转换: requestId={}, sourceFamily={}, event={}, body={}",
                requestId, sourceFamily, eventName, abbreviate(data));
        return Optional.of(BridgeErrorTranslator.fallback(sourceFamily, null));
    }

    private static boolean looksLikePotentialErrorPayload(String data) {
        if (data == null || data.isBlank()) {
            return false;
        }
        return data.contains("\"error\"") || data.contains("\"type\":\"error\"");
    }

    private static String abbreviate(String value) {
        if (value == null || value.length() <= LOG_BODY_LIMIT) {
            return value;
        }
        return value.substring(0, LOG_BODY_LIMIT) + "...(truncated)";
    }

    private static LlmApiError parseOpenAiResponseFailed(String data, String requestId) {
        return LlmErrorCodecs.parse(ErrorEnvelopeFamily.OPENAI, 500, data)
                .orElseGet(() -> new LlmApiError(
                        ErrorEnvelopeFamily.OPENAI,
                        LlmErrorCategory.SERVER,
                        "Upstream response failed",
                        "server_error",
                        null,
                        null,
                        null,
                        null,
                        requestId
                ));
    }

    private static String resolveOpenAiType(String eventName, String data) {
        if (eventName != null && !eventName.isBlank()) {
            return eventName;
        }
        if (data == null || data.isBlank()) {
            return "";
        }
        int typeStart = data.indexOf("\"type\"");
        if (typeStart < 0) {
            return "";
        }
        int valueStart = data.indexOf('"', data.indexOf(':', typeStart) + 1);
        if (valueStart < 0) {
            return "";
        }
        int valueEnd = data.indexOf('"', valueStart + 1);
        return valueEnd > valueStart ? data.substring(valueStart + 1, valueEnd) : "";
    }

    private static List<SseEvent<String>> errorEvents(
            LlmApiError error,
            ErrorEnvelopeFamily targetFamily,
            String requestId) {
        String body = LlmErrorCodecs.render(targetFamily, error, 0, requestId);
        return switch (targetFamily) {
            case OPENAI -> List.of(
                    SseEvent.<String>builder().data(body).build(),
                    SseEvent.<String>builder().data("[DONE]").build()
            );
            case ANTHROPIC -> List.of(new AnthropicSseEvent("error", body).toSseEvent());
            case GEMINI -> List.of(SseEvent.<String>builder().data(body).build());
        };
    }

    private record StreamItem(SseEvent<String> event, LlmApiError error) {

        private static StreamItem event(SseEvent<String> event) {
            return new StreamItem(event, null);
        }

        private static StreamItem error(LlmApiError error) {
            return new StreamItem(null, error);
        }
    }
}
