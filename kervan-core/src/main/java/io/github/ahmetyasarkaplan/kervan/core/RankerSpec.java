package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.Ranker;
import io.github.ahmetyasarkaplan.kervan.api.RankerFailurePolicy;
import java.time.Duration;
import java.util.Objects;

/**
 * Pipeline-owned limits and explicit failure behavior for one bounded reranking stage.
 *
 * @param <Q> query type
 * @param <ID> candidate identifier type
 * @param ranker ranking implementation
 * @param inputLimit maximum fused candidates offered to the ranker
 * @param outputLimit maximum ranked or fallback candidates passed to the final policy
 * @param timeout positive maximum ranking duration
 * @param failurePolicy behavior when ranking fails, times out, or has no budget
 */
public record RankerSpec<Q, ID>(
    Ranker<Q, ID> ranker,
    int inputLimit,
    int outputLimit,
    Duration timeout,
    RankerFailurePolicy failurePolicy) {
  /** Validates implementation, limits, timeout and failure behavior. */
  public RankerSpec {
    Objects.requireNonNull(ranker, "ranker must not be null");
    Objects.requireNonNull(failurePolicy, "failurePolicy must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (inputLimit <= 0 || outputLimit <= 0) {
      throw new IllegalArgumentException("inputLimit and outputLimit must be positive");
    }
    if (outputLimit > inputLimit) {
      throw new IllegalArgumentException("outputLimit must not exceed inputLimit");
    }
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    try {
      timeout.toNanos();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("timeout is too large", e);
    }
  }
}
