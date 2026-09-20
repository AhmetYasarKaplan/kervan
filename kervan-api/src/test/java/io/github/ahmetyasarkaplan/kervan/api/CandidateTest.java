package io.github.ahmetyasarkaplan.kervan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CandidateTest {

  @Test
  void shouldCreateValidCandidateWithFullConstructor() {
    Candidate<String> candidate =
        new Candidate<>(
            "doc-101",
            12.5,
            Set.of("lexical", "vector"),
            Map.of("lexical", 10.0, "vector", 2.5),
            Map.of("category", "books"));

    assertThat(candidate.id()).isEqualTo("doc-101");
    assertThat(candidate.score()).isEqualTo(12.5);
    assertThat(candidate.sources()).containsExactlyInAnyOrder("lexical", "vector");
    assertThat(candidate.sourceScores())
        .containsEntry("lexical", 10.0)
        .containsEntry("vector", 2.5);
    assertThat(candidate.attributes()).containsEntry("category", "books");
  }

  @Test
  void shouldCreateCandidateUsingFactoryMethod() {
    Candidate<String> candidate = Candidate.of("doc-202", 0.95, "vector");

    assertThat(candidate.id()).isEqualTo("doc-202");
    assertThat(candidate.score()).isEqualTo(0.95);
    assertThat(candidate.sources()).containsExactly("vector");
    assertThat(candidate.sourceScores()).containsEntry("vector", 0.95);
    assertThat(candidate.attributes()).isEmpty();
  }

  @Test
  void shouldRejectNullId() {
    assertThatThrownBy(
            () ->
                new Candidate<String>(
                    null, 1.0, Set.of("lexical"), Map.of("lexical", 1.0), Map.of()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("id must not be null");
  }

  @Test
  void shouldRejectNonFiniteScores() {
    assertThatThrownBy(
            () ->
                new Candidate<>(
                    "doc-1", Double.NaN, Set.of("lexical"), Map.of("lexical", 1.0), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("score must be a finite number");

    assertThatThrownBy(
            () ->
                new Candidate<>(
                    "doc-1",
                    Double.POSITIVE_INFINITY,
                    Set.of("lexical"),
                    Map.of("lexical", 1.0),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("score must be a finite number");

    assertThatThrownBy(
            () ->
                new Candidate<>(
                    "doc-1",
                    Double.NEGATIVE_INFINITY,
                    Set.of("lexical"),
                    Map.of("lexical", 1.0),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("score must be a finite number");
  }

  @Test
  void shouldRejectNullCollectionsInConstructor() {
    assertThatThrownBy(
            () -> new Candidate<String>("doc-1", 1.0, null, Map.of("lexical", 1.0), Map.of()))
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(() -> new Candidate<String>("doc-1", 1.0, Set.of("lexical"), null, Map.of()))
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(
            () ->
                new Candidate<String>(
                    "doc-1", 1.0, Set.of("lexical"), Map.of("lexical", 1.0), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void shouldRejectNullSourceInFactoryMethod() {
    assertThatThrownBy(() -> Candidate.of("doc-1", 1.0, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("source must not be null");
  }

  @Test
  void shouldEnforceImmutabilityAndDefensiveCopying() {
    Set<String> mutableSources = new HashSet<>();
    mutableSources.add("lexical");

    Map<String, Double> mutableScores = new HashMap<>();
    mutableScores.put("lexical", 5.0);

    Map<String, Object> mutableAttributes = new HashMap<>();
    mutableAttributes.put("inStock", true);

    Candidate<String> candidate =
        new Candidate<>("doc-1", 5.0, mutableSources, mutableScores, mutableAttributes);

    mutableSources.add("vector");
    mutableScores.put("vector", 10.0);
    mutableAttributes.put("inStock", false);

    assertThat(candidate.sources()).containsExactly("lexical");
    assertThat(candidate.sourceScores()).hasSize(1).containsEntry("lexical", 5.0);
    assertThat(candidate.attributes()).containsEntry("inStock", true);

    assertThatThrownBy(() -> candidate.sources().add("new_source"))
        .isInstanceOf(UnsupportedOperationException.class);

    assertThatThrownBy(() -> candidate.sourceScores().put("new_source", 1.0))
        .isInstanceOf(UnsupportedOperationException.class);

    assertThatThrownBy(() -> candidate.attributes().put("new_attr", "val"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldSupportEqualityAndHashCode() {
    Candidate<String> c1 = Candidate.of("doc-1", 5.0, "lexical");
    Candidate<String> c2 = Candidate.of("doc-1", 5.0, "lexical");
    Candidate<String> c3 = Candidate.of("doc-2", 5.0, "lexical");

    assertThat(c1).isEqualTo(c2);
    assertThat(c1.hashCode()).isEqualTo(c2.hashCode());
    assertThat(c1).isNotEqualTo(c3);
  }

  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
  void shouldRejectNonFiniteSourceScoresEvenWhenCurrentScoreIsFinite(double sourceScore) {
    assertThatThrownBy(
            () ->
                new Candidate<>(
                    "doc",
                    1.0,
                    Set.of("lexical", "vector"),
                    Map.of("lexical", 2.0, "vector", sourceScore),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourceScores must contain only finite numbers");
  }

  @ParameterizedTest
  @ValueSource(doubles = {-Double.MAX_VALUE, -2.5, -0.0, 0.0, Double.MAX_VALUE})
  void shouldPreserveFiniteCurrentAndSourceScores(double score) {
    var candidate = Candidate.of("doc", score, "lexical");
    assertThat(candidate.score()).isEqualTo(score);
    assertThat(candidate.sourceScores()).containsEntry("lexical", score);
  }

  @Test
  void shouldRejectNullSourceScoreValues() {
    Map<String, Double> scores = new HashMap<>();
    scores.put("lexical", null);
    assertThatThrownBy(() -> new Candidate<>("doc", 1.0, Set.of("lexical"), scores, Map.of()))
        .isInstanceOf(NullPointerException.class);
  }
}
