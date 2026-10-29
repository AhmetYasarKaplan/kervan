package io.github.ahmetyasarkaplan.kervan.opensearch;

import java.util.Objects;

/** A sanitized OpenSearch failure without retained query, credentials, or server error body. */
public final class OpenSearchRetrievalException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Sanitized failure category. */
  public enum Reason {
    /** Transport failed to communicate with OpenSearch. */
    TRANSPORT_FAILURE,
    /** OpenSearch rejected or failed a request. */
    REMOTE_FAILURE,
    /** Native response or mapping was unusable. */
    INVALID_RESPONSE,
    /** The adapter or server deadline expired. */
    TIMED_OUT,
    /** Waiting caller was interrupted. */
    INTERRUPTED,
    /** Application request factory failed. */
    INVALID_REQUEST
  }

  /** Sanitized failure category. */
  private final Reason reason;

  /**
   * Creates a sanitized failure without a retained native cause.
   *
   * @param reason failure category
   */
  public OpenSearchRetrievalException(Reason reason) {
    super("OpenSearch retrieval failed: " + Objects.requireNonNull(reason, "reason"));
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
