package io.github.yidasanqian.llm.usage;

import io.github.yidasanqian.llm.protocol.Protocol;
import io.github.yidasanqian.llm.protocol.SseEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.Optional;

/** Per-call bounded usage state. No HTTP subscription, body buffering, estimation or settlement. */
public final class UsageAccumulator {
    private final UsageContext context;
    private final UsageParser parser;
    private final StreamUsageDecoder decoder;
    private ObjectNode anthropic;
    private Usage latest;
    private Usage confirmed;
    private boolean terminal;
    public UsageAccumulator(UsageContext context) { this(context, new UsageParser()); }
    public UsageAccumulator(UsageContext context, UsageParser parser) {
        this.context = Objects.requireNonNull(context, "context");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.decoder = parser.newStreamDecoder(context.protocol());
    }

    public synchronized void accept(SseEvent<String> event) {
        if (terminal || event == null) return;
        if (decoder != null) {
            decoder.decode(event, context).ifPresent(update -> {
                if (update.usage() != null) latest = update.usage();
                if (update.authoritative()) confirmed = update.usage();
                terminal = update.terminal();
            });
            return;
        }
        String data = event.data();
        if (data != null && "[DONE]".equals(data.trim())) {
            if (Protocol.OPENAI_CHAT.equals(context.protocol())) terminal = true;
            return;
        }
        JsonNode root;
        try { root = parser.readTree(data); }
        catch (JacksonException malformed) { return; }
        if (root == null || !root.isObject()) return;
        String type = root.path("type").asString("");
        if (type.isBlank()) type = event.event() == null ? "" : event.event();
        Protocol protocol = context.protocol();
        if (Protocol.ANTHROPIC_MESSAGES.equals(protocol)) {
            JsonNode usage = "message_start".equals(type) ? root.path("message").get("usage") : root.get("usage");
            if (("message_start".equals(type) || "message_delta".equals(type)) && usage != null && usage.isObject()) {
                anthropic = parser.mergeAnthropicUsage(anthropic, usage);
                ObjectNode combined = anthropic.objectNode();
                combined.set("usage", anthropic);
                parser.parse(combined, context).ifPresent(value -> latest = value);
                if ("message_delta".equals(type) && usage.hasNonNull("output_tokens") && latest != null) confirmed = latest;
            }
            terminal = "message_stop".equals(type) || "error".equals(type);
        } else if (Protocol.OPENAI_RESPONSES.equals(protocol)) {
            parser.parse(root, context).ifPresent(value -> latest = value);
            if ("response.completed".equals(type) || "response.incomplete".equals(type)) {
                // Do not confirm a usage snapshot from an earlier non-terminal event.
                confirmed = parser.parse(root, context).orElse(null);
                terminal = true;
            } else if ("response.failed".equals(type) || "error".equals(type)) terminal = true;
        } else if (Protocol.GEMINI.equals(protocol)) {
            Optional<Usage> current = parser.parse(root, context);
            current.ifPresent(value -> latest = value);
            String reason = root.path("candidates").path(0).path("finishReason").asString("");
            if (!reason.isBlank() && !"FINISH_REASON_UNSPECIFIED".equals(reason)) {
                terminal = true;
                confirmed = current.orElse(null);
            }
        } else {
            parser.parse(root, context).ifPresent(value -> {
                latest = value;
                if (Protocol.OPENAI_CHAT.equals(protocol)) confirmed = value;
            });
            terminal = "error".equals(type);
        }
    }
    public synchronized Optional<Usage> latestUsage() { return Optional.ofNullable(latest); }
    public synchronized Optional<Usage> confirmedUsage() { return Optional.ofNullable(confirmed); }
    public synchronized boolean terminal() { return terminal; }

}
