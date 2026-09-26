package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import java.util.Objects;

/** The stable name, implementation and candidate limit of one independent provider. */
public record RetrieverSpec<Q, ID>(
    String name, CandidateRetriever<Q, ID> retriever, int candidateLimit) {

  public RetrieverSpec {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    Objects.requireNonNull(retriever, "retriever must not be null");
    if (candidateLimit <= 0) {
      throw new IllegalArgumentException("candidateLimit must be positive");
    }
  }

  public static <Q, ID> RetrieverSpec<Q, ID> of(
      String name, CandidateRetriever<Q, ID> retriever, int candidateLimit) {
    return new RetrieverSpec<>(name, retriever, candidateLimit);
  }
}
