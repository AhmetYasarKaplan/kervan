package io.github.ahmetyasarkaplan.kervan.testkit;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import java.time.Duration;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * JUnit contract for a provider failure fixture. The failure must be observable as a defined
 * runtime exception so the core can classify it without exposing provider details in a result.
 *
 * @param <Q> application query type
 * @param <ID> canonical candidate identifier type
 */
public interface FailingCandidateRetrieverContract<Q, ID> {

  /**
   * Returns the failing retriever under test.
   *
   * @return retriever implementation
   */
  CandidateRetriever<Q, ID> failingRetriever();

  /**
   * Returns a query that triggers the defined failure.
   *
   * @return failing fixture query
   */
  Q failingQuery();

  /**
   * Returns the expected sanitized runtime failure type.
   *
   * @return expected exception class
   */
  Class<? extends RuntimeException> expectedFailureType();

  /** Verifies the retriever raises its defined failure type. */
  @Test
  default void propagatesDefinedFailure() {
    CandidateRetriever<Q, ID> retriever =
        Objects.requireNonNull(failingRetriever(), "failingRetriever must not be null");
    Class<? extends RuntimeException> failureType =
        Objects.requireNonNull(expectedFailureType(), "expectedFailureType must not be null");
    assertThrows(
        failureType,
        () -> retriever.retrieve(failingQuery(), RetrievalContext.of(3, Duration.ofSeconds(5))));
  }
}
