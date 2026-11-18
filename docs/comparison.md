# Why not Spring AI or LangChain4j

**English** | [简体中文](comparison.zh-CN.md)

Short answer: they solve a different problem. Use them for applications; use this SDK for the
gateway data plane.

Spring AI and LangChain4j are application frameworks. They put a typed abstraction in front of the
model call — `ChatClient`, `ChatModel`, `Advisor`, `AiServices` — and normalise the provider
response into a `ChatResponse`. That is the right trade-off for agents, RAG, tool orchestration and
Spring Boot integration, where the application wants a model, not a wire format.

A gateway needs the opposite. It must forward bytes it does not own, convert between the protocol
the client speaks and the protocol the upstream speaks, and bill from what the upstream actually
reported. Normalising early destroys the information a gateway needs:

- The original status code, headers and body, including non-2xx responses.
- The upstream usage payload, which is the only authoritative billing source.
- The distinction between "the transport ended" and "the model succeeded".
- Control over cancellation, draining and one-request-per-subscription semantics.

## Side by side

| | Spring AI | LangChain4j | llm-api-sdk-java |
| --- | --- | --- | --- |
| Role | Application framework | Application framework | Gateway data-plane library |
| Primary abstraction | `ChatClient` / `ChatModel` / Advisor | `AiServices` / `ChatModel` | Raw request and response plus `ProtocolBridge` |
| Wire fidelity | Normalised `ChatResponse` | Normalised `ChatResponse` | Status, headers and original body, including non-2xx |
| Cross-protocol conversion | Provider-oriented adapters | Provider-oriented adapters | Client-to-upstream bridges with terminal-event rules |
| Usage | prompt/completion/total plus native usage | input/output/total tokens, no cost | Normalised meters, confirmed versus partial |
| Cost | Not included | `TokenUsage` carries no price | Separate pricing module with explicit units and rates |
| Call lifecycle | Framework-managed | Framework-managed | One request per subscription, cancellation, bounded drain |
| Spring context required | Yes | Optional | No |

## Where the difference shows up

**Non-2xx responses.** A framework call typically throws or returns an error object. A gateway has
to relay the upstream status and body to the client, and often has to decide whether failover is
still possible. `execute` keeps the raw response, and `stream` emits `Failure` with the raw body.

**Billing.** A normalised usage object is convenient for display and dangerous for billing. OpenAI
cached input is a subset of input; Anthropic splits cache creation and cache read; Responses and
Messages report usage across several events. `UsageParser` reads the upstream protocol and returns
normalised meters that quote the upstream report. A missing value is not zero, and a positive meter
without a rate fails instead of charging nothing.

**Terminal events.** HTTP EOF is not success. Built-in bridges require Chat `[DONE]`, Responses
`response.completed` / `response.incomplete`, or Anthropic `message_stop`. A framework that treats
stream completion as success will bill a truncated answer as a finished one.

**Cancellation.** A downstream disconnect is a policy decision: cancel upstream, or drain it so the
usage report still arrives. `CallOptions.continuationTimeout` makes that decision explicit and
bounded, and emits exactly one completion notification per attempt.

## Composing both

The roles are complementary. A gateway can use this SDK for the request path and billing, while
application code above the gateway keeps using Spring AI or LangChain4j against the gateway's
OpenAI-compatible endpoint. Nothing in this SDK requires replacing an application framework, and
nothing in an application framework gives the gateway raw-payload fidelity or upstream usage.

## When this SDK is the wrong choice

- You are building an agent, a RAG pipeline or a tool-calling service, not a gateway.
- You want Spring Boot auto-configuration and typed chat clients out of the box.
- You need image/audio binaries, realtime WebSocket sessions or provider-hosted conversation state;
  those are outside the current transport scope.

See [protocol support and limitations](protocols.md) for the exact conversion boundaries.
