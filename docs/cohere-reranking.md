# Cohere v2 reranking

`kervan-reranking` supplies a ready HTTP implementation of `BatchScorer<Q, ID>`.
It is optional: `kervan-core` and `kervan-api` have no provider or Jackson dependency.
The adapter accepts application-owned Java 21 `HttpClient` and Jackson 3 `JsonMapper`
instances and never closes them. Its dependency is pinned to
[`tools.jackson.core:jackson-databind:3.2.3`](https://central.sonatype.com/artifact/tools.jackson.core/jackson-databind/3.2.3).

```kotlin
implementation("io.github.ahmetyasarkaplan.kervan:kervan-reranking:<version>")
```

```java
import io.github.ahmetyasarkaplan.kervan.reranking.CohereBatchScorer;
import java.net.http.HttpClient;
import tools.jackson.databind.json.JsonMapper;

var http = HttpClient.newHttpClient(); // Keep this application-owned client for reuse.
var json = JsonMapper.builder().build();
var scorer = CohereBatchScorer.<String, String>builder(http, json)
    .model(System.getenv("COHERE_RERANK_MODEL"))
    .bearerToken(() -> System.getenv("COHERE_API_KEY"))
    .queryMapper(query -> query)
    .documentMapper((query, candidates, context) -> {
      var ids = candidates.stream().map(candidate -> candidate.id()).toList();
      // Application code: one bulk repository operation using context.remainingBudget().
      // Return Map<String, String>: canonical ID -> provider document text.
      return documents.loadTextsByIds(ids, context.remainingBudget());
    })
    .build();
```

Pass `scorer` to `new BatchScoringRanker<>(scorer)` in the core pipeline. The document mapper receives
the complete selected reranking window in one invocation. Bulk hydration belongs to
the application; Kervan imposes no document schema. Return exactly the selected IDs,
each with non-null text. Missing, extra, and duplicate IDs fail the stage.

The [official Cohere v2 reference](https://docs.cohere.com/reference/rerank) defines
`POST https://api.cohere.com/v2/rerank` with bearer authentication and the JSON fields
`model`, `query`, and `documents`. This adapter omits `top_n`, which requests scores
for all documents. The application must select its model explicitly. Results are
mapped from zero-based `index` values to the original canonical IDs, irrespective
of response order. Exactly one finite `relevance_score` is required per input.
Provider metadata and any extra response fields are ignored.

The defaults are 1,000 candidates, 100,000 Java characters per document, a 4 MiB
encoded UTF-8 JSON request, and a 1 MiB response. Configure these using
`maxDocuments`, `maxDocumentChars`, `maxRequestBytes`, and `maxResponseBytes`.
Request bytes are bounded during serialization; chunked response bodies, including
error responses, are bounded during transfer. Provider token truncation is separate
from these local limits; the adapter uses Cohere's default token limit.

`RankingContext.remainingBudget()` decreases across query mapping, bulk hydration,
serialization, and HTTP. Callbacks execute on the caller thread and must cooperate
with the supplied context and interruption; arbitrary blocking application callbacks
cannot be forcibly stopped by this adapter. A two-argument query mapper can inspect
the context: `.queryMapper((query, context) -> ...)`. The HTTP request timeout and
future deadline use the remaining time, covering a stalled response body as well as
headers. Deadline expiry cancels pending HTTP work. Interruption cancels work and
restores the caller's interrupt flag.

The client must use `HttpClient.Redirect.NEVER` (Java's default), preventing bearer
credentials from being forwarded by redirects. `endpoint(URI)` supports custom
HTTPS deployments and local HTTP on `localhost`, `127.0.0.1`, or `[::1]`; user info,
query strings, and fragments are rejected. Use an application-owned client with
appropriate connection and TLS settings. The adapter does not retry, apply fallback,
or own an executor. Configure those behaviors in the pipeline's reranking policy.

Failures use `CohereScoringException`, with fixed sanitized messages and no original
cause, request, response, or credential data. `statusCode()` returns a failing HTTP
status when available, otherwise zero. Callback exceptions are sanitized too.
Applications remain responsible for their HTTP client, mapper, and callback logging.

Tests use a local HTTP server to exercise the wire contract, bulk hydration,
reordered indices, malformed/partial results, size limits, error privacy, delayed
headers, stalled bodies, and interruption. They make no paid provider calls. Live
Cohere credentials, service availability, model access, and relevance quality have
not been validated by those tests. The real-data evaluation example can exercise a
live provider when explicitly enabled with application credentials.
