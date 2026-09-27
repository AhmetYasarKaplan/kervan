# Kervan

**Bring candidates from many sources. Return one reliable ranking.**

Kervan is a Java 21 library for coordinating candidates from independently
operated providers. An application can combine search, recommendation, popularity,
or custom provider results into one ranked list with canonical IDs and inspectable
source contributions. Its synchronous pipeline includes concurrent provider
calls, time budgets, failure policies, rank and score fusion, candidate limits,
and source provenance. Optional modules provide an Elasticsearch adapter,
Spring Boot configuration, execution instrumentation, and reusable test fixtures.

## Intended request flow

```text
Request
  -> invoke independent providers concurrently within a deadline
  -> validate and limit each ordered source list
  -> classify provider success, failure, and timeout
  -> merge canonical IDs with source ranks and scores
  -> rank and limit candidates
  -> final ranked results
```

The embedded Java library is the primary design target.

Project status: v1.0 preparation. The APIs and example are available from this
checkout; the release workflow is documented but has not been run here.

Kervan operates across provider boundaries. A search engine can perform its own
lexical, vector, and hybrid retrieval inside one provider request; the application
can then combine that result with independently operated providers. Search indexing,
embedding generation, and model training/serving are outside Kervan's scope.

## Embedded usage

```java
import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.core.IdentityRanker;
import io.github.ahmetyasarkaplan.kervan.core.RankingPipeline;
import io.github.ahmetyasarkaplan.kervan.core.ReciprocalRankFusion;
import io.github.ahmetyasarkaplan.kervan.core.RetrieverSpec;
import java.time.Duration;
import java.util.List;

CandidateRetriever<String, String> search =
    (query, context) -> List.of(SourceCandidate.of("product-1", 4.2));
CandidateRetriever<String, String> recommendations =
    (query, context) -> List.of(SourceCandidate.of("product-1", 0.8));

try (RankingPipeline<String, String> pipeline =
    RankingPipeline.<String, String>builder("discovery")
        .addRetriever(RetrieverSpec.required("search", search, 10, Duration.ofMillis(140)))
        .addRetriever(
            RetrieverSpec.optional("recommendations", recommendations, 10, Duration.ofMillis(100)))
        .fusion(new ReciprocalRankFusion<>(60), 20)
        .ranker(new IdentityRanker<>(), 20)
        .resultLimit(10)
        .timeout(Duration.ofMillis(250))
        .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
        .build()) {

    var result = pipeline.execute("camera");
    // result.candidates() contains ranked IDs and source contributions.
    // result.retrievalResults() contains each provider's status and duration.
}
```

`ReciprocalRankFusion` combines source ranks without comparing their raw scores.
It is also the pipeline default with a rank constant of 60 when `.fusion(...)`
is omitted. An explicit fusion configuration sets both the strategy and its
output limit.
`WeightedMinMaxFusion` instead normalizes each source's returned scores and adds
them using optional positive weights on `RetrieverSpec`. `FirstSeenFusion` remains
available when provider registration order should determine the result. All three
strategies retain each provider's original rank and score for inspection.
Retriever implementations are supplied by the application. The example uses
in-memory lambdas; it does not contact an external provider.

## Modules

| Module | Responsibility |
| --- | --- |
| `kervan-api` | Public contracts and domain-independent value types |
| `kervan-core` | Concurrent pipeline, time budgets, failure handling, built-in fusion strategies, and identity ranker |
| `kervan-elasticsearch` | Official-client adapter that maps one Elasticsearch search response into a provider list |
| `kervan-spring-boot-starter` | Auto-configuration that assembles named pipelines from properties and application beans |
| `kervan-observability` | Optional Micrometer metrics, OpenTelemetry spans, and structured execution logs |
| `kervan-testkit` | Test-only retriever fixtures, contract tests, and pipeline assertions |
| `examples/product-discovery` | Runnable Spring Boot example with Elasticsearch, an HTTP recommendation provider, and in-memory popularity |

Production dependencies flow from `kervan-core` to `kervan-api` to the JDK.
`kervan-elasticsearch` depends on `kervan-api` and the pinned official Java
client. API and core have no external production dependencies. The Maven group
and Java package prefix are `io.github.ahmetyasarkaplan.kervan`.
The Spring Boot starter depends on core and Spring Boot, while the Elasticsearch
adapter remains an independent optional module.
The observability module depends on core and its telemetry APIs; testkit depends
on API and JUnit Jupiter. Neither adds an infrastructure dependency to API or
core.

## Run the product discovery example

With JDK 21 and Docker running, start Elasticsearch and the separate demo
recommendation process, then run the Spring Boot application in another terminal:

```sh
docker compose -f examples/product-discovery/compose.yaml up --wait -d
./gradlew :examples:product-discovery:runRecommendations
```

```sh
./gradlew :examples:product-discovery:run
```

On Windows, replace `./gradlew` with `.\gradlew.bat`. The application seeds a
three-product catalog, executes one native Elasticsearch `query` + `knn` request,
and prints the fused IDs, contributing sources, and provider statuses. See the
[getting-started guide](docs/getting-started.md) for setup details and the
equivalent Spring Boot configuration.

The example's Spring configuration names application retriever beans and keeps
their time and candidate limits visible:

```yaml
kervan:
  pipelines:
    discovery:
      timeout: 3s
      result-size: 5
      failure-policy: partial-results
      retrievers:
        - name: search
          bean: searchRetriever
          candidate-limit: 5
          timeout: 2s
          required: true
        - name: recommendations
          bean: recommendationRetriever
          candidate-limit: 3
          timeout: 700ms
          required: false
```

## Build locally

Install a JDK 21 and make it available through `JAVA_HOME` or `PATH`. The build
uses a Java 21 toolchain and does not download JDKs automatically. Git and network
access to the Gradle distribution, Plugin Portal, and Maven Central are needed
for an initial checkout and build. Docker is required for the Elasticsearch
integration tests included in `check`. A separate Gradle installation is
unnecessary.

```sh
java -version
./gradlew --version
./gradlew clean check
```

On Windows, use `gradlew.bat clean check`.

The wrapper downloads the pinned Gradle distribution and verifies its SHA-256.
`check` runs formatting checks, module tests, coverage reporting, and production
dependency-boundary validation. GitHub Actions uses the same verification command
on Ubuntu with Temurin Java 21.

## Verification and formatting

```sh
./gradlew spotlessCheck
./gradlew spotlessApply
./gradlew :kervan-api:check :kervan-core:check
```

Spotless formats Java with google-java-format and Kotlin build scripts with ktfmt.
JUnit 5 and AssertJ are configured for each module. Compiler warnings are enabled.
Tool versions are pinned in the [version catalog](gradle/libs.versions.toml) and
[wrapper configuration](gradle/wrapper/gradle-wrapper.properties).

Test and coverage reports are generated under each module:

- Tests: `build/reports/tests/test/index.html`
- Coverage: `build/reports/jacoco/test/html/index.html`
- Coverage XML: `build/reports/jacoco/test/jacocoTestReport.xml`

See the [Elasticsearch adapter guide](docs/elasticsearch.md) for a native hybrid
request combined with an independent provider.
See the [Spring Boot guide](docs/spring-boot.md) for YAML configuration, custom
beans, validation, and lifecycle.
See the [observability guide](docs/observability.md) to attach metrics, tracing,
and structured logs, and the [testkit guide](docs/testkit.md) for deterministic
retriever contract tests and pipeline fixtures.
See the [extension guide](docs/extension-guide.md) for implementing custom
retrievers, fusion strategies, and rankers.
See the [release guide](docs/releasing.md) for versioning and publication setup.
See the [public API review](docs/api-review.md) for v1.0 contract decisions and
remaining release checks.

## Design documentation

- [Architecture and module boundaries](docs/architecture.md)
- [Java 21 and Gradle](docs/adr/0001-java-21-and-gradle.md)
- [Framework-independent module boundaries](docs/adr/0002-module-boundaries.md)
- [Provider boundaries and native search capabilities](docs/adr/0003-provider-boundaries.md)
- [Deadlines and failure policies](docs/adr/0004-deadlines-and-failure-policies.md)
- [Cross-provider fusion](docs/adr/0005-cross-provider-fusion.md)
- [Elasticsearch adapter boundary](docs/adr/0006-elasticsearch-adapter.md)
