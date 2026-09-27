package io.github.ahmetyasarkaplan.kervan.testkit;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import java.util.List;
import java.util.Objects;

/**
 * A fixed, ordered provider response for pipeline and adapter tests.
 *
 * @param <Q> application query type
 * @param <ID> canonical candidate identifier type
 */
public final class StubRetriever<Q, ID> implements CandidateRetriever<Q, ID> {

  private final List<SourceCandidate<ID>> candidates;

  /**
   * Copies a fixed list of provider candidates for repeated tests.
   *
   * @param candidates ordered candidates to return
   */
  public StubRetriever(List<SourceCandidate<ID>> candidates) {
    this.candidates =
        List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
  }

  @Override
  public List<SourceCandidate<ID>> retrieve(Q query, RetrievalContext context) {
    Objects.requireNonNull(context, "context must not be null");
    return candidates.subList(0, Math.min(candidates.size(), context.candidateLimit()));
  }
}
