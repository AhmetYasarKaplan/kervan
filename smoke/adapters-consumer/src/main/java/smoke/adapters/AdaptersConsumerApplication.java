package smoke.adapters;

import com.sun.net.httpserver.HttpServer;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import io.github.ahmetyasarkaplan.kervan.api.RerankingScore;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.ScoreDirection;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import io.github.ahmetyasarkaplan.kervan.core.BatchScoringRanker;
import io.github.ahmetyasarkaplan.kervan.core.MaximalMarginalRelevanceRanker;
import io.github.ahmetyasarkaplan.kervan.opensearch.OpenSearchCandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.pgvector.PgvectorCandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.reranking.CohereBatchScorer;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.transport.OpenSearchTransport;
import tools.jackson.databind.json.JsonMapper;

/** Runs published optional-adapter contracts without remote services or a database driver. */
public final class AdaptersConsumerApplication {
  private AdaptersConsumerApplication() {}

  public static void main(String[] args) throws Exception {
    var candidates = List.of(candidate("a", 0.4), candidate("b", 0.3), candidate("c", 0.2));
    verifyCohere(candidates);
    verifyOpenSearch();
    verifyPgvector();
    var mmr =
        new MaximalMarginalRelevanceRanker<String, String>(
            0.5,
            3,
            RankedCandidate::score,
            (first, second) ->
                !first.id().equals("c") && !second.id().equals("c") ? 1.0 : 0.0);
    var diverse = mmr.rank("query", candidates, RankingContext.of(3));
    check(ids(diverse).equals(List.of("a", "c", "b")), "MMR published API ordering failed");
    check(diverse.getFirst() == candidates.getFirst(), "MMR changed candidate identity");
    System.out.println("Published optional adapter consumer passed");
  }

  private static void verifyCohere(List<RankedCandidate<String>> candidates) throws IOException {
    var mapper = JsonMapper.builder().build();
    var requestCount = new AtomicInteger();
    var hydrationCount = new AtomicInteger();
    var handlerFailure = new AtomicReference<Throwable>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v2/rerank",
        exchange -> {
          try (exchange) {
            try {
              requestCount.incrementAndGet();
              var request = mapper.readTree(exchange.getRequestBody().readAllBytes());
              check(exchange.getRequestMethod().equals("POST"), "Cohere method failed");
              check(request.get("model").asString().equals("smoke-model"), "Cohere model failed");
              check(request.get("query").asString().equals("query"), "Cohere query failed");
              var documents = request.get("documents");
              check(documents.size() == 3, "Cohere document count failed");
              for (int index = 0; index < candidates.size(); index++) {
                check(
                    documents.get(index).asString().equals("document-" + candidates.get(index).id()),
                    "Cohere document order failed");
              }
              byte[] response =
                  """
                  {"results":[{"index":2,"relevance_score":0.91},
                              {"index":0,"relevance_score":0.2},
                              {"index":1,"relevance_score":0.51}]}
                  """
                      .getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, response.length);
              exchange.getResponseBody().write(response);
            } catch (Throwable failure) {
              handlerFailure.set(failure);
              exchange.sendResponseHeaders(500, -1);
            }
          }
        });
    server.start();
    try (var http =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2))
            .build()) {
      var scorer =
          CohereBatchScorer.<String, String>builder(http, mapper)
              .endpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v2/rerank"))
              .model("smoke-model")
              .bearerToken(() -> "local-smoke-token")
              .queryMapper(query -> query)
              .documentMapper(
                  (query, window, context) -> {
                    hydrationCount.incrementAndGet();
                    check(window.equals(candidates), "Cohere hydration window failed");
                    return Map.of("c", "document-c", "a", "document-a", "b", "document-b");
                  })
              .maxDocuments(3)
              .build();
      var ranked =
          new BatchScoringRanker<>(scorer)
              .rank("query", candidates, RankingContext.of(3, Duration.ofSeconds(5)));
      check(handlerFailure.get() == null, "Cohere local request validation failed");
      check(requestCount.get() == 1 && hydrationCount.get() == 1, "Cohere batching failed");
      check(ids(ranked).equals(List.of("c", "b", "a")), "Cohere response index mapping failed");
      check(
          ranked.stream().map(RankedCandidate::score).toList().equals(List.of(0.91, 0.51, 0.2)),
          "Cohere model scores failed");
      check(
          ranked.getFirst().rerankingScore().orElseThrow().equals(new RerankingScore(0.2, 0.91)),
          "Cohere score provenance failed");
      check(
          ranked.getFirst().contributions().equals(candidates.getLast().contributions()),
          "Cohere source provenance failed");
    } finally {
      server.stop(0);
    }
  }

  private static void verifyOpenSearch() {
    var nativeRequest = new AtomicReference<SearchRequest>();
    var response =
        new SearchResponse.Builder<Void>()
            .took(1)
            .timedOut(false)
            .shards(shards -> shards.total(1).successful(1).failed(0))
            .hits(
                hits ->
                    hits.hits(
                        List.of(
                            new Hit.Builder<Void>().index("products").id("a").score(0.7).build())))
            .build();
    var transport =
        (OpenSearchTransport)
            Proxy.newProxyInstance(
                OpenSearchTransport.class.getClassLoader(),
                new Class<?>[] {OpenSearchTransport.class},
                (proxy, method, arguments) ->
                    switch (method.getName()) {
                      case "performRequestAsync" -> {
                        nativeRequest.set((SearchRequest) arguments[0]);
                        yield CompletableFuture.completedFuture(response);
                      }
                      case "jsonpMapper" -> new JacksonJsonpMapper();
                      case "close" -> throw new AssertionError("OpenSearch client ownership failed");
                      default -> null;
                    });
    var client = new OpenSearchClient(transport);
    var retriever =
        OpenSearchCandidateRetriever.<String, String, Void>builder(client, Void.class)
            .requestFactory((query, context) -> new SearchRequest.Builder().index("products"))
            .idMapper(Hit::id)
            .attributesMapper(hit -> Map.of("index", hit.index()))
            .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
            .build();
    var candidates = retriever.retrieve("query", RetrievalContext.of(1, Duration.ofSeconds(2)));
    check(candidates.size() == 1 && candidates.getFirst().id().equals("a"), "OpenSearch mapping failed");
    check(candidates.getFirst().score() == 0.7, "OpenSearch native score failed");
    check(candidates.getFirst().attributes().equals(Map.of("index", "products")), "OpenSearch hints failed");
    check(nativeRequest.get().size() == 1, "OpenSearch native candidate limit failed");
  }

  private static void verifyPgvector() throws ClassNotFoundException {
    try {
      Class.forName("org.postgresql.Driver");
      throw new AssertionError("pgvector unexpectedly brings a PostgreSQL driver");
    } catch (ClassNotFoundException expected) {
      // A consumer chooses its own JDBC driver; constructing the adapter needs only JDK JDBC APIs.
    }
    var dataSource =
        (DataSource)
            Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, arguments) -> {
                  throw new AssertionError("pgvector construction must not access the data source");
                });
    try (var retriever =
        PgvectorCandidateRetriever.<String, String>builder(dataSource)
            .statementFactory(
                (connection, query, context) -> {
                  throw new AssertionError("pgvector smoke must not acquire a statement");
                })
            .candidateMapper(row -> SourceCandidate.of("a", 0.1))
            .scoreDirection(ScoreDirection.LOWER_IS_BETTER)
            .build()) {
      check(retriever != null, "pgvector published API construction failed");
    }
  }

  private static RankedCandidate<String> candidate(String id, double score) {
    return new RankedCandidate<>(
        id, score, List.of(new SourceContribution("retrieval", 1, score)), Map.of("title", id));
  }

  private static List<String> ids(List<RankedCandidate<String>> candidates) {
    return candidates.stream().map(RankedCandidate::id).toList();
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
