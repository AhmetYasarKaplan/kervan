package io.github.ahmetyasarkaplan.kervan.qdrant;

import java.util.Objects;

/** A Qdrant retrieval failure without server response or credential details. */
public final class QdrantRetrievalException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Sanitized failure category. */
  public enum Reason {
    /** The client could not communicate with Qdrant. */
    TRANSPORT_FAILURE,
    /** Qdrant rejected or failed the request. */
    REMOTE_FAILURE,
    /** Qdrant returned an unusable candidate response. */
    INVALID_RESPONSE,
    /** The Qdrant call exceeded its remaining time budget. */
    TIMED_OUT,
    /** The calling worker was interrupted. */
    INTERRUPTED
  }

  /** Sanitized category of this failure. */
  private final Reason reason;

  /**
   * Creates a failure without retaining a remote error message.
   *
   * @param reason sanitized failure category
   */
  public QdrantRetrievalException(Reason reason) {
    super("Qdrant retrieval failed: " + Objects.requireNonNull(reason, "reason"));
    this.reason = reason;
  }

  /**
   * Returns the sanitized failure category.
   *
   * @return failure category
   */
  public Reason reason() {
    return reason;
  }
}
