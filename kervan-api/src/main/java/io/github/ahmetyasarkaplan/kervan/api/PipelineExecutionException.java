package io.github.ahmetyasarkaplan.kervan.api;

import java.util.List;
import java.util.Objects;

/**
 * A pipeline failure with provider statuses but no provider exception messages or query contents.
 */
@SuppressWarnings("serial")
public final class PipelineExecutionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Categorizes the pipeline failure without exposing provider exception details. */
  public enum Reason {
    /** A required provider failed or timed out. */
    REQUIRED_RETRIEVER_FAILED,
    /** No provider produced a usable result. */
    ALL_RETRIEVERS_FAILED,
    /** The fail-fast policy stopped execution after a provider failure. */
    FAIL_FAST,
    /** The pipeline deadline expired before a usable result could be assembled. */
    GLOBAL_DEADLINE_EXCEEDED,
    /** Fusion, ranking, or another pipeline stage failed. */
    STAGE_FAILED,
    /** The executing thread was interrupted. */
    INTERRUPTED,
    /** The pipeline was closed while the call was active. */
    CLOSED
  }

  /** Name of the pipeline that failed. */
  private final String pipelineName;

  /** Sanitized category of the failure. */
  private final Reason reason;

  /** Provider outcomes in registration order. */
  private final List<? extends RetrievalResult<?>> retrievalResults;

  /**
   * Creates a failure with sanitized provider outcomes in registration order.
   *
   * @param pipelineName name of the failed pipeline
   * @param reason failure category
   * @param retrievalResults provider outcomes without raw exception details
   */
  public PipelineExecutionException(
      String pipelineName, Reason reason, List<? extends RetrievalResult<?>> retrievalResults) {
    super(
        "pipeline '"
            + Objects.requireNonNull(pipelineName, "pipelineName")
            + "' failed: "
            + Objects.requireNonNull(reason, "reason"));
    this.pipelineName = pipelineName;
    this.reason = reason;
    this.retrievalResults =
        List.copyOf(Objects.requireNonNull(retrievalResults, "retrievalResults"));
  }

  /**
   * Returns the failed pipeline's name.
   *
   * @return pipeline name
   */
  public String pipelineName() {
    return pipelineName;
  }

  /**
   * Returns the sanitized failure category.
   *
   * @return failure category
   */
  public Reason reason() {
    return reason;
  }

  /**
   * Returns provider results in registration order, including cancelled unfinished providers.
   *
   * @return immutable provider outcomes
   */
  public List<? extends RetrievalResult<?>> retrievalResults() {
    return retrievalResults;
  }
}
