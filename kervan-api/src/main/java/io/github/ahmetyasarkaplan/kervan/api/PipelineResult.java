package io.github.ahmetyasarkaplan.kervan.api;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Outcome of a ranking pipeline. Provider results retain registration order and expose status and
 * duration without raw provider exception details.
 *
 * @param <ID> candidate identifier type
 * @param pipelineName the executed pipeline's name
 * @param candidates final ordered candidates
 * @param duration total elapsed time
 * @param retrievalResults one result per configured provider, in registration order
 */
public record PipelineResult<ID>(
    String pipelineName,
    List<RankedCandidate<ID>> candidates,
    Duration duration,
    List<RetrievalResult<ID>> retrievalResults) {

  /** Validates the outcome and copies both candidate and provider result lists. */
  public PipelineResult {
    Objects.requireNonNull(pipelineName, "pipelineName must not be null");
    if (pipelineName.isBlank()) {
      throw new IllegalArgumentException("pipelineName must not be blank");
    }
    candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
    Objects.requireNonNull(duration, "duration must not be null");
    if (duration.isNegative()) {
      throw new IllegalArgumentException("duration must not be negative");
    }
    retrievalResults =
        List.copyOf(Objects.requireNonNull(retrievalResults, "retrievalResults must not be null"));
    if (retrievalResults.isEmpty()) {
      throw new IllegalArgumentException("retrievalResults must not be empty");
    }
    Set<String> names = new LinkedHashSet<>();
    boolean usable = false;
    for (RetrievalResult<ID> result : retrievalResults) {
      if (!names.add(result.retrieverName())) {
        throw new IllegalArgumentException("retrievalResults must have unique retriever names");
      }
      if (result.status() == RetrievalStatus.CANCELLED) {
        throw new IllegalArgumentException("a successful pipeline cannot contain cancelled work");
      }
      usable |= isUsable(result.status());
    }
    if (!usable) {
      throw new IllegalArgumentException("a pipeline result requires a usable provider");
    }
  }

  /**
   * Whether an optional provider failed or exceeded a time budget.
   *
   * @return {@code true} when the result has an unsuccessful provider
   */
  public boolean isPartial() {
    return retrievalResults.stream().anyMatch(result -> !isUsable(result.status()));
  }

  /**
   * Returns providers that succeeded, including providers with empty results.
   *
   * @return provider names in registration order
   */
  public Set<String> successfulStages() {
    return stageNames(true, false);
  }

  /**
   * Returns providers that failed or exceeded a time budget.
   *
   * @return provider names in registration order
   */
  public Set<String> failedStages() {
    return stageNames(false, false);
  }

  /**
   * Returns providers that timed out or exceeded the global deadline.
   *
   * @return provider names in registration order
   */
  public Set<String> timedOutStages() {
    return stageNames(false, true);
  }

  private Set<String> stageNames(boolean successful, boolean timeoutsOnly) {
    Set<String> names = new LinkedHashSet<>();
    for (RetrievalResult<ID> result : retrievalResults) {
      RetrievalStatus status = result.status();
      if (timeoutsOnly
          ? status == RetrievalStatus.TIMED_OUT || status == RetrievalStatus.DEADLINE_EXCEEDED
          : isUsable(status) == successful) {
        names.add(result.retrieverName());
      }
    }
    return Collections.unmodifiableSet(names);
  }

  private static boolean isUsable(RetrievalStatus status) {
    return status == RetrievalStatus.SUCCESS || status == RetrievalStatus.EMPTY;
  }
}
