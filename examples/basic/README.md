# Standalone consumer

This example has no SDK parent and is intentionally outside its Maven reactor. It proves that installed or published artifacts expose sufficient transitive dependencies.

From the SDK root, install the SDK, then run:

```shell
mvn "-Dmaven.test.skip=true" install
mvn -f examples/basic/pom.xml test
mvn -f examples/basic/pom.xml org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=example.StandaloneExample
```

The default main only maps and prices a local fixture. To make a real, potentially billable model request, explicitly set `LLM_ENDPOINT` (the full Chat Completions URL), `LLM_MODEL` and `LLM_API_KEY`, and add `-Dexec.args=call`. Do not put keys in source files or command-line arguments.

The example defaults to `0.1.0`. Before Central publication, install the SDK from the repository root. After publication, verify the same version with an empty Maven local repository to confirm resolution from Central.

## Manual live protocol verification

`example.LiveProtocolVerification` is an opt-in, potentially billable verification tool. It is never run by tests or CI. Configure these environment variables before invoking it:

| Variable | Value |
| --- | --- |
| `LLM_TEST_BASE_URL` | API root, for example `https://your-gateway.example/api`; do not include `/v1`, credentials, query parameters or a fragment |
| `LLM_TEST_API_KEY` | A valid key authorized for the selected models |
| `LLM_TEST_CHAT_MODEL` | Model available through `/v1/chat/completions` |
| `LLM_TEST_RESPONSES_MODEL` | Model available through `/v1/responses` |
| `LLM_TEST_MESSAGES_MODEL` | Model available through `/v1/messages` |
| `LLM_TEST_GEMINI_MODEL` | Model available through `/v1beta/models/{model}:generateContent`; `provider/model` is supported |

Keep credentials in environment variables; do not put them in source files, Maven arguments, or captured logs. There are no default hosts, models or keys. The SDK selects native authentication headers through `LlmEndpoint.apiKey()`.

After installing the SDK, run from the SDK root:

```shell
mvn -f examples/basic/pom.xml compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=example.LiveProtocolVerification -Dexec.args=all
```

The default `all` mode makes **16 sequential requests**: JSON and SSE for each of the four native protocols, then JSON and SSE for Chat → Responses, Chat → Messages, Messages → Responses, and Messages → Chat. The arrow denotes client → upstream. The configured upstream model and endpoint receive the converted request; the SDK maps the response back to the client protocol.

Use `-Dexec.args=native-only` or `-Dexec.args=bridge-only` for **8 requests** when only one group needs verification. Bridge-only does not require the Gemini model. Each call asks `只回复OK`, caps output at 512 tokens, has a 90-second timeout, and is not retried. Select models that can produce a short text answer within that limit; a reasoning-only or truncated answer fails verification.

Models must accept the target protocol's generated request parameters. Chat inputs use `max_completion_tokens`; Messages inputs use `max_tokens`, which the Messages → Chat adapter currently preserves. The tool sends the actual adapter output and does not rewrite it for individual models. An HTTP rejection is reported by status code without printing the provider's error body.

A case passes only when HTTP is successful, both raw and mapped responses contain text and a successful protocol ending, and the SDK completion callback reports confirmed **raw upstream** input/output usage. A missing usage value fails; it is not treated as zero. SSE validation uses one subscription and retains only observation flags. Output contains case names, numeric status/usage and a safe result category; it never prints keys, headers, response bodies or arbitrary exception messages. Any failed case gives a nonzero process exit status. This verifies the SDK boundary; when the target is a gateway, its internal provider selection and billing require separate server-side evidence.
