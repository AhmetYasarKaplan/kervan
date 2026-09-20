package io.github.ahmetyasarkaplan.kervan.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Encapsulates the outcome of candidate retrieval from a single source.
 *
 * @param <ID> the candidate identifier type.
 * @param retrieverName the stable identifier of the retriever source.
 * @param candidates ordered candidates returned by the retriever (unmodifiable).
 * @param duration the wall-clock time taken by the retrieval stage.
 * @param status the operational status of the retrieval.
 * @param failureReason a human-readable failure description, or null if successful.
 */
public record RetrievalResult<ID>(
    String retrieverName,
    List<Candidate<ID>> candidates,
    Duration duration,
    RetrievalStatus status,
    String failureReason) {

  public RetrievalResult {
    Objects.requireNonNull(retrieverName, "retrieverName must not be null");
    if (retrieverName.isBlank()) {
      throw new IllegalArgumentException("retrieverName must not be blank");
    }
    candidates = List.copyOf(candidates);
    Objects.requireNonNull(duration, "duration must not be null");
    if (duration.isNegative()) {
      throw new IllegalArgumentException("duration must not be negative: " + duration);
    }
    Objects.requireNonNull(status, "status must not be null");
  }

  /**
   * Creates a successful retrieval result. If candidates is empty, status is automatically EMPTY.
   */
  public static <ID> RetrievalResult<ID> success(
      String retrieverName, List<Candidate<ID>> candidates, Duration duration) {
    RetrievalStatus status = candidates.isEmpty() ? RetrievalStatus.EMPTY : RetrievalStatus.SUCCESS;
    return new RetrievalResult<>(retrieverName, candidates, duration, status, null);
  }

  /**
   * Creates an empty retrieval result with zero candidates.
   */
  public static <ID> RetrievalResult<ID> empty(String retrieverName, Duration duration) {
    return new RetrievalResult<>(retrieverName, List.of(), duration, RetrievalStatus.EMPTY, null);
  }

  /**
   * Creates a failed retrieval result with an associated failure reason.
   */
  public static <ID> RetrievalResult<ID> failure(
      String retrieverName, String failureReason, Duration duration) {
    return new RetrievalResult<>(
        retrieverName, List.of(), duration, RetrievalStatus.FAILED, failureReason);
  }

  /**
   * Creates a timed-out retrieval result.
   */
  public static <ID> RetrievalResult<ID> timedOut(String retrieverName, Duration duration) {
    return new RetrievalResult<>(
        retrieverName, List.of(), duration, RetrievalStatus.TIMED_OUT, "Retrieval timed out");
  }
}
