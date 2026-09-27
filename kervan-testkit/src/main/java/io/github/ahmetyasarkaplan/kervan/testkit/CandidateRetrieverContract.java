package io.github.ahmetyasarkaplan.kervan.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * JUnit contract for a retriever backed by a deterministic fixture. Implement this interface in a
 * test class and provide a query whose expected IDs are the application's canonical IDs, in
 * provider order. The fixture must remain unchanged across two calls.
 *
 * <p>The retriever itself has no name; pipeline registration supplies the stable source name.
 *
 * @param <Q> application query type
 * @param <ID> canonical candidate identifier type
 */
public interface CandidateRetrieverContract<Q, ID> {

  /**
   * Returns the retriever under test.
   *
   * @return retriever implementation
   */
  CandidateRetriever<Q, ID> retriever();

  /**
   * Returns a query for the deterministic fixture.
   *
   * @return fixture query
   */
  Q query();

  /**
   * Returns expected canonical IDs in provider order.
   *
   * @return ordered fixture IDs
   */
  List<ID> expectedCanonicalIds();

  /**
   * Supplies a positive time budget for each fixture invocation.
   *
   * @return provider time budget
   */
  default Duration timeBudget() {
    return Duration.ofSeconds(5);
  }

  /** Verifies canonical IDs, scores, ordering, and the full fixture limit. */
  @Test
  default void returnsOrderedCanonicalCandidatesWithinLimit() {
    List<ID> expected = List.copyOf(expectedCanonicalIds());
    int limit = Math.max(1, expected.size());
    List<SourceCandidate<ID>> candidates =
        assertValid(retriever().retrieve(query(), RetrievalContext.of(limit, timeBudget())), limit);
    assertEquals(expected, candidates.stream().map(SourceCandidate::id).toList());
  }

  /** Verifies the retriever respects a smaller candidate limit. */
  @Test
  default void honorsCandidateLimit() {
    List<ID> expected = List.copyOf(expectedCanonicalIds());
    int limit = Math.max(1, expected.size() / 2);
    List<SourceCandidate<ID>> candidates =
        assertValid(retriever().retrieve(query(), RetrievalContext.of(limit, timeBudget())), limit);
    assertTrue(
        expected.containsAll(candidates.stream().map(SourceCandidate::id).toList()),
        "limited output must use fixture canonical IDs");
  }

  /** Verifies repeated fixture calls produce the same ordered candidates. */
  @Test
  default void returnsStableFixtureOnRepeat() {
    List<ID> expected = List.copyOf(expectedCanonicalIds());
    RetrievalContext context = RetrievalContext.of(Math.max(1, expected.size()), timeBudget());
    CandidateRetriever<Q, ID> retriever = retriever();
    List<SourceCandidate<ID>> first =
        assertValid(retriever.retrieve(query(), context), context.candidateLimit());
    List<SourceCandidate<ID>> second =
        assertValid(retriever.retrieve(query(), context), context.candidateLimit());
    assertEquals(first, second, "fixed fixture output must remain stable across calls");
  }

  private static <ID> List<SourceCandidate<ID>> assertValid(
      List<SourceCandidate<ID>> candidates, int limit) {
    assertNotNull(candidates, "retriever must return a list, including for an empty result");
    assertTrue(candidates.size() <= limit, "retriever exceeded candidateLimit");
    List<SourceCandidate<ID>> snapshot = new ArrayList<>(candidates.size());
    for (SourceCandidate<ID> candidate : candidates) {
      assertNotNull(candidate, "candidate must not be null");
      assertNotNull(candidate.id(), "candidate ID must not be null");
      assertTrue(Double.isFinite(candidate.score()), "candidate score must be finite");
      snapshot.add(candidate);
    }
    return snapshot;
  }
}
