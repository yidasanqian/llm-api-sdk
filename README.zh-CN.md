# LLM API SDK

[![CI](https://github.com/yidasanqian/llm-api-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/yidasanqian/llm-api-sdk/actions/workflows/ci.yml)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-007396?logo=openjdk&logoColor=white)](pom.xml)
[![Jackson 3.1](https://img.shields.io/badge/Jackson-3.1-2E7D32)](pom.xml)
[![许可证：Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue)](LICENSE)
[![版本：0.1.0](https://img.shields.io/badge/version-0.1.0-orange)](CHANGELOG.md)
[![Maven Central：0.1.0](https://img.shields.io/badge/Maven_Central-0.1.0-blue)](https://central.sonatype.com/artifact/io.github.yidasanqian/llm-api-sdk-core/0.1.0)

[English](README.md) | **简体中文**

面向网关和代理开发者的 Java SDK，提供 LLM 原生协议请求、协议转换、上游 usage 解析和可扩展计价。基于 Java **17+**、**Jackson 3** 和 Reactor，可选 Spring WebFlux 传输模块。无需启动 Spring 应用上下文，也不依赖数据库或 Redis。

**当前状态：** `0.1.0`，已发布到 Maven Central。构建、签名、Central 发布和 GitHub Release 均由 GitHub Actions 自动执行。

## 模块

| Maven artifact（groupId：`io.github.yidasanqian`） | 职责 |
| --- | --- |
| `llm-api-sdk-core` | 原生协议、JSON/SSE 转换、错误转换和可扩展 usage 解码；不依赖 Spring |
| `llm-api-sdk-webflux` | HTTP/SSE、原始响应回调、取消、完成通知和连接资源管理 |
| `llm-api-sdk-pricing` | 明确的计量单位与费率、Token/按次/计量计价，以及自定义策略；无 I/O |

路由、凭据存储、Token 估算、折扣、汇率换算、账户、Responses 状态持久化和结算由宿主应用负责。

## 构建

```shell
mvn verify
mvn -Dmaven.test.skip=true install
mvn -f examples/basic/pom.xml test
```

所有产物使用 Java 17 字节码，CI 配置覆盖 Java 17、21 和 25。WebFlux 模块使用 Spring Framework 7；支持 Java 17 不代表兼容 Spring Boot 3 / Framework 6 的依赖组合。Jackson core/databind 使用 `tools.jackson` 3.x；官方共用的注解库仍使用 `com.fasterxml.jackson.annotation` 包名。

## 原生协议请求

添加版本一致的 `llm-api-sdk-webflux` 依赖，然后配置**完整的端点 URI**。认证信息和模型名称由调用方提供。Gemini 流式请求需要单独配置 `streamUri`，以 `:streamGenerateContent?alt=sse` 结尾。

```java
var endpoint = LlmEndpoint.builder(Protocol.OPENAI_CHAT,
        URI.create("https://api.openai.com/v1/chat/completions"))
    .model("your-upstream-model")
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .build();
var request = new LlmRequest(endpoint,
    Map.of("messages", List.of(Map.of("role", "user", "content", "Hello"))));

try (var client = LlmClient.builder().build()) {
    // 阻塞调用仅用于独立 main 示例，不要在网关事件循环中使用。
    var response = client.execute(request).block();
    System.out.println(response.statusCode());
}
```

`execute` 保留状态码、响应头和原始响应体，包括非 2xx 响应。`stream` 先发送 `Headers`，再发送 `Data` 事件；非 2xx 响应会发送包含原始响应体的 `Failure`。响应头属于元数据，不代表已经输出转换后的协议内容。网关判断是否仍可切换上游时，应使用协议桥接的首输出判断接口。

每次订阅都会发起一个新请求。不要为了分别获取 usage 和响应内容而订阅两次，应使用 `CallOptions.completionListener`。原始响应和事件回调返回 `Mono<Void>`，SDK 等待回调完成后再向下游转发。回调中不能执行阻塞操作。SDK 保留有界的 usage 和转换状态，是否保留完整响应记录由宿主决定。

`idleTimeout` 限制相邻可观察 SSE 事件之间的时间，包含原始事件回调和等待下游消费的时间。对于 JSON 请求，它限制整个响应及原始响应回调的总时间。该配置不是单纯的 socket 读取超时，设置时应考虑慢消费者。

`CallOutcome.COMPLETED` 表示 HTTP/SSE 传输正常完成，不代表模型业务调用成功。协议可能在 HTTP 200 的响应体或事件中报告错误。应用业务策略前，应检查原始内容或协议错误转换结果。SDK 自建的传输客户端会关闭 Reactor Netty 的连接重置自动重试；注入 `WebClient` 时，其过滤器、重试和连接器配置由调用方负责。

默认情况下，下游取消会取消上游请求。通过 `CallOptions` 设置 `continuationTimeout(Duration.ofSeconds(180))`，可以明确允许下游取消后继续读取上游，直到完成或超时。包括续读阶段在内，每次请求尝试只发出一次完成通知；持久化计费的幂等性仍由宿主保证。关闭客户端会取消其请求并释放它自己创建的连接池；注入的 WebClient 资源仍归调用方管理。

已经自行管理请求生命周期的网关，可以将 WebClient 请求传给 `WebFluxResponses.json(request)` 或 `WebFluxResponses.sse(request)`。这两个方法保留 WebClient 的 `retrieve()` 错误处理行为和响应实体类型。网关必须消费或取消返回的 SSE 响应体；这些方法不会收集完整响应或提前订阅，usage 观察和取消策略仍由网关负责。

## 协议转换

```java
var bridge = new ProtocolBridge(Protocol.OPENAI_CHAT, Protocol.OPENAI_RESPONSES);
var upstreamRequest = bridge.mapRequest(clientRequest, true);
// 通过 LlmEndpoint 设置实际上游模型，并先观察原始上游 usage。
var clientEvents = bridge.mapStream(rawUpstreamEvents, requestId);
```

| 客户端协议 | 上游协议 | 内置支持 |
| --- | --- | --- |
| 相同协议 | 相同协议 | 透传 |
| Chat | Responses | 请求、JSON/SSE 响应和错误转换 |
| Chat | Anthropic Messages | 请求、JSON/SSE 响应和错误转换 |
| Anthropic Messages | Responses | 请求、JSON/SSE 响应和错误转换 |
| Anthropic Messages | Chat | 请求、JSON/SSE 响应和错误转换 |
| Gemini | Chat 或其他协议 | 提供社区扩展接口，尚无内置转换器 |

原生客户端还支持 Gemini、embeddings、rerank 和 Seedance 视频任务 JSON。这不代表任意协议间都能无损转换。抽取的转换器保留了既有字段支持范围和降级行为，详见[转换限制](docs/protocols.md)。

## Usage 与计价

```java
var usage = new UsageParser().parse(rawUpstreamJson,
    new UsageContext(Protocol.OPENAI_RESPONSES)).orElseThrow();
var prices = new PriceSpec(Currency.getInstance("USD"), Map.of(
    UsageDimensions.INPUT_TOKENS, UnitPrice.perMillionTokens(new BigDecimal("2")),
    UsageDimensions.OUTPUT_TOKENS, UnitPrice.perMillionTokens(new BigDecimal("6"))));
var context = new PricingContext(requestStartedAt, ZoneId.of("Asia/Shanghai"));
var cost = new TokenPricingStrategy().calculate(new PricingRequest(usage, prices, context));
```

以上费率仅用于示例。缓存用量为正时，也必须提供明确费率。缺失 usage 不等于零用量；应计费用量为正但缺少费率时，计算会失败，不会静默按零计费。缓存写入按 5 分钟、1 小时和剩余用量拆分，避免重复收费。上下文档位根据上游用量基数选择整套费率，不采用累进分段计价。成本和售价分别调用计算器。金额结果保留计算精度，汇率、折扣和结算舍入由宿主处理。

`Protocol.SEEDANCE_VIDEO` 内置“仅成功任务”的 usage 解码和四档每百万输出 Token 计价。费率与 `has_video_input` 由调用方提供；任务轮询、持久化、幂等和结算仍由宿主负责。详见 [Seedance 视频任务](docs/seedance-video.md)。

`ProtocolAdapter`、`UsageDecoder`、流式 usage 解码器和 `PricingStrategy` 都是公开扩展接口。协议标识和计量项名称允许扩展。[扩展指南](docs/extensions.md)介绍了社区协议转换器、峰谷计价和自定义视频计量。

可运行示例见[独立使用示例](examples/basic)，发布步骤见[发布指南](docs/releasing.md)。CI 在推送到 `main` 和 pull request 时运行 Java 17/21/25 测试矩阵及独立消费者示例。

欢迎贡献协议转换器和计价策略，详见[贡献指南](CONTRIBUTING.md)。
安全问题请按 [SECURITY.md](SECURITY.md) 私下报告。

## 许可证

[Apache-2.0](LICENSE)。
