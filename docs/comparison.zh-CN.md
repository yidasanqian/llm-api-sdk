# 为什么不用 Spring AI 或 LangChain4j

[English](comparison.md) | **简体中文**

一句话：它们解决的是另一个问题。应用用它们，网关数据面用本 SDK。

Spring AI 和 LangChain4j 是应用框架。它们在模型调用前面加一层类型化抽象——`ChatClient`、`ChatModel`、`Advisor`、`AiServices`——并把提供商响应规整为 `ChatResponse`。对于 Agent、RAG、工具编排和 Spring Boot 集成，这是正确的取舍：应用要的是模型，不是报文格式。

网关的需求正好相反。它必须转发自己并不拥有的字节，在客户端协议和上游协议之间转换，并按上游实际返回的 usage 计费。过早规整会破坏网关需要的信息：

- 原始状态码、响应头和响应体，包括非 2xx 响应。
- 上游 usage 报文，它是唯一权威的计费依据。
- “传输结束”和“模型成功”的区别。
- 对取消、续读和“每次订阅一次请求”语义的控制。

## 逐项对比

| | Spring AI | LangChain4j | llm-api-sdk-java |
| --- | --- | --- | --- |
| 定位 | 应用框架 | 应用框架 | 网关数据面库 |
| 主抽象 | `ChatClient` / `ChatModel` / Advisor | `AiServices` / `ChatModel` | 原始请求响应加 `ProtocolBridge` |
| 报文保真 | 规整为 `ChatResponse` | 规整为 `ChatResponse` | 保留状态码、响应头和原始 body，含非 2xx |
| 跨协议转换 | 面向 provider 的适配 | 面向 provider 的适配 | 客户端到上游的桥接，并校验终止事件 |
| usage | prompt/completion/total 加 native usage | input/output/total token，不含费用 | 统一计量项，区分已确认与部分观测 |
| 费用 | 不提供 | `TokenUsage` 不含价格 | 独立 pricing 模块，显式单位和费率 |
| 调用生命周期 | 框架管理 | 框架管理 | 每次订阅一次请求，可控取消与有界续读 |
| 需要 Spring 上下文 | 是 | 可选 | 否 |

## 差异体现在哪里

**非 2xx 响应。** 框架调用通常会抛异常或返回错误对象。网关必须把上游状态码和响应体转给客户端，还经常要判断是否仍可切换上游。`execute` 保留原始响应，`stream` 在失败时以 `Failure` 携带原始响应体。

**计费。** 规整后的 usage 适合展示，不适合直接计费。OpenAI 的缓存输入是输入的子集；Anthropic 区分缓存创建和缓存读取；Responses 和 Messages 的 usage 分散在多个事件里。`UsageParser` 按上游协议解析，返回引用上游报文的统一计量项。缺失值不等于零，应计费用量为正但缺少费率时会失败，而不是按零收费。

**终止事件。** HTTP EOF 不代表成功。内置桥接要求 Chat `[DONE]`、Responses `response.completed` / `response.incomplete`，或 Anthropic `message_stop`。把流结束当作成功的实现，会把被截断的回答当成完整回答计费。

**取消。** 下游断开是一个策略决定：取消上游，或继续读完以便拿到 usage。`CallOptions.continuationTimeout` 把该决定显式化并加上边界，且每次尝试只发出一次完成通知。

## 两者可以组合

两者互补。网关可以用本 SDK 处理请求路径和计费，网关上层的应用代码继续用 Spring AI 或 LangChain4j 访问网关的 OpenAI 兼容端点。本 SDK 不要求替换应用框架，应用框架也不会给网关带来原始报文保真或上游 usage。

## 什么情况下不该用本 SDK

- 你在做 Agent、RAG 流水线或工具调用服务，而不是网关。
- 你想要开箱即用的 Spring Boot 自动配置和类型化聊天客户端。
- 你需要图像/音频二进制、实时 WebSocket 会话或提供商托管的会话状态；这些不在当前传输范围内。

精确的转换边界见[协议支持与限制](protocols.md)。
