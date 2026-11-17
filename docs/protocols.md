# Protocol support and limitations

Protocol identifiers describe wire formats, not commercial providers. OpenAI-compatible endpoints may be hosted by many providers; select the protocol from the actual upstream endpoint, not from the client-facing route or provider name.

The built-in bridges translate the currently supported text, function-tool and multimodal content structures; they are not lossless schema-to-schema translators. Request mappers construct destination-specific payloads and can omit source-only fields or unsupported content blocks. They remove or rewrite model-related fields; the host must assign the actual upstream deployment model through the endpoint configuration.

Important preserved boundaries:

- Chat to Responses drops Chat-only fields and unsupported role/content/tool variants according to the existing mapper. Responses hosted state is not reconstructed by the converter.
- Messages/Chat adapters translate tools and supported content; vendor-specific features outside their tested mappings are not automatically portable.
- A Chat `[DONE]` marker is not a Responses terminal event. Final Responses usage must be observed from `response.completed` or `response.incomplete` before conversion ends.
- Built-in bridges require the upstream protocol's terminal event: Chat `[DONE]`, Responses `response.completed`/`response.incomplete`, or Anthropic `message_stop`. HTTP EOF alone does not synthesize success. Missing termination fails before output or becomes a destination-protocol error after output has started.
- Responses tool-argument deltas retain only a character count and SHA-256 prefix digest. Final snapshots can supply a missing suffix, but inconsistent snapshots are ignored and deltas after a final snapshot are rejected. Digest comparison follows UTF-16 code units so split surrogate pairs do not depend on SSE boundaries.
- Anthropic usage can span `message_start` and `message_delta`; Gemini intermediate cumulative counters are not automatically final usage.
- OpenAI cached input is a subset of input; Anthropic cache creation/read accounting differs. Quote normalized upstream meters, never parse the mapped response to derive a bill.
- Gemini native generation works without a bridge. Gemini-to-Chat remains a community extension, demonstrated only by adapter contract tests.
- Seedance video task JSON is supported natively for authoritative succeeded-only usage and four-tier output-token pricing. Task tables, polling, ownership, settlement and deletion remain host responsibilities.
- Binary image/audio, realtime WebSocket sessions, other async video lifecycles and Responses database-backed state are outside v0.1.0 transport scope.

For each new feature, add concrete JSON/SSE fixtures and document how unsupported fields behave. Do not claim support based only on successful compilation or a single happy-path response.
