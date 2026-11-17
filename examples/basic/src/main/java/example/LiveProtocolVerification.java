package example;

import io.github.yidasanqian.llm.bridge.ProtocolBridge;
import io.github.yidasanqian.llm.client.CallCompletion;
import io.github.yidasanqian.llm.client.CallOptions;
import io.github.yidasanqian.llm.client.CallOutcome;
import io.github.yidasanqian.llm.client.LlmClient;
import io.github.yidasanqian.llm.client.LlmEndpoint;
import io.github.yidasanqian.llm.client.LlmRequest;
import io.github.yidasanqian.llm.client.LlmStreamEvent;
import io.github.yidasanqian.llm.protocol.Protocol;
import io.github.yidasanqian.llm.protocol.SseEvent;
import io.github.yidasanqian.llm.usage.Usage;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.github.yidasanqian.llm.usage.UsageDimensions.INPUT_TOKENS;
import static io.github.yidasanqian.llm.usage.UsageDimensions.OUTPUT_TOKENS;

/** Manual, billable verification. Never invoked by tests or CI. */
public final class LiveProtocolVerification {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(90);
    private static final String PROMPT = "只回复OK";

    private LiveProtocolVerification() { }

    public static void main(String[] args) {
        int failures = 0;
        try {
            var cases = cases(args);
            var endpoints = endpoints(cases);
            try (var client = LlmClient.builder().idleTimeout(CALL_TIMEOUT).build()) {
                for (var test : cases) {
                    try {
                        System.out.println(verify(client, test, endpoints.get(test.upstream()))
                                .block(CALL_TIMEOUT));
                    } catch (RuntimeException error) {
                        failures++;
                        System.out.println("case=" + test.id() + " result=FAIL reason=" + safeReason(error));
                    }
                }
            }
            System.out.println("cases=" + cases.size() + " passed=" + (cases.size() - failures)
                    + " failed=" + failures);
        } catch (RuntimeException error) {
            failures++;
            System.out.println("configuration=FAIL reason=" + safeReason(error));
        }
        if (failures > 0) System.exit(1);
    }

    private static String safeReason(RuntimeException error) {
        // Provider errors may contain credentials or echoed request data. Print no causes.
        return error instanceof VerificationFailure ? error.getMessage() : error.getClass().getSimpleName();
    }

    private static Mono<String> verify(LlmClient client, TestCase test, LlmEndpoint endpoint) {
        var bridge = new ProtocolBridge(test.client(), test.upstream());
        var upstream = new ProtocolProbe(test.upstream());
        var converted = new ProtocolProbe(test.client());
        var completion = new AtomicReference<CallCompletion>();
        var options = CallOptions.builder().completionListener(completion::set)
                .rawEventHandler(event -> {
                    upstream.event(event);
                    return Mono.empty();
                }).build();
        var request = new LlmRequest(endpoint, bridge.mapRequest(requestBody(test.client()), test.stream()), options);
        Mono<Void> consumed;
        if (test.stream()) {
            var raw = client.stream(request).<SseEvent<String>>handle((event, sink) -> {
                if (event instanceof LlmStreamEvent.Headers headers) {
                    requireSuccessfulHttp(headers.statusCode());
                } else if (event instanceof LlmStreamEvent.Failure failure) {
                    throw new VerificationFailure("http-" + failure.statusCode());
                } else if (event instanceof LlmStreamEvent.Data data) {
                    sink.next(data.event());
                }
            });
            consumed = bridge.mapStream(raw, "manual-verification").doOnNext(converted::event).then();
        } else {
            consumed = client.execute(request).doOnNext(response -> {
                requireSuccessfulHttp(response.statusCode());
                upstream.response(response.body());
                converted.response(bridge.mapResponse(response.body()));
            }).then();
        }
        return consumed.then(Mono.fromSupplier(() -> {
            upstream.verify();
            converted.verify();
            var finished = completion.get();
            require(finished != null && finished.outcome() == CallOutcome.COMPLETED, "transport-not-completed");
            Usage usage = finished.usage().orElseThrow(() -> new VerificationFailure("raw-upstream-usage-missing"));
            require(usage.token(INPUT_TOKENS) != null && usage.token(OUTPUT_TOKENS) != null,
                    "raw-upstream-token-meters-missing");
            return "case=" + test.id() + " status=" + finished.statusCode()
                    + " inputTokens=" + usage.token(INPUT_TOKENS) + " outputTokens=" + usage.token(OUTPUT_TOKENS)
                    + " text=true terminal=true rawUsage=true result=PASS";
        }));
    }

    private static Map<String, Object> requestBody(Protocol protocol) {
        if (protocol.equals(Protocol.GEMINI)) {
            return Map.of("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", PROMPT)))),
                    "generationConfig", Map.of("maxOutputTokens", 512));
        }
        if (protocol.equals(Protocol.OPENAI_RESPONSES)) {
            return Map.of("input", PROMPT, "max_output_tokens", 512);
        }
        String tokenLimit = protocol.equals(Protocol.OPENAI_CHAT) ? "max_completion_tokens" : "max_tokens";
        return Map.of("messages", List.of(Map.of("role", "user", "content", PROMPT)), tokenLimit, 512);
    }

    private static List<TestCase> cases(String[] args) {
        require(args.length <= 1, "use-all-native-only-or-bridge-only");
        String mode = args.length == 0 ? "all" : args[0];
        require(List.of("all", "native-only", "bridge-only").contains(mode), "use-all-native-only-or-bridge-only");
        var cases = new ArrayList<TestCase>();
        if (!mode.equals("bridge-only")) {
            for (var protocol : List.of(Protocol.OPENAI_CHAT, Protocol.OPENAI_RESPONSES,
                    Protocol.ANTHROPIC_MESSAGES, Protocol.GEMINI)) {
                addCases(cases, protocol, protocol);
            }
        }
        if (!mode.equals("native-only")) {
            addCases(cases, Protocol.OPENAI_CHAT, Protocol.OPENAI_RESPONSES);
            addCases(cases, Protocol.OPENAI_CHAT, Protocol.ANTHROPIC_MESSAGES);
            addCases(cases, Protocol.ANTHROPIC_MESSAGES, Protocol.OPENAI_RESPONSES);
            addCases(cases, Protocol.ANTHROPIC_MESSAGES, Protocol.OPENAI_CHAT);
        }
        return cases;
    }

    private static void addCases(List<TestCase> cases, Protocol client, Protocol upstream) {
        cases.add(new TestCase(client, upstream, false));
        cases.add(new TestCase(client, upstream, true));
    }

    private static Map<Protocol, LlmEndpoint> endpoints(List<TestCase> cases) {
        URI root = URI.create(requiredEnv("LLM_TEST_BASE_URL"));
        require(("http".equalsIgnoreCase(root.getScheme()) || "https".equalsIgnoreCase(root.getScheme()))
                && root.getHost() != null && root.getUserInfo() == null
                && root.getQuery() == null && root.getFragment() == null, "invalid-base-url");
        String base = root.toString().replaceAll("/+$", "");
        String key = requiredEnv("LLM_TEST_API_KEY");
        var endpoints = new LinkedHashMap<Protocol, LlmEndpoint>();
        for (var test : cases) {
            Protocol protocol = test.upstream();
            if (endpoints.containsKey(protocol)) continue;
            String variable;
            String path;
            if (protocol.equals(Protocol.OPENAI_CHAT)) {
                variable = "LLM_TEST_CHAT_MODEL";
                path = "/v1/chat/completions";
            } else if (protocol.equals(Protocol.OPENAI_RESPONSES)) {
                variable = "LLM_TEST_RESPONSES_MODEL";
                path = "/v1/responses";
            } else if (protocol.equals(Protocol.ANTHROPIC_MESSAGES)) {
                variable = "LLM_TEST_MESSAGES_MODEL";
                path = "/v1/messages";
            } else {
                variable = "LLM_TEST_GEMINI_MODEL";
                path = "/v1beta/models/" + modelPath(requiredEnv(variable)) + ":generateContent";
            }
            var builder = LlmEndpoint.builder(protocol, URI.create(base + path))
                    .model(requiredEnv(variable)).apiKey(key);
            if (protocol.equals(Protocol.GEMINI)) {
                builder.streamUri(URI.create(base + path.replace(":generateContent", ":streamGenerateContent") + "?alt=sse"));
            }
            endpoints.put(protocol, builder.build());
        }
        return endpoints;
    }

    private static String modelPath(String model) {
        return Stream.of(model.split("/", -1)).map(segment -> {
            require(!segment.isBlank() && !segment.equals(".") && !segment.equals(".."), "invalid-gemini-model-path");
            return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
        }).collect(Collectors.joining("/"));
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        require(value != null && !value.isBlank(), "missing-" + name);
        return value;
    }

    private static void requireSuccessfulHttp(int status) {
        require(status >= 200 && status < 300, "http-" + status);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new VerificationFailure(reason);
    }

    private record TestCase(Protocol client, Protocol upstream, boolean stream) {
        String id() { return client.id() + "->" + upstream.id() + "/" + (stream ? "sse" : "json"); }
    }

    private static final class VerificationFailure extends RuntimeException {
        VerificationFailure(String reason) { super(reason); }
    }

    /** Keeps only observations, never accumulates generated text or raw events. */
    private static final class ProtocolProbe {
        private final Protocol protocol;
        private boolean text;
        private boolean terminal;
        private boolean successfulFinish;

        ProtocolProbe(Protocol protocol) { this.protocol = protocol; }

        void response(String body) {
            JsonNode root = parse(body);
            inspect(root, false, "");
            terminal = true;
        }

        void event(SseEvent<String> event) {
            String data = event.data();
            if (data == null || data.isBlank()) return;
            if ("[DONE]".equals(data.trim())) {
                if (protocol.equals(Protocol.OPENAI_CHAT)) terminal = true;
                // Responses may contain this non-authoritative marker before response.completed.
                if (protocol.equals(Protocol.OPENAI_CHAT) || protocol.equals(Protocol.OPENAI_RESPONSES)) return;
            }
            JsonNode root = parse(data);
            String type = event.event() == null ? root.path("type").asString("") : event.event();
            inspect(root, true, type);
        }

        private JsonNode parse(String data) {
            JsonNode root = JSON.readTree(data);
            require(root != null && root.isObject(), "invalid-protocol-json");
            require(!root.hasNonNull("error") && !"error".equals(root.path("type").asString("")), "protocol-error");
            return root;
        }

        private void inspect(JsonNode root, boolean stream, String type) {
            require(!"error".equals(type), "protocol-error");
            if (protocol.equals(Protocol.OPENAI_CHAT)) {
                JsonNode choice = root.path("choices").path(0);
                text |= !choice.path(stream ? "delta" : "message").path("content").asString("").isBlank();
                successfulFinish |= "stop".equals(choice.path("finish_reason").asString(""));
            } else if (protocol.equals(Protocol.OPENAI_RESPONSES)) {
                if (stream && "response.output_text.delta".equals(type)) {
                    text |= !root.path("delta").asString("").isBlank();
                }
                JsonNode response = stream ? root.path("response") : root;
                for (var item : response.path("output")) {
                    for (var content : item.path("content")) {
                        if ("output_text".equals(content.path("type").asString(""))) {
                            text |= !content.path("text").asString("").isBlank();
                        }
                    }
                }
                if (!stream || "response.completed".equals(type)) {
                    successfulFinish |= "completed".equals(response.path("status").asString(""));
                    terminal = true;
                }
                require(!List.of("response.failed", "response.incomplete").contains(type), "response-not-completed");
            } else if (protocol.equals(Protocol.ANTHROPIC_MESSAGES)) {
                if (stream) {
                    text |= !root.path("delta").path("text").asString("").isBlank();
                    text |= !root.path("content_block").path("text").asString("").isBlank();
                } else {
                    for (var content : root.path("content")) {
                        if ("text".equals(content.path("type").asString(""))) {
                            text |= !content.path("text").asString("").isBlank();
                        }
                    }
                }
                String stop = (stream ? root.path("delta") : root).path("stop_reason").asString("");
                successfulFinish |= "end_turn".equals(stop) || "stop_sequence".equals(stop);
                terminal |= "message_stop".equals(type);
            } else if (protocol.equals(Protocol.GEMINI)) {
                JsonNode candidate = root.path("candidates").path(0);
                for (var part : candidate.path("content").path("parts")) {
                    if (!part.path("thought").asBoolean(false)) text |= !part.path("text").asString("").isBlank();
                }
                successfulFinish |= "STOP".equals(candidate.path("finishReason").asString(""));
                terminal |= successfulFinish;
            }
        }

        void verify() {
            require(text, "response-text-missing");
            require(terminal && successfulFinish, "successful-protocol-termination-missing");
        }
    }
}
