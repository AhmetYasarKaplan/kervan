package io.github.ahmetyasarkaplan.kervan.api;

/** Represents the execution outcome of an individual candidate retriever. */
public enum RetrievalStatus {
  /** The retriever completed successfully and returned at least one candidate. */
  SUCCESS,

  /** The retriever completed successfully, but found zero candidates matching the query. */
  EMPTY,

  /** The retriever encountered an error or exception during execution. */
  FAILED,

  /** The retriever exceeded its allocated timeout budget. */
  TIMED_OUT,

  /** The pipeline's global deadline elapsed before this retriever finished. */
  DEADLINE_EXCEEDED,

  /** The pipeline stopped waiting for this retriever after a fatal outcome or closure. */
  CANCELLED
}
