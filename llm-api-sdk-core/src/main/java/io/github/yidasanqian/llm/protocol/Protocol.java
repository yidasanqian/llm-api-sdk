package io.github.yidasanqian.llm.protocol;

import java.util.Objects;

/**
 * Native wire protocol identifier, independent of a provider's name.
 * IDs are case-sensitive. Community adapters can use their own IDs without changing this library.
 */
public record Protocol(String id) {
    public static final Protocol OPENAI_CHAT = new Protocol("openai-chat");
    public static final Protocol OPENAI_RESPONSES = new Protocol("openai-responses");
    public static final Protocol ANTHROPIC_MESSAGES = new Protocol("anthropic-messages");
    public static final Protocol GEMINI = new Protocol("gemini");
    public static final Protocol SEEDANCE_VIDEO = new Protocol("seedance-video");
    public static final Protocol EMBEDDINGS = new Protocol("embeddings");
    public static final Protocol RERANK = new Protocol("rerank");

    public Protocol {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("Protocol ID must not be blank");
    }

    public static Protocol of(String id) { return new Protocol(id); }

    @Override
    public String toString() { return id; }
}
