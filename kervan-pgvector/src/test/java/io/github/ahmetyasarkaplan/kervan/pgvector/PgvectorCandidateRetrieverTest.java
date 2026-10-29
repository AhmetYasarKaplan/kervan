package io.github.ahmetyasarkaplan.kervan.pgvector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.ScoreDirection;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PgvectorCandidateRetrieverTest {
  private static final RetrievalContext CONTEXT = RetrievalContext.of(2, Duration.ofSeconds(1));

  @Test
  void shouldLimitRowsOrientDistancesAndCloseJdbcResources() {
    AtomicInteger next = new AtomicInteger();
    AtomicInteger maxRows = new AtomicInteger();
    AtomicInteger timeout = new AtomicInteger();
    AtomicBoolean resultClosed = new AtomicBoolean();
    AtomicBoolean statementClosed = new AtomicBoolean();
    AtomicBoolean connectionClosed = new AtomicBoolean();
    ResultSet rows =
        proxy(
            ResultSet.class,
            (method, args) ->
                switch (method) {
                  case "next" -> next.incrementAndGet() <= 5;
                  case "close" -> {
                    resultClosed.set(true);
                    yield null;
                  }
                  default -> null;
                });
    PreparedStatement statement =
        proxy(
            PreparedStatement.class,
            (method, args) ->
                switch (method) {
                  case "executeQuery" -> rows;
                  case "setMaxRows" -> {
                    maxRows.set((int) args[0]);
                    yield null;
                  }
                  case "setQueryTimeout" -> {
                    timeout.set((int) args[0]);
                    yield null;
                  }
                  case "close" -> {
                    statementClosed.set(true);
                    yield null;
                  }
                  default -> null;
                });
    Connection connection =
        proxy(
            Connection.class,
            (method, args) -> {
              if (method.equals("close")) {
                connectionClosed.set(true);
              }
              return null;
            });
    try (var retriever =
        PgvectorCandidateRetriever.<String, String>builder(dataSource(connection))
            .statementFactory((c, q, context) -> statement)
            .candidateMapper(
                row ->
                    new SourceCandidate<>(
                        "catalog:" + next.get(), next.get(), Map.of("tenant", "a")))
            .scoreDirection(ScoreDirection.LOWER_IS_BETTER)
            .build()) {
      var result = retriever.retrieve("ignored", CONTEXT);
      assertThat(result).extracting(SourceCandidate::score).containsExactly(-1.0, -2.0);
      assertThat(result.getFirst().attributes()).containsEntry("tenant", "a");
      assertThat(next).hasValue(2);
      assertThat(maxRows).hasValue(2);
      assertThat(timeout.get()).isPositive();
      assertThat(resultClosed).isTrue();
      assertThat(statementClosed).isTrue();
      assertThat(connectionClosed).isTrue();
    }
  }

  @Test
  void shouldBoundConnectionAcquisitionAndCleanUpLateConnectionsAfterClose() throws Exception {
    CountDownLatch acquiring = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch cleaned = new CountDownLatch(1);
    Connection connection =
        proxy(
            Connection.class,
            (method, args) -> {
              if (method.equals("abort") || method.equals("close")) {
                cleaned.countDown();
              }
              return null;
            });
    DataSource source =
        proxy(
            DataSource.class,
            (method, args) -> {
              if (method.equals("getConnection")) {
                acquiring.countDown();
                boolean done = false;
                while (!done) {
                  try {
                    release.await();
                    done = true;
                  } catch (InterruptedException ignored) {
                  }
                }
                return connection;
              }
              throw new AssertionError("DataSource global settings must never be modified");
            });
    var retriever =
        PgvectorCandidateRetriever.<String, String>builder(source)
            .statementFactory(
                (c, q, context) -> {
                  throw new AssertionError("cancelled acquisition must not prepare SQL");
                })
            .candidateMapper(row -> SourceCandidate.of("x", 1))
            .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
            .build();
    long started = System.nanoTime();
    assertThatThrownBy(
            () -> retriever.retrieve("secret", RetrievalContext.of(1, Duration.ofMillis(80))))
        .isInstanceOfSatisfying(
            PgvectorRetrievalException.class,
            e -> assertThat(e.reason()).isEqualTo(PgvectorRetrievalException.Reason.TIMED_OUT));
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    assertThat(acquiring.await(1, TimeUnit.SECONDS)).isTrue();
    retriever.close();
    release.countDown();
    assertThat(cleaned.await(2, TimeUnit.SECONDS)).isTrue();
    assertThatThrownBy(() -> retriever.retrieve("secret", CONTEXT))
        .hasMessage("pgvector retrieval failed: CLOSED")
        .hasNoCause();
  }

  @Test
  void shouldSanitizeSqlAndMappingFailures() {
    Connection connection = proxy(Connection.class, (method, args) -> null);
    try (var retriever =
        PgvectorCandidateRetriever.<String, String>builder(dataSource(connection))
            .statementFactory(
                (c, q, context) -> {
                  throw new SQLException("password=secret SELECT private", "42000");
                })
            .candidateMapper(row -> SourceCandidate.of("x", 1))
            .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
            .build()) {
      assertThatThrownBy(() -> retriever.retrieve("secret", CONTEXT))
          .hasMessage("pgvector retrieval failed: QUERY_FAILURE")
          .hasNoCause();
    }
    ResultSet rows = proxy(ResultSet.class, (method, args) -> method.equals("next") ? true : null);
    PreparedStatement statement =
        proxy(
            PreparedStatement.class, (method, args) -> method.equals("executeQuery") ? rows : null);
    try (var retriever =
        PgvectorCandidateRetriever.<String, String>builder(dataSource(connection))
            .statementFactory((c, q, context) -> statement)
            .candidateMapper(
                row -> {
                  throw new IllegalArgumentException("private document");
                })
            .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
            .build()) {
      assertThatThrownBy(() -> retriever.retrieve("secret", CONTEXT))
          .hasMessage("pgvector retrieval failed: INVALID_RESPONSE")
          .hasNoCause();
    }
  }

  @Test
  void shouldDiscardSuppressedJdbcCloseFailuresFromInvalidMapping() {
    Connection connection =
        proxy(
            Connection.class,
            (method, args) -> {
              if (method.equals("close")) {
                throw new SQLException("private connection credential");
              }
              return null;
            });
    ResultSet rows =
        proxy(
            ResultSet.class,
            (method, args) -> {
              if (method.equals("close")) {
                throw new SQLException("private row contents");
              }
              return method.equals("next") ? true : null;
            });
    PreparedStatement statement =
        proxy(
            PreparedStatement.class,
            (method, args) -> {
              if (method.equals("close")) {
                throw new SQLException("private SQL text");
              }
              return method.equals("executeQuery") ? rows : null;
            });
    try (var retriever =
        PgvectorCandidateRetriever.<String, String>builder(dataSource(connection))
            .statementFactory((c, q, context) -> statement)
            .candidateMapper(
                row -> {
                  throw new IllegalArgumentException("private document");
                })
            .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
            .build()) {
      assertThatThrownBy(() -> retriever.retrieve("secret", CONTEXT))
          .isInstanceOfSatisfying(
              PgvectorRetrievalException.class,
              failure -> assertThat(failure.getSuppressed()).isEmpty())
          .hasMessage("pgvector retrieval failed: INVALID_RESPONSE")
          .hasNoCause();
    }
  }

  @Test
  void shouldReleaseCancelledInvocationsThatNeverStart() throws Exception {
    var retriever =
        PgvectorCandidateRetriever.<String, String>builder(
                proxy(
                    DataSource.class,
                    (method, args) -> {
                      throw new SQLException("not connected");
                    }))
            .statementFactory((c, q, context) -> null)
            .candidateMapper(row -> null)
            .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
            .build();
    try (retriever) {
      for (int i = 0; i < 100; i++) {
        assertThatThrownBy(
                () -> retriever.retrieve("secret", RetrievalContext.of(1, Duration.ofNanos(1))))
            .hasMessage("pgvector retrieval failed: TIMED_OUT");
      }
      var active = PgvectorCandidateRetriever.class.getDeclaredField("active");
      active.setAccessible(true);
      assertThat((java.util.Set<?>) active.get(retriever)).isEmpty();
    }
  }

  @Test
  void shouldRestoreCallerInterruptFlagAndCancelWork() {
    DataSource source =
        proxy(
            DataSource.class,
            (method, args) -> {
              if (method.equals("getConnection")) {
                Thread.sleep(10_000);
              }
              return null;
            });
    try (var retriever =
        PgvectorCandidateRetriever.<String, String>builder(source)
            .statementFactory((c, q, context) -> null)
            .candidateMapper(row -> null)
            .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
            .build()) {
      Thread.currentThread().interrupt();
      try {
        assertThatThrownBy(() -> retriever.retrieve("secret", CONTEXT))
            .hasMessage("pgvector retrieval failed: INTERRUPTED")
            .hasNoCause();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
      } finally {
        Thread.interrupted();
      }
    }
  }

  private static DataSource dataSource(Connection connection) {
    return proxy(
        DataSource.class, (method, args) -> method.equals("getConnection") ? connection : null);
  }

  @FunctionalInterface
  private interface Call {
    Object invoke(String method, Object[] args) throws Throwable;
  }

  private static <T> T proxy(Class<T> type, Call call) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (object, method, args) -> call.invoke(method.getName(), args)));
  }
}
