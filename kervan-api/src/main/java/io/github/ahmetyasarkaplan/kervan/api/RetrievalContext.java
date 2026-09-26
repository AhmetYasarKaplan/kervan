package io.github.ahmetyasarkaplan.kervan.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Invocation settings supplied to one retriever. The time budget is the smaller of its configured
 * timeout and the remaining pipeline deadline when the call begins. Adapters should pass it to
 * provider-native timeout facilities where possible; the core also enforces its own wait limit.
 *
 * @param candidateLimit maximum number of unique candidates requested
 * @param timeBudget positive time available when the invocation begins
 */
public record RetrievalContext(int candidateLimit, Duration timeBudget) {

  public RetrievalContext {
    if (candidateLimit <= 0) {
      throw new IllegalArgumentException("candidateLimit must be positive: " + candidateLimit);
    }
    Objects.requireNonNull(timeBudget, "timeBudget must not be null");
    if (timeBudget.isZero() || timeBudget.isNegative()) {
      throw new IllegalArgumentException("timeBudget must be positive");
    }
  }

  public static RetrievalContext of(int candidateLimit, Duration timeBudget) {
    return new RetrievalContext(candidateLimit, timeBudget);
  }
}
