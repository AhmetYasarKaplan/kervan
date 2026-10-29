package io.github.ahmetyasarkaplan.kervan.reranking;

/**
 * A sanitized scoring failure that never retains request data, response data, or an original cause.
 */
public final class CohereScoringException extends Exception {
  private static final long serialVersionUID = 1L;

  /** HTTP status associated with the failure, or zero when unavailable. */
  private final int statusCode;

  CohereScoringException(String message) {
    this(message, 0);
  }

  CohereScoringException(String message, int statusCode) {
    super(message, null, false, true);
    this.statusCode = statusCode;
  }

  /**
   * Returns the failing HTTP status, if the provider returned a non-success status.
   *
   * @return HTTP status code, or zero for local, transport, deadline, or response validation
   *     failures
   */
  public int statusCode() {
    return statusCode;
  }
}
