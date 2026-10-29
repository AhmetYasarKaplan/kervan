package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import io.github.ahmetyasarkaplan.kervan.api.RankingPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Greedily keeps candidates in input order while enforcing one maximum count per group.
 *
 * <p>The application supplies the group key. All null keys share one missing-group bucket, which
 * has the same quota as every other group. Group keys use equality and hashing, never ordering.
 *
 * @param <Q> application query type
 * @param <ID> canonical candidate identifier type
 * @param <G> application group key type
 */
public final class GroupQuotaPolicy<Q, ID, G> implements RankingPolicy<Q, ID> {
  private final Function<? super RankedCandidate<ID>, ? extends G> groupBy;
  private final int maxPerGroup;

  /**
   * Creates a group quota policy.
   *
   * @param groupBy application callback returning a group key, or null for the missing-group bucket
   * @param maxPerGroup positive maximum count for each group
   */
  public GroupQuotaPolicy(
      Function<? super RankedCandidate<ID>, ? extends G> groupBy, int maxPerGroup) {
    this.groupBy = Objects.requireNonNull(groupBy, "groupBy must not be null");
    if (maxPerGroup <= 0) {
      throw new IllegalArgumentException("maxPerGroup must be positive");
    }
    this.maxPerGroup = maxPerGroup;
  }

  @Override
  public List<RankedCandidate<ID>> apply(
      Q query, List<RankedCandidate<ID>> candidates, RankingContext context) {
    Objects.requireNonNull(context, "context must not be null");
    var input = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
    var ids = new HashSet<ID>();
    for (RankedCandidate<ID> candidate : input) {
      if (!ids.add(candidate.id())) {
        throw new IllegalArgumentException("candidates must have unique IDs");
      }
    }
    var counts = new HashMap<G, Integer>();
    var selected = new ArrayList<RankedCandidate<ID>>();
    for (RankedCandidate<ID> candidate : input) {
      if (selected.size() == context.candidateLimit()) {
        break;
      }
      G group = groupBy.apply(candidate);
      int count = counts.getOrDefault(group, 0);
      if (count < maxPerGroup) {
        selected.add(candidate);
        counts.put(group, count + 1);
      }
    }
    return List.copyOf(selected);
  }
}
