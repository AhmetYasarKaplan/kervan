# Testkit

Add `kervan-testkit` to the test classpath when validating a custom retriever or
pipeline. Within this multi-module build, the dependency is:

```kotlin
testImplementation(project(":kervan-testkit"))
```

The module depends on `kervan-api` and JUnit Jupiter API. It does not add
test-only libraries to Kervan's production API or core modules.

## Retriever contracts

`CandidateRetrieverContract<Q, ID>` is a JUnit interface that checks a
deterministic retriever fixture: canonical IDs appear in provider order, output
is bounded by `RetrievalContext.candidateLimit()`, candidates and IDs are
non-null, scores are finite, and a repeated call returns the same fixture.
Implement the three fixture methods in a test class:

```java
import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.testkit.CandidateRetrieverContract;
import java.util.List;

class CatalogRetrieverContractTest
    implements CandidateRetrieverContract<String, String> {

  private record NativeHit(String sku, double score) {}

  private final CandidateRetriever<String, String> subject =
      (query, context) -> {
        if (!"camera".equals(query)) {
          return List.of();
        }
        List<NativeHit> hits =
            List.of(new NativeHit("sku-17", 2.0), new NativeHit("sku-42", 1.0));
        return hits.stream()
            .limit(context.candidateLimit())
            .map(hit -> SourceCandidate.of(
                "product-" + hit.sku().substring(4), hit.score()))
            .toList();
      };

  @Override
  public CandidateRetriever<String, String> retriever() {
    return subject;
  }

  @Override
  public String query() {
    return "camera";
  }

  @Override
  public List<String> expectedCanonicalIds() {
    return List.of("product-17", "product-42");
  }
}
```

This small fixture maps provider-native SKUs to canonical product IDs. For an
adapter test, return the actual retriever from `retriever()` and back its
provider client with a deterministic fixture. Its expected IDs should be the
application's canonical IDs, not provider-native IDs. The retriever itself has
no name; `RetrieverSpec` supplies the stable name when the pipeline is
assembled. Override `timeBudget()` if the fixture needs a different positive
budget. The contract's inherited default methods run as JUnit tests.

`FailingCandidateRetrieverContract<Q, ID>` checks that a failure fixture throws
the declared `RuntimeException` type. Implement `failingRetriever()`,
`failingQuery()`, and `expectedFailureType()` when testing provider error
translation.

## Fixtures and assertions

| Utility | Use |
| --- | --- |
| `StubRetriever` | Return a fixed ordered list, capped at the requested limit |
| `SlowRetriever` | Delay a delegate to exercise timeout or interruption behavior |
| `FailingRetriever` | Throw a chosen runtime exception on every invocation |
| `FixedOutputFusion` | Isolate downstream stages with a fixed fusion list |
| `FixedOutputRanker` | Isolate final pipeline behavior with a fixed ranking list |
| `PipelineAssertions` | Check ordered candidate IDs, per-provider statuses, and contributing source names |

For example, a pipeline test can check output order and provenance without
repeating result traversal:

```java
PipelineAssertions.assertCandidateIds(result, List.of("product-17", "product-42"));
PipelineAssertions.assertContributingSources(
    result, "product-17", List.of("search", "recommendations"));
```

Use a deterministic provider fixture for the contract tests. A live external
service can change ranking or availability and should be exercised separately
in an integration test.
