package io.github.ahmetyasarkaplan.kervan.elasticsearch;

import java.util.Objects;

/** A sanitized Elasticsearch retrieval failure with no server response or credential details. */
public final class ElasticsearchRetrievalException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Sanitized category of an Elasticsearch retrieval failure. */
  public enum Reason {
    /** The client could not communicate with Elasticsearch. */
    TRANSPORT_FAILURE,
    /** Elasticsearch rejected or failed the request. */
    REMOTE_FAILURE,
    /** Elasticsearch returned an unusable candidate response. */
    INVALID_RESPONSE,
    /** Elasticsearch reported a search timeout. */
    TIMED_OUT
  }

  /** Sanitized failure category. */
  private final Reason reason;

  /**
   * Creates a failure without retaining server response or credential details.
   *
   * @param reason failure category
   */
  public ElasticsearchRetrievalException(Reason reason) {
    super("Elasticsearch retrieval failed: " + Objects.requireNonNull(reason, "reason"));
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
