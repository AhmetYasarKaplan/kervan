package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.RetrievalResult;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import io.github.ahmetyasarkaplan.kervan.api.SourceHit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CandidateMergerTest {

  @Test
  void shouldKeepConfiguredProviderAndSourceOrder() {
    var search = result("search", hit("a", 8.0, 1), hit("shared", 7.0, 2));
    var recommendations = result("recommendations", hit("shared", 0.8, 1), hit("c", 0.7, 2));

    var merged = CandidateMerger.merge(List.of(search, recommendations));

    assertThat(merged).extracting(candidate -> candidate.id()).containsExactly("a", "shared", "c");
    assertThat(merged.get(1).score()).isEqualTo(7.0);
    assertThat(merged.get(1).contributions())
        .containsExactly(
            new SourceContribution("search", 2, 7.0),
            new SourceContribution("recommendations", 1, 0.8));
  }

  @Test
  void shouldKeepFirstAttributesAndAddNewKeys() {
    var first = new SourceHit<>(new SourceCandidate<>("same", 2.0, Map.of("category", "books")), 1);
    var second =
        new SourceHit<>(
            new SourceCandidate<>("same", 0.2, Map.of("category", "media", "inStock", true)), 3);

    var merged =
        CandidateMerger.merge(List.of(result("search", first), result("recommendations", second)))
            .getFirst();

    assertThat(merged.attributes())
        .containsEntry("category", "books")
        .containsEntry("inStock", true);
  }

  @Test
  void shouldIgnoreLaterOccurrenceFromTheSameProvider() {
    var search = result("search", hit("same", 3.0, 1), hit("same", 9.0, 2));
    var merged = CandidateMerger.merge(List.of(search));

    assertThat(merged).hasSize(1);
    assertThat(merged.getFirst().score()).isEqualTo(3.0);
    assertThat(merged.getFirst().contributions())
        .containsExactly(new SourceContribution("search", 1, 3.0));
  }

  @Test
  void shouldReturnAnIndependentUnmodifiableSnapshot() {
    var results = new ArrayList<>(List.of(result("search", hit("a", 1.0, 1))));
    var merged = CandidateMerger.merge(results);
    results.clear();

    assertThat(merged).hasSize(1);
    assertThatThrownBy(merged::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldAcceptEmptyResultsAndRejectNulls() {
    assertThat(CandidateMerger.merge(List.of())).isEmpty();
    assertThatThrownBy(() -> CandidateMerger.merge(null)).isInstanceOf(NullPointerException.class);
    List<RetrievalResult<String>> results = Arrays.asList((RetrievalResult<String>) null);
    assertThatThrownBy(() -> CandidateMerger.merge(results))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> CandidateMerger.merge(List.of(result("same"), result("same"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique source names");
  }

  private static SourceHit<String> hit(String id, double score, int rank) {
    return new SourceHit<>(SourceCandidate.of(id, score), rank);
  }

  @SafeVarargs
  private static RetrievalResult<String> result(String source, SourceHit<String>... hits) {
    var candidates = new ArrayList<SourceHit<String>>();
    for (SourceHit<String> hit : hits) {
      candidates.add(hit);
    }
    return RetrievalResult.success(source, candidates, Duration.ZERO);
  }
}
