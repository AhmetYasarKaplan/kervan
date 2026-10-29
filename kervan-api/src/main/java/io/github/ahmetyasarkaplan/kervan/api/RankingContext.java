package io.github.ahmetyasarkaplan.kervan.api;

import java.time.Duration;
import java.util.Objects;

/** Execution limits supplied to a ranker or final policy stage. */
public final class RankingContext {
  private final int candidateLimit;
  private final long startedAt;
  private final long budgetNanos;

  /**
   * Creates a context without an additional stage deadline.
   *
   * @param candidateLimit positive maximum output size
   */
  public RankingContext(int candidateLimit) {
    this(candidateLimit, Duration.ofNanos(Long.MAX_VALUE));
  }

  /**
   * Creates a context whose remaining budget decreases monotonically.
   *
   * @param candidateLimit positive maximum output size
   * @param timeBudget nonnegative stage budget, representable in nanoseconds
   */
  public RankingContext(int candidateLimit, Duration timeBudget) {
    if (candidateLimit <= 0) {
      throw new IllegalArgumentException("candidateLimit must be positive: " + candidateLimit);
    }
    Objects.requireNonNull(timeBudget, "timeBudget must not be null");
    if (timeBudget.isNegative()) {
      throw new IllegalArgumentException("timeBudget must not be negative");
    }
    try {
      budgetNanos = timeBudget.toNanos();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("timeBudget is too large", e);
    }
    this.candidateLimit = candidateLimit;
    startedAt = System.nanoTime();
  }

  /**
   * Returns the maximum output size.
   *
   * @return positive candidate limit
   */
  public int candidateLimit() {
    return candidateLimit;
  }

  /**
   * Returns the remaining stage budget, clamped to zero after expiration.
   *
   * @return remaining time
   */
  public Duration remainingBudget() {
    return Duration.ofNanos(Math.max(0, budgetNanos - (System.nanoTime() - startedAt)));
  }

  /**
   * Creates a context without an additional stage deadline.
   *
   * @param candidateLimit positive maximum output size
   * @return ranking context
   */
  public static RankingContext of(int candidateLimit) {
    return new RankingContext(candidateLimit);
  }

  /**
   * Creates a context with a decreasing time budget.
   *
   * @param candidateLimit positive maximum output size
   * @param timeBudget nonnegative stage budget
   * @return ranking context
   */
  public static RankingContext of(int candidateLimit, Duration timeBudget) {
    return new RankingContext(candidateLimit, timeBudget);
  }
}
