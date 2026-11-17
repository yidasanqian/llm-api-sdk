package io.github.yidasanqian.llm.client;

import io.github.yidasanqian.llm.protocol.Protocol;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Explicit upstream URLs. Gemini streaming uses a distinct action URL with {@code alt=sse}. */
public final class LlmEndpoint {
    private final Protocol protocol;
    private final URI uri;
    private final URI streamUri;
    private final String model;
    private final String providerId;
    private final Map<String, String> headers;

    private LlmEndpoint(Builder builder) {
        protocol = builder.protocol;
        uri = builder.uri;
        streamUri = builder.streamUri == null ? uri : validateUri(builder.streamUri);
        model = builder.model;
        providerId = builder.providerId;
        Map<String, String> resolved = new LinkedHashMap<>();
        if (protocol.equals(Protocol.ANTHROPIC_MESSAGES)) resolved.put("anthropic-version", "2023-06-01");
        resolved.putAll(builder.headers);
        if (builder.apiKey != null) {
            if (protocol.equals(Protocol.ANTHROPIC_MESSAGES)) resolved.put("x-api-key", builder.apiKey);
            else if (protocol.equals(Protocol.GEMINI)) resolved.put("x-goog-api-key", builder.apiKey);
            else if (protocol.equals(Protocol.OPENAI_CHAT) || protocol.equals(Protocol.OPENAI_RESPONSES)
                    || protocol.equals(Protocol.EMBEDDINGS) || protocol.equals(Protocol.RERANK)
                    || protocol.equals(Protocol.SEEDANCE_VIDEO)) {
                resolved.put("Authorization", "Bearer " + builder.apiKey);
            } else throw new IllegalArgumentException("Custom protocols must provide explicit authentication headers");
        }
        headers = Collections.unmodifiableMap(resolved);
    }

    public static Builder builder(Protocol protocol, URI uri) { return new Builder(protocol, uri); }
    public Protocol protocol() { return protocol; }
    public URI uri() { return uri; }
    public URI streamUri() { return streamUri; }
    public String model() { return model; }
    public String providerId() { return providerId; }
    public Map<String, String> headers() { return headers; }

    @Override
    public String toString() { return "LlmEndpoint[protocol=" + protocol + ", host=" + uri.getHost() + "]"; }

    private static URI validateUri(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Endpoint must be an absolute HTTP(S) URI without user info or fragment");
        }
        return uri;
    }

    public static final class Builder {
        private final Protocol protocol;
        private final URI uri;
        private URI streamUri;
        private String model;
        private String providerId;
        private String apiKey;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private Builder(Protocol protocol, URI uri) {
            this.protocol = Objects.requireNonNull(protocol, "protocol");
            this.uri = validateUri(uri);
        }
        public Builder streamUri(URI value) { streamUri = value; return this; }
        public Builder model(String value) { model = value; return this; }
        public Builder providerId(String value) { providerId = value; return this; }
        public Builder apiKey(String value) { apiKey = value; return this; }
        public Builder headers(Map<String, String> value) { headers.putAll(value); return this; }
        public LlmEndpoint build() { return new LlmEndpoint(this); }
    }
}
