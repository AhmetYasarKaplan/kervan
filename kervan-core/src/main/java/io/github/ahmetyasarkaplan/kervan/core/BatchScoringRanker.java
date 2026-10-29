package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.BatchScorer;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.Ranker;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import io.github.ahmetyasarkaplan.kervan.api.RerankingScore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Scores a complete fused window once using an application-owned batch scorer.
 *
 * <p>All input IDs must be unique and receive exactly one finite score. Results are ordered by
 * descending model score, with ties retaining fusion order. The output limit is applied only after
 * scoring. Original fusion scores and source contributions are preserved in each result.
 *
 * <p>This adapter creates no executors and supplies no timeout or fallback policy. Scorer failures
 * and invalid responses throw a sanitized {@link IllegalStateException} without the original cause;
 * interruptions restore the current thread's interrupt status. A surrounding pipeline can apply its
 * own execution budget and failure policy.
 *
 * @param <Q> query input type
 * @param <ID> canonical candidate identifier type
 */
public final class BatchScoringRanker<Q, ID> implements Ranker<Q, ID> {
  private final BatchScorer<Q, ID> scorer;

  /**
   * Creates a ranker using the supplied batch scoring callback.
   *
   * @param scorer application-owned hydration and scoring callback
   */
  public BatchScoringRanker(BatchScorer<Q, ID> scorer) {
    this.scorer = Objects.requireNonNull(scorer, "scorer must not be null");
  }

  @Override
  public List<RankedCandidate<ID>> rank(
      Q query, List<RankedCandidate<ID>> candidates, RankingContext context) {
    Objects.requireNonNull(context, "context must not be null");
    var input = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
    var ids = new HashSet<ID>();
    for (RankedCandidate<ID> candidate : input) {
      if (!ids.add(candidate.id())) {
        throw new IllegalArgumentException("candidates must have unique IDs");
      }
    }
    if (input.isEmpty()) {
      return List.of();
    }

    try {
      Map<ID, Double> scores = Map.copyOf(scorer.score(query, input, context));
      if (!scores.keySet().equals(ids)) {
        throw new IllegalStateException();
      }
      var ranked = new ArrayList<RankedCandidate<ID>>(input.size());
      for (RankedCandidate<ID> candidate : input) {
        double score = scores.get(candidate.id());
        if (!Double.isFinite(score)) {
          throw new IllegalStateException();
        }
        double fusionScore =
            candidate.rerankingScore().map(RerankingScore::fusionScore).orElse(candidate.score());
        ranked.add(
            new RankedCandidate<>(
                candidate.id(),
                score,
                candidate.contributions(),
                candidate.attributes(),
                Optional.of(new RerankingScore(fusionScore, score))));
      }
      ranked.sort(
          (first, second) ->
              first.score() == second.score() ? 0 : Double.compare(second.score(), first.score()));
      return List.copyOf(ranked.subList(0, Math.min(ranked.size(), context.candidateLimit())));
    } catch (Exception failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException("batch scoring failed or returned invalid scores");
    }
  }
}
