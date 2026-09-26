package io.github.ahmetyasarkaplan.kervan.api;

import java.util.List;
import java.util.Objects;

/**
 * A pipeline failure with provider statuses but no provider exception messages or query contents.
 */
@SuppressWarnings("serial")
public final class PipelineExecutionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public enum Reason {
    REQUIRED_RETRIEVER_FAILED,
    ALL_RETRIEVERS_FAILED,
    FAIL_FAST,
    GLOBAL_DEADLINE_EXCEEDED,
    STAGE_FAILED,
    INTERRUPTED,
    CLOSED
  }

  private final String pipelineName;
  private final Reason reason;
  private final List<? extends RetrievalResult<?>> retrievalResults;

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

  public String pipelineName() {
    return pipelineName;
  }

  public Reason reason() {
    return reason;
  }

  /** Provider results in registration order, including cancelled unfinished providers. */
  public List<? extends RetrievalResult<?>> retrievalResults() {
    return retrievalResults;
  }
}
