# LLM API SDK

[![CI](https://github.com/yidasanqian/llm-api-sdk-java/actions/workflows/ci.yml/badge.svg)](https://github.com/yidasanqian/llm-api-sdk-java/actions/workflows/ci.yml)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-007396?logo=openjdk&logoColor=white)](pom.xml)
[![Jackson 3.3](https://img.shields.io/badge/Jackson-3.3-2E7D32)](pom.xml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue)](LICENSE)
[![Version: 0.2.0](https://img.shields.io/badge/version-0.2.0-orange)](CHANGELOG.md)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.yidasanqian/llm-api-sdk-core?label=Maven%20Central&logo=apachemaven&logoColor=white)](https://central.sonatype.com/artifact/io.github.yidasanqian/llm-api-sdk-core)

**English** | [简体中文](README.zh-CN.md)

Java SDK for gateway and proxy developers: native LLM requests, protocol bridges, upstream usage and extensible pricing. Java **17+**, **Jackson 3**, Reactor and an optional Spring WebFlux transport. No Spring application context, database or Redis is required.

It is a library, not a gateway framework. Your gateway keeps routing, credentials, tenancy and persistence; this SDK owns the parts that are easy to get wrong:

- Speaking OpenAI Chat, OpenAI Responses, Anthropic Messages and Gemini natively, and converting between them with documented field loss instead of silent reshaping.
- Reading usage from the **upstream** protocol, so billing follows what the provider actually reported rather than what the client route implies.
- Refusing to bill silently: missing usage is not zero, and a positive meter without a rate fails instead of charging nothing.

**Status:** `0.2.0`, published to Maven Central. Builds, signatures, Central publication and GitHub Release run in GitHub Actions.

## Install

`llm-api-sdk-webflux` brings in `core` transitively; add `pricing` only when the host bills usage.

```xml
<dependency>
  <groupId>io.github.yidasanqian</groupId>
  <artifactId>llm-api-sdk-webflux</artifactId>
  <version>0.2.0</version>
</dependency>
<dependency>
  <groupId>io.github.yidasanqian</groupId>
  <artifactId>llm-api-sdk-pricing</artifactId>
  <version>0.2.0</version>
</dependency>
```

API reference: [core](https://javadoc.io/doc/io.github.yidasanqian/llm-api-sdk-core) · [webflux](https://javadoc.io/doc/io.github.yidasanqian/llm-api-sdk-webflux) · [pricing](https://javadoc.io/doc/io.github.yidasanqian/llm-api-sdk-pricing).

## Modules

| Artifact (`io.github.yidasanqian`) | Responsibility |
| --- | --- |
| `llm-api-sdk-core` | Native protocols, JSON/SSE conversion, errors and extensible usage decoding; no Spring dependency |
| `llm-api-sdk-webflux` | HTTP/SSE, raw host hooks, cancellation, completion and connection ownership |
| `llm-api-sdk-pricing` | Explicit units/rates, token/request/metered pricing and custom strategies; no I/O |

The SDK does not own routing, credentials storage, token estimation, discounts, currency conversion, accounts, persistent Responses state or settlement. Hosts retain these policies.

## Build

```shell
mvn verify
mvn -Dmaven.test.skip=true install
mvn -f examples/basic/pom.xml test
```

All artifacts use Java 17 bytecode. CI tests Java 17, 21 and 25. The WebFlux module uses Spring Framework 7; Java 17 compatibility is not a promise of Spring Boot 3 / Framework 6 dependency compatibility. Jackson core/databind use `tools.jackson` 3.x; the official shared annotations artifact still uses `com.fasterxml.jackson.annotation`.

## Native requests

Add `llm-api-sdk-webflux` at the matching SDK version, then configure an **exact** endpoint URI. Authentication and model names belong to the caller. A Gemini endpoint uses a separate `streamUri` ending in `:streamGenerateContent?alt=sse`.

```java
var endpoint = LlmEndpoint.builder(Protocol.OPENAI_CHAT,
        URI.create("https://api.openai.com/v1/chat/completions"))
    .model("your-upstream-model")
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .build();
var request = new LlmRequest(endpoint,
    Map.of("messages", List.of(Map.of("role", "user", "content", "Hello"))));

try (var client = LlmClient.builder().build()) {
    // Blocking is only for a standalone main, never a gateway event loop.
    var response = client.execute(request).block();
    System.out.println(response.statusCode());
}
```

`execute` preserves status, headers and raw body, including non-2xx responses. `stream` emits `Headers`, then `Data` events; a non-2xx response emits `Failure` with its raw body. Headers are metadata, not the first converted protocol output. Use bridge readiness when deciding whether a gateway can still fail over.

Each subscription is a new request. Do not subscribe twice to separately obtain usage and payload; use `CallOptions.completionListener`. Raw response/event handlers return `Mono<Void>` and are awaited before forwarding. Hooks must not block. The SDK retains bounded usage/mapper state; the host decides whether to retain full transcripts.

`idleTimeout` measures the interval between observable SSE events, including time spent in raw hooks and waiting for downstream demand. For JSON calls it limits the entire response and raw-response hook. It is not a socket-read-only timeout; account for slow consumers when choosing the value.

`CallOutcome.COMPLETED` means the HTTP/SSE transport finished normally; it does not classify model-level success. A protocol can report an error inside an HTTP 200 body/event. Inspect the original payload or protocol error conversion separately before applying business policy. SDK-created transports disable Reactor Netty's automatic connection-reset retry. When injecting a `WebClient`, its filters, retries and connector configuration remain the caller's responsibility.

Cancellation normally cancels upstream. Setting `continuationTimeout(Duration.ofSeconds(180))` on `CallOptions` explicitly allows bounded upstream draining after downstream cancellation. Only one completion notification is issued per attempt, even during draining; persistent billing idempotency remains the host's responsibility. Closing the client cancels its attempts and disposes its own connection pool; injected WebClient resources remain caller-owned.

Existing gateways that already manage the request lifecycle can use `WebFluxResponses.json(request)` or `WebFluxResponses.sse(request)` with a WebClient request. These retain WebClient's `retrieve()` error behavior and response entity types. The gateway must consume or cancel the returned SSE body; the helper does not collect it, subscribe early, or own usage observation and cancellation policy.

## Protocol bridges

```java
var bridge = new ProtocolBridge(Protocol.OPENAI_CHAT, Protocol.OPENAI_RESPONSES);
var upstreamRequest = bridge.mapRequest(clientRequest, true);
// Set the actual upstream model through LlmEndpoint; inspect raw upstream usage first.
var clientEvents = bridge.mapStream(rawUpstreamEvents, requestId);
```

| Client protocol | Upstream protocol | Built-in |
| --- | --- | --- |
| Same protocol | Same protocol | Pass-through |
| Chat | Responses | Request, JSON/SSE response and errors |
| Chat | Anthropic Messages | Request, JSON/SSE response and errors |
| Anthropic Messages | Responses | Request, JSON/SSE response and errors |
| Anthropic Messages | Chat | Request, JSON/SSE response and errors |
| Gemini | Chat or another protocol | Community extension point; no built-in converter |

The native client also supports Gemini, embeddings, rerank and Seedance video task JSON. These are not promises of arbitrary lossless conversion. The extracted converters retain their existing supported fields and downgrade behavior; review [conversion limitations](docs/protocols.md).

## Usage and pricing

```java
var usage = new UsageParser().parse(rawUpstreamJson,
    new UsageContext(Protocol.OPENAI_RESPONSES)).orElseThrow();
var prices = new PriceSpec(Currency.getInstance("USD"), Map.of(
    UsageDimensions.INPUT_TOKENS, UnitPrice.perMillionTokens(new BigDecimal("2")),
    UsageDimensions.OUTPUT_TOKENS, UnitPrice.perMillionTokens(new BigDecimal("6"))));
var context = new PricingContext(requestStartedAt, ZoneId.of("Asia/Shanghai"));
var cost = new TokenPricingStrategy().calculate(new PricingRequest(usage, prices, context));
```

Rates above are illustrative. Add explicit rates for positive cache meters. Missing usage is not zero; absent rates for positive billable meters fail rather than silently charging zero. Cache writes split into 5m/1h/remaining quantities without double-counting. Context tiers select a complete rate card using the upstream tier basis; they are not progressive brackets. Cost and sale are independent calculator invocations. Monetary results preserve calculation precision; the host applies FX, discounts and settlement rounding.

`Protocol.SEEDANCE_VIDEO` includes succeeded-only task usage decoding and four-tier per-million output-token pricing. Rates and `has_video_input` are caller-supplied; task polling, persistence, idempotency and settlement remain host-owned. See [Seedance video tasks](docs/seedance-video.md).

`ProtocolAdapter`, `UsageDecoder`, streaming usage decoders and `PricingStrategy` are public extension points. Protocol IDs and measurement names are open. [Extension guide](docs/extensions.md) describes community adapters, time-of-use pricing and custom video measurements.

See the runnable [standalone example](examples/basic) and [release instructions](docs/releasing.md).
CI runs the Java 17/21/25 test matrix and the standalone consumer on pushes to `main` and pull requests.

Community adapters and pricing policies are welcome; see [contributing](CONTRIBUTING.md).
Report security issues privately as described in [SECURITY.md](SECURITY.md).

## License

[Apache-2.0](LICENSE).
