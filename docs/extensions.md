# Community extensions

Extensions are explicit Java objects passed by the host. There is no classpath scanning, network-loaded code, global registry or pricing DSL.

## Protocol conversion

`Protocol` is an open value type. Built-in IDs are `openai-chat`, `openai-responses`, `anthropic-messages`, `gemini`, `seedance-video`, `embeddings` and `rerank`. A new wire format can use `Protocol.of("community-format-v1")`; compare protocols by value, not object identity.

Implement `ProtocolAdapter` for one **client -> upstream** pair. Its five operations cover request mapping, non-stream response mapping, stream mapping, first-output readiness and HTTP error mapping. Stream mapping must also translate protocol error events. The return response is mapped in the reverse direction to the client protocol.

```java
var registry = ProtocolAdapterRegistry.builder()
    .builtins()
    .register(Protocol.GEMINI, Protocol.OPENAI_CHAT, new GeminiToChatAdapter())
    .build();
var bridge = new ProtocolBridge(Protocol.GEMINI, Protocol.OPENAI_CHAT, registry);
```

`GeminiToChatAdapter` is the contributor's implementation, not a bundled production converter. The registry rejects duplicate registrations. Same-protocol conversion is pass-through. A host can start with an empty registry to choose a different adapter for a built-in pair.

Contributor tests must cover:

- Request text, tools, supported content parts, unknown/unsupported fields and preservation of the original input.
- JSON and SSE responses, terminal events, HTTP errors and in-stream errors.
- A fresh stream session and readiness predicate for each attempt; interleaved concurrent calls cannot share state.
- One subscription to the input stream, backpressure/cancellation propagation and no eager subscribe or full-stream collection.
- Usage observed from the original upstream body before mapping. Mapping must never fabricate authoritative usage.

The core module's `ProtocolAdapterRegistryTest` and mapper fixtures provide executable registration and conversion examples. Add a contributor adapter as a separate Maven library depending on `llm-api-sdk-core`; users register it without changing SDK source.

## Usage decoding

Register a `UsageDecoder` through immutable `UsageParser.withDecoder(protocol, decoder)`. It receives the JSON tree and `UsageContext` and returns optional normalized usage. Unknown fields remain absent rather than becoming zeros. Meters use a name, BigDecimal quantity, explicit unit and attributes.

Streaming additionally needs protocol-specific confirmation and termination semantics. Supply a per-call stream decoder through `withStreamDecoder`; JSON decoder registration alone must not make every intermediate SSE counter authoritative. Use confirmed usage for billing, and expose partial observations separately.

Built-in streaming parsing uses the supplied `UsageParser` mapper and its JSON read constraints. Anthropic state retains only supported numeric token/cache/TTL counters and aggregates iteration counters before storage. Extra provider usage fields require a stream decoder; arbitrary JSON fields are not accumulated across events.

## Video usage and pricing

`PricingStrategy.calculate(PricingRequest)` is the common boundary. Its input contains `Usage`, a `PriceSpec` and a `PricingContext`; its output is `CostBreakdown`. The host selects the billable instant (for example request acceptance or task completion) and time zone. Strategies never read the system clock or fetch prices.

- **Time-of-use:** a strategy selects a caller-provided rate card from `context.billableAt()` in `context.zoneId()`, then delegates token/meter arithmetic to the built-in strategy. Define interval boundaries and the billable instant in the host's tariff contract.
- **Seedance:** `Protocol.SEEDANCE_VIDEO` has a built-in succeeded-only usage decoder and `SeedanceVideoPricingStrategy`. It bills actual output tokens with four caller-supplied rates selected by final resolution and an explicit `has_video_input` pricing attribute. See [Seedance video tasks](seedance-video.md). Task persistence, polling and settlement remain host-owned.
- **Custom video:** a decoder can emit `video_seconds`, quantity `2.5`, unit `second`, with attributes such as `resolution=1080p`. A strategy selects the explicit rate from those attributes and delegates to `MeteredPricingStrategy`. Integer-only token counters do not limit video measurements.
- **Different units:** the same meter can be priced per second, per frame or per pixel only when its usage unit matches the selected rate. Conversion requires an explicit caller/provider contract; the SDK does not infer it.
- **Currencies:** each price specification and result has one currency. FX remains an explicit host operation.

The pricing tests demonstrate a peak/off-peak boundary, a fractional-duration custom video charge and the Seedance four-tier output-token calculation using invented rates. Provider prices and rate changes are never embedded in SDK code.
