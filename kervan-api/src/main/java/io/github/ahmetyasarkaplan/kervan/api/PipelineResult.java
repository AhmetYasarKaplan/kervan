package io.github.ahmetyasarkaplan.kervan.api;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Outcome of executing a ranking pipeline, with defensively copied collections.
 *
 * <p>Stage names must be non-blank and cannot occur in both status sets. A result is partial
 * exactly when its failed-stage set is nonempty. A partial result may contain zero candidates.
 *
 * @param <ID> candidate identifier type.
 * @param pipelineName the name of the executed pipeline.
 * @param candidates final ranked list of candidates (unmodifiable).
 * @param isPartial true if failedStages is nonempty.
 * @param duration total wall-clock duration of the pipeline execution.
 * @param successfulStages set of stage names that completed successfully.
 * @param failedStages set of stage names that failed or timed out.
 */
public record PipelineResult<ID>(
    String pipelineName,
    List<RankedCandidate<ID>> candidates,
    boolean isPartial,
    Duration duration,
    Set<String> successfulStages,
    Set<String> failedStages) {

  public PipelineResult {
    Objects.requireNonNull(pipelineName, "pipelineName must not be null");
    if (pipelineName.isBlank()) {
      throw new IllegalArgumentException("pipelineName must not be blank");
    }
    candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
    Objects.requireNonNull(duration, "duration must not be null");
    if (duration.isNegative()) {
      throw new IllegalArgumentException("duration must not be negative: " + duration);
    }
    successfulStages =
        Set.copyOf(Objects.requireNonNull(successfulStages, "successfulStages must not be null"));
    failedStages =
        Set.copyOf(Objects.requireNonNull(failedStages, "failedStages must not be null"));
    if (successfulStages.stream().anyMatch(String::isBlank)
        || failedStages.stream().anyMatch(String::isBlank)) {
      throw new IllegalArgumentException("stage names must not be blank");
    }
    if (!Collections.disjoint(successfulStages, failedStages)) {
      throw new IllegalArgumentException("successfulStages and failedStages must be disjoint");
    }
    if (isPartial != !failedStages.isEmpty()) {
      throw new IllegalArgumentException("isPartial must match the presence of failed stages");
    }
  }
}
