package io.github.ahmetyasarkaplan.kervan.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Execution context provided to a retriever during a query execution.
 *
 * @param candidateLimit the maximum number of candidates the retriever is allowed to return.
 * @param timeout the time budget allocated for this retrieval attempt.
 */
public record RetrievalContext(int candidateLimit, Duration timeout) {

  public RetrievalContext {
    if (candidateLimit <= 0) {
      throw new IllegalArgumentException("candidateLimit must be positive: " + candidateLimit);
    }
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative: " + timeout);
    }
  }

  /**
   * Convenience factory method for creating a retrieval context.
   */
  public static RetrievalContext of(int candidateLimit, Duration timeout) {
    return new RetrievalContext(candidateLimit, timeout);
  }
}
