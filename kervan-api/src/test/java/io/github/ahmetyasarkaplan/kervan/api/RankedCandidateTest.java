package io.github.ahmetyasarkaplan.kervan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RankedCandidateTest {

  @Test
  void shouldPreserveOrderedSourceContributions() {
    var search = new SourceContribution("search", 2, 0.8);
    var recommendation = new SourceContribution("recommendations", 1, 0.3);
    var candidate =
        new RankedCandidate<>("product-42", 0.8, List.of(search, recommendation), Map.of());
    assertThat(candidate.contributions()).containsExactly(search, recommendation);
  }

  @Test
  void shouldDefensivelyCopyCollections() {
    var contributions = new ArrayList<>(List.of(new SourceContribution("search", 1, 1.0)));
    var attributes = new HashMap<String, Object>(Map.of("category", "books"));
    var candidate = new RankedCandidate<>("id", 1.0, contributions, attributes);
    contributions.clear();
    attributes.clear();
    assertThat(candidate.contributions()).hasSize(1);
    assertThat(candidate.attributes()).containsEntry("category", "books");
    assertThatThrownBy(() -> candidate.contributions().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> candidate.attributes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldRejectMissingOrDuplicateContributions() {
    assertThatThrownBy(() -> new RankedCandidate<>("id", 1.0, List.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    var first = new SourceContribution("search", 1, 1.0);
    var duplicate = new SourceContribution("search", 2, 0.5);
    assertThatThrownBy(() -> new RankedCandidate<>("id", 1.0, List.of(first, duplicate), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRejectInvalidScalarFields() {
    var contribution = List.of(new SourceContribution("search", 1, 1.0));
    assertThatThrownBy(() -> new RankedCandidate<>(null, 1.0, contribution, Map.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RankedCandidate<>("id", Double.NaN, contribution, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
