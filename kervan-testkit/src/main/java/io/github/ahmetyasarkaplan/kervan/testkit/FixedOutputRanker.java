package io.github.ahmetyasarkaplan.kervan.testkit;

import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.Ranker;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import java.util.List;
import java.util.Objects;

/** Isolates final pipeline behavior with a fixed, ordered ranker result. */
public final class FixedOutputRanker<Q, ID> implements Ranker<Q, ID> {

  private final List<RankedCandidate<ID>> candidates;

  public FixedOutputRanker(List<RankedCandidate<ID>> candidates) {
    this.candidates =
        List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
  }

  @Override
  public List<RankedCandidate<ID>> rank(
      Q query, List<RankedCandidate<ID>> input, RankingContext context) {
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(context, "context must not be null");
    return candidates.subList(0, Math.min(candidates.size(), context.candidateLimit()));
  }
}
