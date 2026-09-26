package io.github.ahmetyasarkaplan.kervan.api;

/**
 * Execution context provided to a retriever during a query execution.
 *
 * @param candidateLimit the maximum number of candidates the retriever is allowed to return.
 */
public record RetrievalContext(int candidateLimit) {

  public RetrievalContext {
    if (candidateLimit <= 0) {
      throw new IllegalArgumentException("candidateLimit must be positive: " + candidateLimit);
    }
  }

  /** Convenience factory method for creating a retrieval context. */
  public static RetrievalContext of(int candidateLimit) {
    return new RetrievalContext(candidateLimit);
  }
}
