package io.github.ahmetyasarkaplan.kervan.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Encapsulates the outcome of candidate retrieval from a single source.
 *
 * <p>The core creates this result after invoking a retriever. Only {@link RetrievalStatus#SUCCESS}
 * contains hits, and it must contain at least one. Failure information is limited to the status;
 * exception messages and causes are not retained.
 *
 * @param <ID> the candidate identifier type.
 * @param retrieverName the stable identifier of the retriever source.
 * @param candidates ordered source hits with their original one-based ranks (unmodifiable).
 * @param duration the wall-clock time taken by the retrieval stage.
 * @param status the operational status of the retrieval.
 */
public record RetrievalResult<ID>(
    String retrieverName,
    List<SourceHit<ID>> candidates,
    Duration duration,
    RetrievalStatus status) {

  public RetrievalResult {
    Objects.requireNonNull(retrieverName, "retrieverName must not be null");
    if (retrieverName.isBlank()) {
      throw new IllegalArgumentException("retrieverName must not be blank");
    }
    candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
    Objects.requireNonNull(duration, "duration must not be null");
    if (duration.isNegative()) {
      throw new IllegalArgumentException("duration must not be negative: " + duration);
    }
    Objects.requireNonNull(status, "status must not be null");
    if (status == RetrievalStatus.SUCCESS && candidates.isEmpty()) {
      throw new IllegalArgumentException("SUCCESS requires at least one candidate");
    }
    if (status != RetrievalStatus.SUCCESS && !candidates.isEmpty()) {
      throw new IllegalArgumentException("Only SUCCESS may contain candidates");
    }
  }

  /**
   * Creates a successful retrieval result. If candidates is empty, status is automatically EMPTY.
   */
  public static <ID> RetrievalResult<ID> success(
      String retrieverName, List<SourceHit<ID>> candidates, Duration duration) {
    List<SourceHit<ID>> snapshot =
        List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
    RetrievalStatus status = snapshot.isEmpty() ? RetrievalStatus.EMPTY : RetrievalStatus.SUCCESS;
    return new RetrievalResult<>(retrieverName, snapshot, duration, status);
  }

  /** Creates an empty retrieval result with zero candidates. */
  public static <ID> RetrievalResult<ID> empty(String retrieverName, Duration duration) {
    return new RetrievalResult<>(retrieverName, List.of(), duration, RetrievalStatus.EMPTY);
  }

  /** Creates a failed retrieval result without retaining exception details. */
  public static <ID> RetrievalResult<ID> failure(String retrieverName, Duration duration) {
    return new RetrievalResult<>(retrieverName, List.of(), duration, RetrievalStatus.FAILED);
  }

  /** Creates a timed-out retrieval result. */
  public static <ID> RetrievalResult<ID> timedOut(String retrieverName, Duration duration) {
    return new RetrievalResult<>(retrieverName, List.of(), duration, RetrievalStatus.TIMED_OUT);
  }

  /** Creates a result for a provider unfinished at the pipeline's global deadline. */
  public static <ID> RetrievalResult<ID> deadlineExceeded(String retrieverName, Duration duration) {
    return new RetrievalResult<>(
        retrieverName, List.of(), duration, RetrievalStatus.DEADLINE_EXCEEDED);
  }

  /** Creates a result for work cancelled after the pipeline could no longer continue. */
  public static <ID> RetrievalResult<ID> cancelled(String retrieverName, Duration duration) {
    return new RetrievalResult<>(retrieverName, List.of(), duration, RetrievalStatus.CANCELLED);
  }
}
