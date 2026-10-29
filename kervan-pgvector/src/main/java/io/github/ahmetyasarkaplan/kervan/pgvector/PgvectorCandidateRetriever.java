package io.github.ahmetyasarkaplan.kervan.pgvector;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.ScoreDirection;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * Executes application-owned pgvector SQL through a caller-owned JDBC data source.
 *
 * <p>Each invocation borrows and closes one connection. This closeable adapter owns reusable
 * virtual thread executors, never the data source. Its caller wait covers acquisition, preparation,
 * query, mapping, and resource closure. On timeout it interrupts work and requests JDBC
 * cancellation and connection abort asynchronously. Drivers ignoring interruption or cancellation
 * may retain workers and resources after the caller returns; configure pool acquisition and driver
 * socket timeouts too.
 *
 * @param <Q> application query type
 * @param <ID> canonical identifier type
 */
public final class PgvectorCandidateRetriever<Q, ID>
    implements CandidateRetriever<Q, ID>, AutoCloseable {
  private final DataSource dataSource;
  private final PgvectorStatementFactory<Q> statementFactory;
  private final PgvectorCandidateMapper<ID> candidateMapper;
  private final ScoreDirection scoreDirection;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final ExecutorService cancellation = Executors.newVirtualThreadPerTaskExecutor();
  private final Set<Invocation> active = ConcurrentHashMap.newKeySet();
  private final Object lifecycle = new Object();
  private boolean closed;

  private PgvectorCandidateRetriever(Builder<Q, ID> builder) {
    dataSource = builder.dataSource;
    statementFactory = builder.statementFactory;
    candidateMapper = builder.candidateMapper;
    scoreDirection = builder.scoreDirection;
  }

  /**
   * Starts configuration with a caller-owned data source.
   *
   * @param <Q> application query type
   * @param <ID> canonical identifier type
   * @param dataSource caller-owned connection provider
   * @return retriever builder
   */
  public static <Q, ID> Builder<Q, ID> builder(DataSource dataSource) {
    return new Builder<>(dataSource);
  }

  @Override
  public List<SourceCandidate<ID>> retrieve(Q query, RetrievalContext context) {
    Objects.requireNonNull(context, "context must not be null");
    long started = System.nanoTime();
    long budget = nanos(context);
    Invocation invocation = new Invocation();
    Future<List<SourceCandidate<ID>>> future;
    synchronized (lifecycle) {
      if (closed) {
        throw failure(PgvectorRetrievalException.Reason.CLOSED);
      }
      active.add(invocation);
      future = workers.submit(() -> execute(query, context, started, budget, invocation));
      invocation.future = future;
    }
    try {
      long remaining = budget - (System.nanoTime() - started);
      if (remaining <= 0) {
        throw new TimeoutException();
      }
      List<SourceCandidate<ID>> result = future.get(remaining, TimeUnit.NANOSECONDS);
      if (budget - (System.nanoTime() - started) <= 0) {
        throw new TimeoutException();
      }
      return result;
    } catch (TimeoutException e) {
      invocation.cancel();
      throw failure(PgvectorRetrievalException.Reason.TIMED_OUT);
    } catch (InterruptedException e) {
      invocation.cancel();
      Thread.currentThread().interrupt();
      throw failure(PgvectorRetrievalException.Reason.INTERRUPTED);
    } catch (CancellationException e) {
      throw failure(PgvectorRetrievalException.Reason.CLOSED);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof PgvectorRetrievalException sanitized) {
        // JDBC resource closure may attach driver exceptions as suppressed details to the worker
        // failure. Create a clean boundary exception, keeping only its safe category.
        throw failure(sanitized.reason());
      }
      throw failure(PgvectorRetrievalException.Reason.QUERY_FAILURE);
    } finally {
      // A future cancelled before its worker starts never reaches the worker's finally block.
      active.remove(invocation);
    }
  }

  private List<SourceCandidate<ID>> execute(
      Q query, RetrievalContext context, long started, long budget, Invocation invocation) {
    try (Connection connection = dataSource.getConnection()) {
      invocation.connection = connection;
      invocation.checkCancelled();
      int millis = remainingMillis(started, budget);
      try {
        connection.setNetworkTimeout(cancellation, millis);
      } catch (SQLFeatureNotSupportedException ignored) {
        // The bounded caller wait remains effective when a driver lacks this optional facility.
      }
      try (PreparedStatement statement =
          Objects.requireNonNull(
              statementFactory.create(
                  connection,
                  query,
                  RetrievalContext.of(
                      context.candidateLimit(),
                      Duration.ofNanos(remainingNanos(started, budget)))))) {
        invocation.statement = statement;
        invocation.checkCancelled();
        millis = remainingMillis(started, budget);
        statement.setQueryTimeout((int) Math.min(Integer.MAX_VALUE, (millis + 999L) / 1000));
        statement.setMaxRows(context.candidateLimit());
        List<SourceCandidate<ID>> candidates = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
          while (candidates.size() < context.candidateLimit() && rows.next()) {
            invocation.checkCancelled();
            remainingMillis(started, budget);
            try {
              SourceCandidate<ID> candidate = Objects.requireNonNull(candidateMapper.map(rows));
              candidates.add(
                  new SourceCandidate<>(
                      candidate.id(),
                      scoreDirection.orient(candidate.score()),
                      candidate.attributes()));
            } catch (SQLException | RuntimeException e) {
              throw failure(PgvectorRetrievalException.Reason.INVALID_RESPONSE);
            }
          }
        }
        return List.copyOf(candidates);
      }
    } catch (PgvectorRetrievalException e) {
      throw e;
    } catch (SQLTimeoutException e) {
      throw failure(PgvectorRetrievalException.Reason.TIMED_OUT);
    } catch (SQLException e) {
      if (budget - (System.nanoTime() - started) <= 0) {
        throw failure(PgvectorRetrievalException.Reason.TIMED_OUT);
      }
      String state = e.getSQLState();
      throw failure(
          state != null && state.startsWith("08")
              ? PgvectorRetrievalException.Reason.TRANSPORT_FAILURE
              : PgvectorRetrievalException.Reason.QUERY_FAILURE);
    } catch (RuntimeException e) {
      throw failure(PgvectorRetrievalException.Reason.QUERY_FAILURE);
    } finally {
      active.remove(invocation);
    }
  }

  private static long nanos(RetrievalContext context) {
    try {
      return context.timeBudget().toNanos();
    } catch (ArithmeticException ignored) {
      return Long.MAX_VALUE;
    }
  }

  private static long remainingNanos(long started, long budget) {
    long remaining = budget - (System.nanoTime() - started);
    if (remaining <= 0) {
      throw failure(PgvectorRetrievalException.Reason.TIMED_OUT);
    }
    return remaining;
  }

  private static int remainingMillis(long started, long budget) {
    long remaining = remainingNanos(started, budget);
    return (int)
        Math.min(Integer.MAX_VALUE, remaining / 1_000_000 + (remaining % 1_000_000 == 0 ? 0 : 1));
  }

  private static PgvectorRetrievalException failure(PgvectorRetrievalException.Reason reason) {
    return new PgvectorRetrievalException(reason);
  }

  /**
   * Stops accepting retrievals and requests cancellation of active work without waiting on drivers.
   * Closing the application's data source remains the application's responsibility.
   */
  @Override
  public void close() {
    synchronized (lifecycle) {
      if (closed) {
        return;
      }
      closed = true;
      active.forEach(Invocation::cancel);
      workers.shutdownNow();
      cancellation.shutdown();
    }
  }

  private final class Invocation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Connection connection;
    private volatile PreparedStatement statement;
    private volatile Future<?> future;

    void checkCancelled() {
      if (cancelled.get()) {
        requestCleanup();
        throw failure(PgvectorRetrievalException.Reason.TIMED_OUT);
      }
    }

    void cancel() {
      cancelled.set(true);
      Future<?> running = future;
      if (running != null) {
        running.cancel(true);
      }
      requestCleanup();
    }

    void requestCleanup() {
      PreparedStatement currentStatement = statement;
      Connection currentConnection = connection;
      if (currentStatement != null) {
        cleanup(
            () -> {
              try {
                currentStatement.cancel();
              } catch (SQLException ignored) {
              }
            });
      }
      if (currentConnection != null) {
        cleanup(
            () -> {
              try {
                currentConnection.abort(Runnable::run);
              } catch (SQLException ignored) {
              }
            });
      }
    }

    void cleanup(Runnable task) {
      try {
        cancellation.execute(task);
      } catch (RejectedExecutionException ignored) {
        // A late connection/statement registration after close must still receive cleanup.
        Thread.startVirtualThread(task);
      }
    }
  }

  /**
   * Configures native SQL, row mapping, and explicit score direction.
   *
   * @param <Q> application query type
   * @param <ID> canonical identifier type
   */
  public static final class Builder<Q, ID> {
    private final DataSource dataSource;
    private PgvectorStatementFactory<Q> statementFactory;
    private PgvectorCandidateMapper<ID> candidateMapper;
    private ScoreDirection scoreDirection;

    private Builder(DataSource dataSource) {
      this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /**
     * Supplies application-owned prepared SQL and parameter binding.
     *
     * @param statementFactory invocation statement factory
     * @return this builder
     */
    public Builder<Q, ID> statementFactory(PgvectorStatementFactory<Q> statementFactory) {
      this.statementFactory = Objects.requireNonNull(statementFactory, "statementFactory");
      return this;
    }

    /**
     * Supplies canonical ID, raw native score, and attributes from the current JDBC row.
     *
     * @param candidateMapper row mapper
     * @return this builder
     */
    public Builder<Q, ID> candidateMapper(PgvectorCandidateMapper<ID> candidateMapper) {
      this.candidateMapper = Objects.requireNonNull(candidateMapper, "candidateMapper");
      return this;
    }

    /**
     * Declares whether the SQL score is similarity or distance. Distances are negated while native
     * result ordering is retained.
     *
     * @param scoreDirection native SQL score direction
     * @return this builder
     */
    public Builder<Q, ID> scoreDirection(ScoreDirection scoreDirection) {
      this.scoreDirection = Objects.requireNonNull(scoreDirection, "scoreDirection");
      return this;
    }

    /**
     * Validates required SQL, row mapping, and score direction.
     *
     * @return closeable retriever
     */
    public PgvectorCandidateRetriever<Q, ID> build() {
      if (statementFactory == null || candidateMapper == null || scoreDirection == null) {
        throw new IllegalStateException(
            "statementFactory, candidateMapper and scoreDirection are required");
      }
      return new PgvectorCandidateRetriever<>(this);
    }
  }
}
