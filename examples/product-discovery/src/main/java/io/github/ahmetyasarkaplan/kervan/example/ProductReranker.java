package io.github.ahmetyasarkaplan.kervan.example;

import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.Ranker;
import io.github.ahmetyasarkaplan.kervan.core.BatchScoringRanker;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Transparent title-token matching for the demo, with one batch catalog lookup. */
final class ProductReranker {
  private ProductReranker() {}

  static Ranker<ProductQuery, String> titleMatch() {
    return new BatchScoringRanker<>(
        (query, candidates, context) -> {
          var products =
              DemoCatalog.loadByIds(candidates.stream().map(RankedCandidate::id).toList());
          // Hydration and scoring share context.remainingBudget(). External calls must use the
          // remaining value after hydration, not restart the original timeout.
          Set<String> queryTokens = tokens(query.text());
          Map<String, Double> scores = new LinkedHashMap<>();
          for (var candidate : candidates) {
            Set<String> titleTokens = tokens(products.get(candidate.id()).title());
            long matches = queryTokens.stream().filter(titleTokens::contains).count();
            scores.put(
                candidate.id(),
                queryTokens.isEmpty() ? 0.0 : (double) matches / queryTokens.size());
          }
          return scores;
        });
  }

  private static Set<String> tokens(String text) {
    return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
        .filter(token -> !token.isBlank())
        .collect(Collectors.toSet());
  }
}
