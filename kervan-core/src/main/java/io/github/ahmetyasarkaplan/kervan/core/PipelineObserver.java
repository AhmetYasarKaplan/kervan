package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.PipelineExecutionException;
import java.time.Duration;

/**
 * Receives bounded, content-free execution events. Implementations must be thread-safe: retriever
 * scopes run on provider threads, while terminal outcomes are settled by the calling thread.
 * Observer runtime failures are ignored by the pipeline and cannot change its result.
 */
@FunctionalInterface
public interface PipelineObserver {

  /**
   * Starts observing one pipeline execution.
   *
   * @param pipelineName stable configured pipeline name
   * @return per-execution observer state
   */
  Execution startPipeline(String pipelineName);

  /** Stage categories with stable, low-cardinality names. */
  enum StageKind {
    /** One configured candidate provider. */
    RETRIEVER,
    /** Cross-provider fusion. */
    FUSION,
    /** Candidate ranking. */
    RANKER,
    /** Final candidate filtering and ordering. */
    POLICY
  }

  /** Sanitized outcome of one stage. */
  enum StageStatus {
    /** Stage produced candidates. */
    SUCCESS,
    /** Stage completed with no candidates. */
    EMPTY,
    /** Stage failed without exposing its exception. */
    FAILED,
    /** Stage exceeded its own time budget. */
    TIMED_OUT,
    /** Stage remained unfinished at the pipeline deadline. */
    DEADLINE_EXCEEDED,
    /** Stage was cancelled after execution could no longer continue. */
    CANCELLED
  }

  /** A scope activated on the thread that performs one stage. */
  @FunctionalInterface
  interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  /** A stage token may be activated on a worker and completed on a different thread. */
  interface Stage {
    /** Stage token that discards all instrumentation events. */
    Stage NOOP = new Stage() {};

    /**
     * Activates thread-local instrumentation for stage work.
     *
     * @return scope to close after stage work completes
     */
    default Scope activate() {
      return () -> {};
    }

    /**
     * Called once with the settled outcome; failed stages report zero output candidates.
     *
     * @param status sanitized stage status
     * @param duration elapsed stage time
     * @param outputCandidates number of produced candidates
     */
    default void finished(StageStatus status, Duration duration, int outputCandidates) {}
  }

  /** Per-execution observer state. Stable names come from pipeline configuration, not requests. */
  interface Execution {
    /** Execution state that discards all instrumentation events. */
    Execution NOOP = new Execution() {};

    /**
     * Starts a stage. Retriever inputs are reported as zero; later stages receive their actual
     * count.
     *
     * @param kind configured stage category
     * @param stableName configured stage name
     * @param inputCandidates number of candidates entering the stage
     * @return stage token for activation and completion
     */
    default Stage startStage(StageKind kind, String stableName, int inputCandidates) {
      return Stage.NOOP;
    }

    /**
     * Reports a successful pipeline result.
     *
     * @param partial whether any optional provider failed or exceeded a time budget
     * @param duration total pipeline duration
     * @param outputCandidates number of final candidates
     */
    default void succeeded(boolean partial, Duration duration, int outputCandidates) {}

    /**
     * Reports a sanitized pipeline failure.
     *
     * @param reason failure category
     * @param duration total pipeline duration
     */
    default void failed(PipelineExecutionException.Reason reason, Duration duration) {}
  }

  /**
   * Returns an observer that discards all instrumentation events.
   *
   * @return no-op observer
   */
  static PipelineObserver noOp() {
    return name -> Execution.NOOP;
  }
}
