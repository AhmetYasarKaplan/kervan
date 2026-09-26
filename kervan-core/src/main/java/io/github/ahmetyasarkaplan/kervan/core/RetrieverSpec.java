package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import java.time.Duration;
import java.util.Objects;

/** The stable identity, implementation and execution limits of one independent provider. */
public record RetrieverSpec<Q, ID>(
    String name,
    CandidateRetriever<Q, ID> retriever,
    int candidateLimit,
    Duration timeout,
    boolean required) {

  public RetrieverSpec {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    Objects.requireNonNull(retriever, "retriever must not be null");
    if (candidateLimit <= 0) {
      throw new IllegalArgumentException("candidateLimit must be positive");
    }
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    try {
      timeout.toNanos();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("timeout is too large", e);
    }
  }

  public static <Q, ID> RetrieverSpec<Q, ID> required(
      String name, CandidateRetriever<Q, ID> retriever, int candidateLimit, Duration timeout) {
    return new RetrieverSpec<>(name, retriever, candidateLimit, timeout, true);
  }

  public static <Q, ID> RetrieverSpec<Q, ID> optional(
      String name, CandidateRetriever<Q, ID> retriever, int candidateLimit, Duration timeout) {
    return new RetrieverSpec<>(name, retriever, candidateLimit, timeout, false);
  }
}
