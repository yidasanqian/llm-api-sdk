package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.protocol.Protocol;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, explicitly populated protocol adapter registry. No classpath scanning is performed. */
public final class ProtocolAdapterRegistry {
    private final Map<Pair, ProtocolAdapter> adapters;

    private ProtocolAdapterRegistry(Map<Pair, ProtocolAdapter> adapters) {
        this.adapters = Map.copyOf(adapters);
    }

    public static Builder builder() { return new Builder(); }

    public static ProtocolAdapterRegistry builtins() { return builder().builtins().build(); }

    public static ProtocolAdapterRegistry builtins(ObjectMapper mapper) {
        return builder().builtins(mapper).build();
    }

    ProtocolAdapter require(Protocol client, Protocol upstream) {
        ProtocolAdapter adapter = adapters.get(new Pair(client, upstream));
        if (adapter == null) {
            throw new IllegalArgumentException("Unsupported protocol bridge: " + client + " -> " + upstream);
        }
        return adapter;
    }

    public static final class Builder {
        private final Map<Pair, ProtocolAdapter> adapters = new LinkedHashMap<>();

        public Builder register(Protocol client, Protocol upstream, ProtocolAdapter adapter) {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(upstream, "upstream");
            Objects.requireNonNull(adapter, "adapter");
            if (client.equals(upstream)) {
                throw new IllegalArgumentException("Same-protocol requests are always passed through: " + client);
            }
            if (adapters.putIfAbsent(new Pair(client, upstream), adapter) != null) {
                throw new IllegalArgumentException("Duplicate protocol adapter: " + client + " -> " + upstream);
            }
            return this;
        }

        public Builder builtins() { return builtins(JsonMapper.builder().build()); }

        public Builder builtins(ObjectMapper mapper) {
            BuiltinProtocolAdapters.register(this, Objects.requireNonNull(mapper, "mapper"));
            return this;
        }

        public ProtocolAdapterRegistry build() { return new ProtocolAdapterRegistry(adapters); }
    }

    private record Pair(Protocol client, Protocol upstream) { }
}
