# Testkit

Add `kervan-testkit` to the test classpath when validating a custom retriever or
pipeline. Within this multi-module build, the dependency is:

```kotlin
testImplementation(project(":kervan-testkit"))
```

The module depends on `kervan-api` and JUnit Jupiter 6 API. Use the JUnit BOM to
align the Jupiter engine and Platform launcher with the API version. The module
does not add test-only libraries to Kervan's production API or core modules.

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

## Offline relevance evaluation

`RankingEvaluation.evaluate(rankedIds, judgments, k)` compares one query's
best-first canonical ID list with explicit relevance judgments. It returns a
`RankingMetrics` record. The evaluator uses only the JDK; it does not call a
retriever, ranker, or external service.

```java
import io.github.ahmetyasarkaplan.kervan.testkit.RankingEvaluation;
import io.github.ahmetyasarkaplan.kervan.testkit.RankingMetrics;
import java.util.List;
import java.util.Map;

Map<String, Integer> judgments = Map.of(
    "a", 3, "b", 2, "c", 1, "irrelevant", 0);
RankingMetrics baseline = RankingEvaluation.evaluate(
    List.of("irrelevant", "unjudged", "b", "a"), judgments, 3);
RankingMetrics reranked = RankingEvaluation.evaluate(
    List.of("a", "b", "c"), judgments, 3);

// Baseline: P@3 = 1/3, R@3 = 1/3, RR@3 = 1/3,
// nDCG@3 = 0.159696971619899, judged coverage = 2/3.
// Reranked: all five measurements are 1.0 for this fixture.
double change = reranked.ndcgAtK() - baseline.ndcgAtK();
```

The conventions are explicit so fixture comparisons remain reproducible:

| Measurement | Convention |
| --- | --- |
| `precisionAtK()` | Positive judgments in the top K divided by K, even when fewer than K IDs are returned |
| `recallAtK()` | Positive judgments in the top K divided by all positive judgments, including IDs absent from the ranking |
| `reciprocalRankAtK()` | Reciprocal of the first positive judgment's one-based rank; zero when none occurs within K |
| `ndcgAtK()` | DCG divided by ideal DCG; each rank contributes `(2^grade - 1) / log2(rank + 1)` |
| `judgedCoverageAtK()` | IDs with an explicit judgment, including grade zero, divided by the number actually returned within K; zero for an empty ranking |

K must be positive. Grades must be integers from 0 through 30 to keep exponential
gains bounded; grade zero is irrelevant and every positive grade is relevant.
Unjudged IDs contribute zero relevance, while coverage exposes how much of the
returned list was explicitly judged. Low coverage can therefore make comparisons
misleading. Ideal DCG sorts **all** judgments by descending grade, truncates at K,
and uses the same discount. With no positive judgments, recall and nDCG are zero;
an empty ranking produces zero for every measurement.

Null collections, IDs, or grades are rejected. Duplicate IDs anywhere in the
full ranking are rejected, even beyond K, so repeated candidates cannot boost
measurements. Grades are validated for every judgment, including non-retrieved
IDs. The supplied order determines rank; scores and tie handling belong to the
caller. IDs need stable equality and hash codes, and input collections must not
be mutated during evaluation.

These are per-query measurements. Evaluate each query against its own judgments
with a consistent K when comparing systems; averaging per-query reciprocal ranks
gives mean reciprocal rank. The general metric definitions are described in
[Elastic's ranking evaluation reference](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/search-rank-eval).
The exact cutoff, missing-judgment, and zero-denominator conventions above define
this toolkit's behavior.

Small offline fixtures help detect ranking regressions and demonstrate expected
behavior. They do not establish general real-world relevance quality. That
requires representative queries, suitable human judgments, sufficient coverage,
and evaluation on data separate from ranking development.
