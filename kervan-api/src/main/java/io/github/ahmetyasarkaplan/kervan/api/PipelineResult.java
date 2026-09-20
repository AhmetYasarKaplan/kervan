package io.github.ahmetyasarkaplan.kervan.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable outcome of executing a ranking pipeline.
 *
 * @param <ID> candidate identifier type.
 * @param pipelineName the name of the executed pipeline.
 * @param candidates final ranked list of candidates (unmodifiable).
 * @param isPartial true if any optional retriever failed or timed out, but partial results were returned.
 * @param duration total wall-clock duration of the pipeline execution.
 * @param successfulStages set of stage names that completed successfully.
 * @param failedStages set of stage names that failed or timed out.
 */
public record PipelineResult<ID>(
    String pipelineName,
    List<Candidate<ID>> candidates,
    boolean isPartial,
    Duration duration,
    Set<String> successfulStages,
    Set<String> failedStages) {

  public PipelineResult {
    Objects.requireNonNull(pipelineName, "pipelineName must not be null");
    if (pipelineName.isBlank()) {
      throw new IllegalArgumentException("pipelineName must not be blank");
    }
    candidates = List.copyOf(candidates);
    Objects.requireNonNull(duration, "duration must not be null");
    if (duration.isNegative()) {
      throw new IllegalArgumentException("duration must not be negative: " + duration);
    }
    successfulStages = Set.copyOf(successfulStages);
    failedStages = Set.copyOf(failedStages);
  }
}
