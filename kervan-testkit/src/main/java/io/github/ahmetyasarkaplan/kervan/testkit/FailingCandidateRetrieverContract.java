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
 */
public interface FailingCandidateRetrieverContract<Q, ID> {

  CandidateRetriever<Q, ID> failingRetriever();

  Q failingQuery();

  Class<? extends RuntimeException> expectedFailureType();

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
