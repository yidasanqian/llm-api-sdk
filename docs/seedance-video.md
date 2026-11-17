# Seedance video tasks

`Protocol.SEEDANCE_VIDEO` (`seedance-video`) identifies the JSON task protocol used by Volcengine Ark Contents Generations video generation. It is a native, asynchronous HTTP protocol, not an SSE chat protocol and not a bridge target.

## Task transport

The upstream base URL normally ends in `/api/v3`. The four task operations are:

| Operation | Method and path |
| --- | --- |
| Create | `POST /contents/generations/tasks` |
| Get | `GET /contents/generations/tasks/{task_id}` |
| List | `GET /contents/generations/tasks` |
| Cancel or delete | `DELETE /contents/generations/tasks/{task_id}` |

All operations use `Authorization: Bearer <API_KEY>`. `LlmEndpoint` applies that header for `SEEDANCE_VIDEO`. On task creation, `LlmClient.execute` injects `LlmEndpoint.model()` into the JSON body as the upstream model ID.

`LlmClient` sends JSON with POST. Use it for task creation. Query, list and delete operations need a caller-owned `WebClient`; `WebFluxResponses.json(request)` preserves their status, headers and raw body. Task ownership, durable state, polling, retry policy, idempotency and deletion rules remain host responsibilities.

## Usage

The task result reports authoritative usage only after generation succeeds:

```json
{
  "id": "cgt-example",
  "status": "succeeded",
  "resolution": "720p",
  "usage": {
    "completion_tokens": 108900,
    "total_tokens": 108900
  }
}
```

The built-in decoder applies these rules:

- Return no usage unless `status` is exactly `succeeded`.
- Normalize `usage.completion_tokens` to `output_tokens` and copy `resolution` into its measurement attributes.
- Normalize `usage.total_tokens` when present.
- Do not invent input tokens or estimate missing output tokens.
- Preserve an explicit `completion_tokens: 0` as authoritative zero; missing or invalid counters do not become free usage.

```java
var usage = new UsageParser().parse(taskJson,
    new UsageContext(Protocol.SEEDANCE_VIDEO)).orElseThrow();
```

If `succeeded` is present but `completion_tokens` or the final resolution is missing, keep the task pending. Do not substitute duration, frame count or a requested resolution.

## Pricing

Seedance video billing uses the output-token meter and four caller-supplied rates in the model currency:

| Final resolution | Video input | Rate key |
| --- | --- | --- |
| `480p` or `720p` | No | `standardWithoutVideo` |
| `480p` or `720p` | Yes | `standardWithVideo` |
| `1080p` | No | `fullHdWithoutVideo` |
| `1080p` | Yes | `fullHdWithVideo` |

Each rate is an amount per 1,000,000 output tokens:

```text
amount = selected rate × usage.completion_tokens / 1,000,000
```

The caller supplies the model rate snapshot, whether the task contains video input, and the billable instant. The SDK returns the original-currency amount. FX conversion, discounts and settlement rounding remain host responsibilities.

```java
var prices = SeedanceVideoPricingStrategy.priceSpec(Currency.getInstance("CNY"), Map.of(
    SeedanceVideoPricingStrategy.STANDARD_WITHOUT_VIDEO, 70,
    SeedanceVideoPricingStrategy.STANDARD_WITH_VIDEO, 42,
    SeedanceVideoPricingStrategy.FULL_HD_WITHOUT_VIDEO, 77,
    SeedanceVideoPricingStrategy.FULL_HD_WITH_VIDEO, 46));

var context = new PricingContext(taskCreatedAt, ZoneId.of("Asia/Shanghai"),
    Map.of(SeedanceVideoPricingStrategy.HAS_VIDEO_INPUT, "false"));
var cost = new SeedanceVideoPricingStrategy()
    .calculate(new PricingRequest(usage, prices, context));
```

The four prices above are illustrative fixture values, not an SDK-maintained price catalog. The host must snapshot the rates, currency and video-input flag when creating the task, then preserve them until settlement. The strategy rejects estimated usage, incomplete or non-per-million rate cards, unsupported resolutions and a missing or non-boolean `has_video_input`.

The SDK does not create task tables, poll upstream, project query results, calculate quotas, write request logs or guarantee single settlement. Those are host policies described by the task lifecycle above.
