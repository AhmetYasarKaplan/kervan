# Elasticsearch adapter

The adapter turns one Elasticsearch search response into one ordered Kervan
provider list. The application provides the native request and maps each hit to
a canonical ID. The adapter applies the provider candidate limit and a
server-side timeout. The application owns the Elasticsearch client lifecycle.

The example below combines lexical and vector retrieval inside Elasticsearch,
then combines that one result list with an independent recommendation provider.
The caller supplies the query vector; Kervan does not generate embeddings.

```java
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.core.IdentityRanker;
import io.github.ahmetyasarkaplan.kervan.core.RankingPipeline;
import io.github.ahmetyasarkaplan.kervan.core.RetrieverSpec;
import io.github.ahmetyasarkaplan.kervan.elasticsearch.ElasticsearchCandidateRetriever;
import java.time.Duration;
import java.util.List;

final class HybridSearchExample {
  record ProductQuery(String text, List<Float> vector) {}

  static List<String> run(ElasticsearchClient client) {
    CandidateRetriever<ProductQuery, String> search =
        ElasticsearchCandidateRetriever.<ProductQuery, String, Void>builder(client, Void.class)
            .requestFactory(
                (query, context) ->
                    new SearchRequest.Builder()
                        .index("products")
                        .query(q -> q.match(m -> m.field("title").query(query.text())))
                        .knn(
                            k ->
                                k.field("vector")
                                    .queryVector(query.vector())
                                    .k(context.candidateLimit())
                                    .numCandidates(100))
                        .source(s -> s.fetch(false)))
            .idMapper(hit -> "product:" + hit.id())
            .build();

    CandidateRetriever<ProductQuery, String> recommendations =
        (query, context) -> List.of(SourceCandidate.of("product:42", 0.8));

    try (RankingPipeline<ProductQuery, String> pipeline =
        RankingPipeline.<ProductQuery, String>builder("discovery")
            .addRetriever(RetrieverSpec.required("search", search, 20, Duration.ofMillis(200)))
            .addRetriever(
                RetrieverSpec.optional(
                    "recommendations", recommendations, 10, Duration.ofMillis(100)))
            .ranker(new IdentityRanker<>(), 20)
            .resultLimit(20)
            .timeout(Duration.ofMillis(300))
            .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
            .build()) {
      var result = pipeline.execute(new ProductQuery("camera", List.of(1.0f, 0.0f, 0.0f)));
      return result.candidates().stream().map(candidate -> candidate.id()).toList();
    }
  }
}
```

The example uses Elasticsearch's native `query` plus `knn` hybrid operation in
one request. Kervan's default reciprocal rank fusion applies only across the
Elasticsearch and recommendation provider lists. For native RRF or other
Elasticsearch features, change the request factory; supported features and
licensing depend on the Elasticsearch deployment. The adapter does not alter
provider-native ranking.

The adapter is tested with the official Java client 9.5.3 and Elasticsearch server
9.5.0.
`./gradlew clean check` runs its Testcontainers integration tests and needs a
working Docker daemon. The adapter does not own or close the supplied client.

For the native hybrid operation, see the [Elasticsearch kNN query reference](https://www.elastic.co/docs/reference/query-languages/query-dsl/query-dsl-knn-query).
