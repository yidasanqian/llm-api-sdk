package io.github.yidasanqian.llm.error;

import io.github.yidasanqian.llm.protocol.Protocol;

/** Error envelope families; Chat Completions and Responses share OpenAI errors. */
public enum ErrorEnvelopeFamily {
    OPENAI, ANTHROPIC, GEMINI;

    public static ErrorEnvelopeFamily from(Protocol protocol) {
        if (Protocol.ANTHROPIC_MESSAGES.equals(protocol)) return ANTHROPIC;
        if (Protocol.GEMINI.equals(protocol)) return GEMINI;
        if (Protocol.OPENAI_CHAT.equals(protocol) || Protocol.OPENAI_RESPONSES.equals(protocol)
                || Protocol.EMBEDDINGS.equals(protocol) || Protocol.RERANK.equals(protocol)) return OPENAI;
        throw new IllegalArgumentException("No built-in error envelope for protocol: " + protocol);
    }
}
