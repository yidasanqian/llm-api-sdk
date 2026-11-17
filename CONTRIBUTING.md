# Contributing

Build with Java 17 or newer and Maven 3.9. The public runtime baseline is Java 17. JSON APIs use Jackson 3 (`tools.jackson`); core and pricing must not depend on Spring, a gateway application, database, Redis or provider credentials.

## Protocol and provider contributions

Start from the [extension contracts](docs/extensions.md). A converter is registered for a client/upstream pair. Its request mapping goes upstream; its JSON/SSE responses and errors go back to the client. A community library can register its adapter without changing this repository.

Supply sanitized upstream fixtures and document their protocol/version. Cover ordinary responses, tool calls, errors, termination, optional SSE metadata, concurrent calls and cancellation. State unsupported content types or fields explicitly. Do not claim a provider is supported based only on a registration test or a guessed payload.

Usage decoders describe the actual upstream counters. Missing counters must not become authoritative zeros. Streaming usage confirmation requires a per-call stream decoder. Preserve cache and auxiliary measurements so the pricing layer can distinguish unsupported usage from a free request.

Pricing extensions receive a caller-selected instant, time zone and rate card. Avoid system clocks, remote price lookups and provider prices embedded in SDK code. Document measurement units and rounding policy; use BigDecimal quantities for fractional video/audio durations.

## Verification

Add focused fixtures for the behavior being contributed, then run the relevant module's tests. Pull requests run [GitHub Actions CI](.github/workflows/ci.yml) on Java 17, 21 and 25 plus the standalone consumer:

```shell
mvn verify
mvn -Dmaven.test.skip=true install
mvn -f examples/basic/pom.xml test
```

Network tests must use a local HTTP server. Live provider calls are optional manual checks and must not require credentials in CI. Keep account/ledger behavior in the host application.

Report suspected vulnerabilities privately according to [SECURITY.md](SECURITY.md). Release artifacts are built and published only by the GitHub Actions release workflow; see [release instructions](docs/releasing.md).
