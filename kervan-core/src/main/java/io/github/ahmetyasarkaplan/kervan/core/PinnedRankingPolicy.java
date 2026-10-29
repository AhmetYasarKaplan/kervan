package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import io.github.ahmetyasarkaplan.kervan.api.RankingPolicy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Moves existing pinned IDs to the front in requested order, then keeps remaining input order.
 * Missing pins are skipped. No candidate is injected or changed.
 *
 * @param <Q> application query type
 * @param <ID> canonical candidate identifier type
 */
public final class PinnedRankingPolicy<Q, ID> implements RankingPolicy<Q, ID> {
  private final Function<? super Q, ? extends List<ID>> pins;

  /**
   * Creates a policy with an immutable snapshot of fixed pins.
   *
   * @param pinnedIds ordered, unique, non-null pin IDs
   */
  public PinnedRankingPolicy(List<ID> pinnedIds) {
    var snapshot = validatedPins(pinnedIds);
    pins = query -> snapshot;
  }

  /**
   * Creates a policy whose pins depend on the query.
   *
   * @param pins callback returning ordered, unique, non-null pin IDs
   */
  public PinnedRankingPolicy(Function<? super Q, ? extends List<ID>> pins) {
    this.pins = Objects.requireNonNull(pins, "pins must not be null");
  }

  @Override
  public List<RankedCandidate<ID>> apply(
      Q query, List<RankedCandidate<ID>> candidates, RankingContext context) {
    Objects.requireNonNull(context, "context must not be null");
    var input = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
    var requestedPins = validatedPins(pins.apply(query));
    var remaining = new LinkedHashMap<ID, RankedCandidate<ID>>();
    for (RankedCandidate<ID> candidate : input) {
      if (remaining.putIfAbsent(candidate.id(), candidate) != null) {
        throw new IllegalArgumentException("candidates must have unique IDs");
      }
    }
    var ordered = new ArrayList<RankedCandidate<ID>>(input.size());
    for (ID id : requestedPins) {
      var candidate = remaining.remove(id);
      if (candidate != null) {
        ordered.add(candidate);
      }
    }
    ordered.addAll(remaining.values());
    return List.copyOf(ordered.subList(0, Math.min(ordered.size(), context.candidateLimit())));
  }

  private static <ID> List<ID> validatedPins(List<ID> ids) {
    var snapshot = List.copyOf(Objects.requireNonNull(ids, "pin IDs must not be null"));
    if (new HashSet<>(snapshot).size() != snapshot.size()) {
      throw new IllegalArgumentException("pin IDs must be unique");
    }
    return snapshot;
  }
}
