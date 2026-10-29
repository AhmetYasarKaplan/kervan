package io.github.ahmetyasarkaplan.kervan.pgvector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.ScoreDirection;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.core.IdentityRanker;
import io.github.ahmetyasarkaplan.kervan.core.RankingPipeline;
import io.github.ahmetyasarkaplan.kervan.core.RetrieverSpec;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

class PgvectorCandidateRetrieverIntegrationTest {
  private static final GenericContainer<?> CONTAINER =
      new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.7-pg17"))
          .withEnv("POSTGRES_DB", "kervan")
          .withEnv("POSTGRES_USER", "kervan")
          .withEnv("POSTGRES_PASSWORD", "test-only")
          .withExposedPorts(5432)
          .waitingFor(
              Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
          .withStartupTimeout(Duration.ofMinutes(2));
  private static PGSimpleDataSource dataSource;

  @BeforeAll
  static void start() throws SQLException {
    CONTAINER.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setURL(
        "jdbc:postgresql://"
            + CONTAINER.getHost()
            + ":"
            + CONTAINER.getMappedPort(5432)
            + "/kervan");
    dataSource.setUser("kervan");
    dataSource.setPassword("test-only");
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("CREATE EXTENSION vector");
      statement.execute(
          "CREATE TABLE items (id text PRIMARY KEY, tenant text, embedding vector(3))");
      statement.execute(
          "INSERT INTO items VALUES ('a', 'public', '[1,0,0]'), ('b', 'public', '[0,1,0]'), ('private', 'other', '[1,0,0]')");
    }
  }

  @AfterAll
  static void stop() {
    CONTAINER.stop();
  }

  @Test
  void shouldRunCosineSimilarityAndL2DistanceWithBoundFilterAndCanonicalFusion() {
    try (var cosine = retriever(false);
        var distance = retriever(true)) {
      var context = RetrievalContext.of(2, Duration.ofSeconds(3));
      var query = new Query("[1,0,0]", "public");
      var cosineHits = cosine.retrieve(query, context);
      assertThat(cosineHits)
          .extracting(SourceCandidate::id)
          .containsExactly("catalog:a", "catalog:b");
      assertThat(cosineHits).extracting(SourceCandidate::score).containsExactly(1.0, 0.0);
      assertThat(distance.retrieve(query, context))
          .extracting(SourceCandidate::score)
          .containsExactly(-0.0, -Math.sqrt(2));
      assertThat(cosine.retrieve(new Query("[1,0,0]", "public' OR '1'='1"), context)).isEmpty();
      assertThat(cosine.retrieve(query, RetrievalContext.of(1, Duration.ofSeconds(3)))).hasSize(1);
      try (var pipeline =
          RankingPipeline.<Query, String>builder("pgvector-fusion")
              .addRetriever(RetrieverSpec.required("pgvector", cosine, 2, Duration.ofSeconds(3)))
              .addRetriever(
                  RetrieverSpec.optional(
                      "recommendations",
                      (q, c) ->
                          List.of(
                              SourceCandidate.of("catalog:a", 0.8),
                              SourceCandidate.of("catalog:external", 0.7)),
                      2,
                      Duration.ofSeconds(1)))
              .ranker(new IdentityRanker<>(), 3)
              .resultLimit(3)
              .timeout(Duration.ofSeconds(5))
              .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
              .build()) {
        var result = pipeline.execute(query);
        assertThat(result.candidates().getFirst().id()).isEqualTo("catalog:a");
        assertThat(result.candidates().getFirst().contributions())
            .extracting(c -> c.source())
            .containsExactly("pgvector", "recommendations");
        assertThat(result.isPartial()).isFalse();
      }
    }
  }

  @Test
  void shouldBoundAndCancelSlowNativeSqlAndRemainReusable() {
    try (var slow =
        PgvectorCandidateRetriever.<String, String>builder(dataSource)
            .statementFactory(
                (connection, query, context) -> connection.prepareStatement("SELECT pg_sleep(10)"))
            .candidateMapper(row -> SourceCandidate.of("never", 1))
            .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
            .build()) {
      long started = System.nanoTime();
      assertThatThrownBy(
              () -> slow.retrieve("secret", RetrievalContext.of(1, Duration.ofMillis(150))))
          .isInstanceOfSatisfying(
              PgvectorRetrievalException.class,
              failure ->
                  assertThat(failure.reason())
                      .isEqualTo(PgvectorRetrievalException.Reason.TIMED_OUT))
          .hasNoCause();
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }
    try (var healthy = retriever(false)) {
      assertThat(
              healthy.retrieve(
                  new Query("[1,0,0]", "public"), RetrievalContext.of(1, Duration.ofSeconds(3))))
          .hasSize(1);
    }
  }

  @Test
  void shouldSanitizeRemoteSqlErrors() {
    try (var retriever =
        PgvectorCandidateRetriever.<String, String>builder(dataSource)
            .statementFactory(
                (connection, query, context) ->
                    connection.prepareStatement("SELECT secret_column FROM private_missing_table"))
            .candidateMapper(row -> SourceCandidate.of("never", 1))
            .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
            .build()) {
      assertThatThrownBy(
              () -> retriever.retrieve("secret", RetrievalContext.of(1, Duration.ofSeconds(2))))
          .hasMessage("pgvector retrieval failed: QUERY_FAILURE")
          .hasNoCause();
    }
  }

  private static PgvectorCandidateRetriever<Query, String> retriever(boolean distance) {
    String sql =
        distance
            ? "SELECT id, tenant, embedding <-> CAST(? AS vector) AS score FROM items WHERE tenant = ? ORDER BY score ASC LIMIT ?"
            : "SELECT id, tenant, 1 - (embedding <=> CAST(? AS vector)) AS score FROM items WHERE tenant = ? ORDER BY score DESC LIMIT ?";
    return PgvectorCandidateRetriever.<Query, String>builder(dataSource)
        .statementFactory(
            (connection, query, context) -> {
              var statement = connection.prepareStatement(sql);
              try {
                statement.setString(1, query.vector());
                statement.setString(2, query.tenant());
                statement.setInt(3, context.candidateLimit());
                return statement;
              } catch (SQLException failure) {
                statement.close();
                throw failure;
              }
            })
        .candidateMapper(
            row ->
                new SourceCandidate<>(
                    "catalog:" + row.getString("id"),
                    row.getDouble("score"),
                    Map.of("tenant", row.getString("tenant"))))
        .scoreDirection(distance ? ScoreDirection.LOWER_IS_BETTER : ScoreDirection.HIGHER_IS_BETTER)
        .build();
  }

  private record Query(String vector, String tenant) {}
}
