package io.github.ahmetyasarkaplan.kervan.elasticsearch;

import java.util.Objects;

/** A sanitized Elasticsearch retrieval failure with no server response or credential details. */
public final class ElasticsearchRetrievalException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public enum Reason {
    TRANSPORT_FAILURE,
    REMOTE_FAILURE,
    INVALID_RESPONSE,
    TIMED_OUT
  }

  private final Reason reason;

  public ElasticsearchRetrievalException(Reason reason) {
    super("Elasticsearch retrieval failed: " + Objects.requireNonNull(reason, "reason"));
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
