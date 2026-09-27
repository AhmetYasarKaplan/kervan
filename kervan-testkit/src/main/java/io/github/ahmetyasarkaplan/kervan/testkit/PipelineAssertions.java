package io.github.ahmetyasarkaplan.kervan.testkit;

import io.github.ahmetyasarkaplan.kervan.api.PipelineResult;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalResult;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalStatus;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Focused assertions for ordered pipeline output, provider outcomes, and provenance. */
public final class PipelineAssertions {

  private PipelineAssertions() {}

  /**
   * Asserts final canonical candidate IDs in rank order.
   *
   * @param <ID> canonical candidate identifier type
   * @param result pipeline result
   * @param expected expected IDs in rank order
   */
  public static <ID> void assertCandidateIds(PipelineResult<ID> result, List<ID> expected) {
    Objects.requireNonNull(result, "result must not be null");
    List<ID> actual = result.candidates().stream().map(RankedCandidate::id).toList();
    assertEqual(expected, actual, "candidate IDs");
  }

  /**
   * Asserts provider statuses in registration order.
   *
   * @param result pipeline result
   * @param expected statuses keyed by stable provider name
   */
  public static void assertRetrievalStatuses(
      PipelineResult<?> result, Map<String, RetrievalStatus> expected) {
    Objects.requireNonNull(result, "result must not be null");
    Map<String, RetrievalStatus> actual = new LinkedHashMap<>();
    for (RetrievalResult<?> retrieval : result.retrievalResults()) {
      actual.put(retrieval.retrieverName(), retrieval.status());
    }
    assertEqual(expected, actual, "retrieval statuses");
  }

  /**
   * Asserts the providers contributing to one final candidate.
   *
   * @param <ID> canonical candidate identifier type
   * @param result pipeline result
   * @param candidateId candidate to inspect
   * @param expected expected provider names in contribution order
   */
  public static <ID> void assertContributingSources(
      PipelineResult<ID> result, ID candidateId, List<String> expected) {
    Objects.requireNonNull(result, "result must not be null");
    RankedCandidate<ID> candidate =
        result.candidates().stream()
            .filter(item -> Objects.equals(item.id(), candidateId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("candidate ID is absent: " + candidateId));
    List<String> actual =
        candidate.contributions().stream().map(SourceContribution::source).toList();
    assertEqual(expected, actual, "contributing sources for " + candidateId);
  }

  private static void assertEqual(Object expected, Object actual, String description) {
    Objects.requireNonNull(expected, "expected must not be null");
    if (!expected.equals(actual)) {
      throw new AssertionError(
          description + " expected <" + expected + "> but was <" + actual + ">");
    }
  }
}
