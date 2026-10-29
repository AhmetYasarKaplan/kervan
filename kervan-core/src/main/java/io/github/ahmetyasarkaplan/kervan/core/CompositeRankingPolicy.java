package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import io.github.ahmetyasarkaplan.kervan.api.RankingPolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies policies sequentially and limits the final output once, after every policy.
 *
 * <p>Each policy receives an immutable window and a limit large enough to retain the original input
 * window, along with the caller's current remaining budget. Every intermediate result is validated
 * as a unique, unchanged subset of the immediately preceding window. Removed candidates cannot be
 * reintroduced by later policies. An empty policy list preserves input order.
 *
 * @param <Q> application query type
 * @param <ID> canonical candidate identifier type
 */
public final class CompositeRankingPolicy<Q, ID> implements RankingPolicy<Q, ID> {
  private final List<RankingPolicy<Q, ID>> policies;

  /**
   * Creates a policy chain with an immutable snapshot of its policies.
   *
   * @param policies policies in execution order; neither the list nor its elements may be null
   */
  public CompositeRankingPolicy(List<? extends RankingPolicy<Q, ID>> policies) {
    this.policies = List.copyOf(Objects.requireNonNull(policies, "policies must not be null"));
  }

  @Override
  public List<RankedCandidate<ID>> apply(
      Q query, List<RankedCandidate<ID>> candidates, RankingContext context) {
    Objects.requireNonNull(context, "context must not be null");
    var current = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
    var eligible = index(current);
    int windowLimit = Math.max(current.size(), 1);
    for (RankingPolicy<Q, ID> policy : policies) {
      var intermediateContext = RankingContext.of(windowLimit, context.remainingBudget());
      var output =
          List.copyOf(
              Objects.requireNonNull(
                  policy.apply(query, current, intermediateContext),
                  "policy output must not be null"));
      var next = index(output);
      for (RankedCandidate<ID> candidate : output) {
        if (!candidate.equals(eligible.get(candidate.id()))) {
          throw new IllegalArgumentException(
              "policy output must be an unchanged subset of its input");
        }
      }
      current = output;
      eligible = next;
    }
    return List.copyOf(current.subList(0, Math.min(current.size(), context.candidateLimit())));
  }

  private static <ID> Map<ID, RankedCandidate<ID>> index(List<RankedCandidate<ID>> candidates) {
    var indexed = new HashMap<ID, RankedCandidate<ID>>();
    for (RankedCandidate<ID> candidate : candidates) {
      if (indexed.putIfAbsent(candidate.id(), candidate) != null) {
        throw new IllegalArgumentException("candidates must have unique IDs");
      }
    }
    return indexed;
  }
}
