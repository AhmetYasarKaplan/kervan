package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalResult;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stable fusion ordering independent of task completion order or generic ID stringification. */
final class FusionOrdering {

  private FusionOrdering() {}

  static <ID> List<RankedCandidate<ID>> sortAndLimit(
      List<RankedCandidate<ID>> candidates, List<RetrievalResult<ID>> results, int limit) {
    Map<String, Integer> sourceIndexes = new HashMap<>();
    for (RetrievalResult<ID> result : results) {
      if (sourceIndexes.putIfAbsent(result.retrieverName(), sourceIndexes.size()) != null) {
        throw new IllegalArgumentException("retrieval results must have unique source names");
      }
    }

    List<Entry<ID>> entries = new ArrayList<>(candidates.size());
    for (int index = 0; index < candidates.size(); index++) {
      RankedCandidate<ID> candidate = candidates.get(index);
      int[] ranks = new int[sourceIndexes.size()];
      Arrays.fill(ranks, Integer.MAX_VALUE);
      int bestRank = Integer.MAX_VALUE;
      for (SourceContribution contribution : candidate.contributions()) {
        Integer sourceIndex = sourceIndexes.get(contribution.source());
        if (sourceIndex == null) {
          throw new IllegalArgumentException("candidate contribution has an unknown source");
        }
        ranks[sourceIndex] = contribution.rank();
        bestRank = Math.min(bestRank, contribution.rank());
      }
      entries.add(new Entry<>(candidate, bestRank, ranks, index));
    }

    entries.sort(
        (left, right) -> {
          int scoreOrder = Double.compare(right.candidate.score(), left.candidate.score());
          if (scoreOrder != 0) {
            return scoreOrder;
          }
          int bestRankOrder = Integer.compare(left.bestRank, right.bestRank);
          if (bestRankOrder != 0) {
            return bestRankOrder;
          }
          for (int index = 0; index < sourceIndexes.size(); index++) {
            int rankOrder = Integer.compare(left.ranks[index], right.ranks[index]);
            if (rankOrder != 0) {
              return rankOrder;
            }
          }
          return Integer.compare(left.firstSeen, right.firstSeen);
        });

    List<RankedCandidate<ID>> ordered = new ArrayList<>(Math.min(entries.size(), limit));
    for (int index = 0; index < Math.min(entries.size(), limit); index++) {
      ordered.add(entries.get(index).candidate);
    }
    return List.copyOf(ordered);
  }

  private record Entry<ID>(
      RankedCandidate<ID> candidate, int bestRank, int[] ranks, int firstSeen) {}
}
