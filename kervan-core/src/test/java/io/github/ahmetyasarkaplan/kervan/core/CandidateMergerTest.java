package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.Candidate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CandidateMergerTest {

  @Test
  void shouldPreserveFirstSeenOrderForUniqueCandidates() {
    Candidate<String> first = Candidate.of("first", 3.0, "lexical");
    Candidate<String> second = Candidate.of("second", 2.0, "vector");
    Candidate<String> third = Candidate.of("third", 1.0, "popularity");

    assertThat(CandidateMerger.merge(List.of(first, second, third)))
        .containsExactly(first, second, third);
  }

  @Test
  void shouldMergeDuplicateIdsAndPreserveProvenance() {
    Candidate<String> lexical = Candidate.of("shared", 8.0, "lexical");
    Candidate<String> vector = Candidate.of("shared", 0.85, "vector");

    List<Candidate<String>> result = CandidateMerger.merge(List.of(lexical, vector));

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().sources()).containsExactlyInAnyOrder("lexical", "vector");
    assertThat(result.getFirst().sourceScores())
        .containsEntry("lexical", 8.0)
        .containsEntry("vector", 0.85);
  }

  @Test
  void shouldKeepFirstSeenScoreAndPositionWhenMerging() {
    Candidate<String> first = Candidate.of("first", 10.0, "lexical");
    Candidate<String> other = Candidate.of("other", 9.0, "lexical");
    Candidate<String> duplicate = Candidate.of("first", 20.0, "vector");

    List<Candidate<String>> result = CandidateMerger.merge(List.of(first, other, duplicate));

    assertThat(result).extracting(Candidate::id).containsExactly("first", "other");
    assertThat(result.getFirst().score()).isEqualTo(10.0);
  }

  @Test
  void shouldMergeAttributesUsingFirstValueForConflictingKeys() {
    Candidate<String> first =
        new Candidate<>(
            "shared",
            8.0,
            Set.of("lexical"),
            Map.of("lexical", 8.0),
            Map.of("category", "books", "language", "en"));
    Candidate<String> duplicate =
        new Candidate<>(
            "shared",
            0.85,
            Set.of("vector"),
            Map.of("vector", 0.85),
            Map.of("category", "media", "inStock", true));

    Candidate<String> merged = CandidateMerger.merge(List.of(first, duplicate)).getFirst();

    assertThat(merged.attributes())
        .containsEntry("category", "books")
        .containsEntry("language", "en")
        .containsEntry("inStock", true);
  }

  @Test
  void shouldAcceptRepeatedIdenticalScoreFromSameSource() {
    Candidate<String> first = Candidate.of("shared", 4.0, "lexical");
    Candidate<String> duplicate = Candidate.of("shared", 4.0, "lexical");

    assertThat(CandidateMerger.merge(List.of(first, duplicate)))
        .singleElement()
        .satisfies(
            candidate -> {
              assertThat(candidate.sources()).containsExactly("lexical");
              assertThat(candidate.sourceScores()).containsExactlyEntriesOf(Map.of("lexical", 4.0));
            });
  }

  @Test
  void shouldRejectConflictingScoresFromSameSourceWithoutExposingCandidateId() {
    Candidate<String> first = Candidate.of("sensitive-id", 4.0, "lexical");
    Candidate<String> duplicate = Candidate.of("sensitive-id", 5.0, "lexical");

    assertThatThrownBy(() -> CandidateMerger.merge(List.of(first, duplicate)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Conflicting scores for source: lexical")
        .hasMessageNotContaining("sensitive-id");
  }

  @Test
  void shouldReturnAnIndependentUnmodifiableSnapshot() {
    Candidate<String> candidate = Candidate.of("candidate", 1.0, "source");
    List<Candidate<String>> input = new ArrayList<>(List.of(candidate));

    List<Candidate<String>> result = CandidateMerger.merge(input);
    input.clear();

    assertThat(result).containsExactly(candidate);
    assertThatThrownBy(() -> result.add(candidate))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldRejectNullInputAndNullElements() {
    assertThatThrownBy(() -> CandidateMerger.merge(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("candidates must not be null");

    List<Candidate<String>> candidates = Arrays.asList((Candidate<String>) null);
    assertThatThrownBy(() -> CandidateMerger.merge(candidates))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("candidates must not contain null elements");
  }
}
