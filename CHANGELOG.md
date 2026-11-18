# Changelog

## 0.2.0 — 2026-11-18

- Rename the repository to `llm-api-sdk-java` and update SCM, CI badge and security advisory links. Maven coordinates are unchanged.
- Upgrade Jackson to 3.3.0, JUnit to 6.1.3, SLF4J to 2.0.20, maven-surefire-plugin to 3.6.1, maven-jar-plugin to 3.5.1 and actions/setup-java to v6.
- Fail release builds early when Central credentials contain whitespace.
- Extend the Central publication wait window.

## 0.1.0 — 2026-11-17

- Native reactive LLM HTTP/SSE clients and four protocol bridge directions.
- Upstream usage normalization, cache accounting and per-call streaming accumulation.
- Seedance video task usage, Bearer transport and four-tier per-million output-token pricing, without owning task persistence or polling.
- Extensible pricing contracts with token, request and custom measured dimensions.
- Java 17 baseline, Jackson 3 and a Java 17/21/25 CI matrix.
- Fix immediate-cancellation retention and completion callbacks that close the client.
- Bound Anthropic usage and Responses tool-argument state; honor injected JSON read constraints.
- Require protocol terminal events in built-in bridges; preserve Responses incomplete usage and ignore stray Chat DONE markers.
- Align Jackson 3.1.8, Spring Framework 7.0.10, Reactor BOM 2025.0.8 and Netty 4.2.19.Final, including the Netty DNS cache-poisoning fix.
- Document idle timeout behavior during hook execution and downstream backpressure.
