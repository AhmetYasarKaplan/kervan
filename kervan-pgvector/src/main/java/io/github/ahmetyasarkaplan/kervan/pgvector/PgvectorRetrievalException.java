package io.github.ahmetyasarkaplan.kervan.pgvector;

import java.util.Objects;

/** A sanitized JDBC retrieval failure without SQL, query values, or driver exception details. */
public final class PgvectorRetrievalException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Sanitized failure categories. */
  public enum Reason {
    /** A connection or network operation failed. */
    TRANSPORT_FAILURE,
    /** SQL execution or preparation failed. */
    QUERY_FAILURE,
    /** Row mapping produced unusable candidates. */
    INVALID_RESPONSE,
    /** The invocation deadline or JDBC timeout expired. */
    TIMED_OUT,
    /** The waiting caller was interrupted. */
    INTERRUPTED,
    /** The retriever has been closed. */
    CLOSED
  }

  /** Sanitized failure category. */
  private final Reason reason;

  /**
   * Creates a failure with no retained driver cause.
   *
   * @param reason sanitized category
   */
  public PgvectorRetrievalException(Reason reason) {
    super("pgvector retrieval failed: " + Objects.requireNonNull(reason, "reason"));
    this.reason = reason;
  }

  /**
   * Returns the sanitized failure category.
   *
   * @return sanitized failure category
   */
  public Reason reason() {
    return reason;
  }
}
