# Kervan

[![Maven Central](https://img.shields.io/maven-central/v/io.github.ahmetyasarkaplan.kervan/kervan-core)](https://central.sonatype.com/artifact/io.github.ahmetyasarkaplan.kervan/kervan-core)

**Combine results from separate systems into one ranked list.**

Kervan is a Java 21 library for applications that get possible results from more than
one source. For example, a product page might ask a search engine, a recommendation
service, and a popularity service for products. Kervan calls those sources
concurrently, merges products that share the same application ID, and returns one
ordered list. Each result shows which sources found it. The response also reports
which sources succeeded, failed, or timed out.

Kervan runs inside your application. It does not index data, generate embeddings,
train models, or replace the search and recommendation systems you already use.

## When is it useful?

Use Kervan when **independent systems** contribute candidates to the same result
page and you need shared rules for ranking, timeouts, and partial results. One
source can be required while another is optional: if the optional source times
out, the application can still return the available results and mark them partial.

If all retrieval happens inside one Elasticsearch or OpenSearch request, start
with that engine's native search features. Kervan becomes useful when you also
need to combine its results with another independently called source.

## Install

Kervan 1.0.0 is on Maven Central. Use JDK 21 or later. The core dependency is
enough to build a pipeline with your own candidate providers.

Gradle (Kotlin DSL):

```kotlin
repositories { mavenCentral() }

dependencies {
  implementation("io.github.ahmetyasarkaplan.kervan:kervan-core:1.0.0")
}
```

Maven:

```xml
<dependency>
  <groupId>io.github.ahmetyasarkaplan.kervan</groupId>
  <artifactId>kervan-core</artifactId>
  <version>1.0.0</version>
</dependency>
```

Add an optional module only when you need its integration; see [What's included](#whats-included).

<a id="embedded-usage"></a>

## Use it

This example has two independent candidate providers. Both return `product-2`,
so Kervan includes that ID once and records both contributions. The providers
here use fixed data so you can try the pipeline without running any services.

```java
import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.core.IdentityRanker;
import io.github.ahmetyasarkaplan.kervan.core.RankingPipeline;
import io.github.ahmetyasarkaplan.kervan.core.RetrieverSpec;
import java.time.Duration;
import java.util.List;

public class KervanExample {
  public static void main(String[] args) {
    CandidateRetriever<String, String> search = (query, context) -> List.of(
        SourceCandidate.of("product-1", 4.2),
        SourceCandidate.of("product-2", 3.8));
    CandidateRetriever<String, String> recommendations = (query, context) -> List.of(
        SourceCandidate.of("product-2", 0.9),
        SourceCandidate.of("product-3", 0.7));

    try (var pipeline = RankingPipeline.<String, String>builder("discovery")
        .addRetriever(RetrieverSpec.required("search", search, 10, Duration.ofMillis(140)))
        .addRetriever(RetrieverSpec.optional(
            "recommendations", recommendations, 10, Duration.ofMillis(100)))
        .ranker(new IdentityRanker<>(), 10)
        .resultLimit(10)
        .timeout(Duration.ofMillis(250))
        .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
        .build()) {
      var result = pipeline.execute("camera");
      System.out.println(result.candidates().stream().map(candidate -> candidate.id()).toList());
      System.out.println("partial=" + result.isPartial());
    }
  }
}
```

This prints `[product-2, product-1, product-3]` and `partial=false`.
The default fusion strategy is reciprocal rank fusion: it uses each source's
*ordering*, so the two example scores do not need to be on the same scale.
`result.candidates()` also contains each item's source contributions;
`result.retrievalResults()` contains each provider's status and duration.
If the optional recommendation provider fails, the search results remain available
and `result.isPartial()` reports `true`.

To use real systems, replace the two lambdas with implementations of
`CandidateRetriever`. Return your application's **same canonical ID** for the
same item across sources. Construct a pipeline once and reuse it for requests;
close it when your application shuts down. The [extension guide](docs/extension-guide.md)
explains custom providers, and the [Spring Boot guide](docs/spring-boot.md) shows
how to assemble and manage pipelines with application beans and configuration.

## What's included

| Need | Module |
| --- | --- |
| Concurrent providers, deadlines, partial results, fusion, and batch reranking | `kervan-core` (includes `kervan-api`) |
| Map native results from Elasticsearch, OpenSearch, Qdrant, or pgvector | `kervan-elasticsearch`, `kervan-opensearch`, `kervan-qdrant`, `kervan-pgvector` |
| Ready-made Cohere v2 batch scorer | `kervan-reranking` |
| Spring Boot configuration or an optional HTTP endpoint | `kervan-spring-boot-starter`, `kervan-server` |
| Metrics, traces, logs, or test fixtures | `kervan-observability`, `kervan-testkit` |

Provider adapters accept native requests and map their returned IDs into your
application's canonical IDs. Your application still owns provider clients,
queries, and the domain objects behind those IDs. Optional modules use the same
Maven group and version as `kervan-core`.

## Try the examples

The repository has a service-free example that compares fusion with batch
reranking. With JDK 21 installed, run from the repository root:

```sh
./gradlew :examples:product-discovery:runReranking
```

On Windows, use `.\gradlew.bat` instead of `./gradlew`. The full
[product discovery example](docs/getting-started.md#run-the-product-discovery-example)
combines Elasticsearch, a separate HTTP recommendation service, and popularity
results; it needs Docker and three terminals.

## Learn more

- [Getting started](docs/getting-started.md): installation and runnable examples
- [Architecture](docs/architecture.md): provider boundaries and execution flow
- [Ranking and reranking](docs/reranking.md): scoring, limits, and fallback
- [Spring Boot](docs/spring-boot.md): beans, YAML configuration, and lifecycle
- [Adapters](docs/elasticsearch.md): start with Elasticsearch; see also
  [OpenSearch](docs/opensearch.md), [Qdrant](docs/qdrant.md), and
  [pgvector](docs/pgvector.md)
- [Observability](docs/observability.md) and [testkit](docs/testkit.md)
- [Contributing](CONTRIBUTING.md) and [changelog](CHANGELOG.md)
