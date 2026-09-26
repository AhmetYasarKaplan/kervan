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

  Execution startPipeline(String pipelineName);

  enum StageKind {
    RETRIEVER,
    FUSION,
    RANKER
  }

  enum StageStatus {
    SUCCESS,
    EMPTY,
    FAILED,
    TIMED_OUT,
    DEADLINE_EXCEEDED,
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
    Stage NOOP = new Stage() {};

    default Scope activate() {
      return () -> {};
    }

    /** Called once with the settled outcome; failed stages report zero output candidates. */
    default void finished(StageStatus status, Duration duration, int outputCandidates) {}
  }

  /** Per-execution observer state. Stable names come from pipeline configuration, not requests. */
  interface Execution {
    Execution NOOP = new Execution() {};

    /** Retriever inputs are reported as zero; later stages receive their actual input count. */
    default Stage startStage(StageKind kind, String stableName, int inputCandidates) {
      return Stage.NOOP;
    }

    default void succeeded(boolean partial, Duration duration, int outputCandidates) {}

    default void failed(PipelineExecutionException.Reason reason, Duration duration) {}
  }

  static PipelineObserver noOp() {
    return name -> Execution.NOOP;
  }
}
