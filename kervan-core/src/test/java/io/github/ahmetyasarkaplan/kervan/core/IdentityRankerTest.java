package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IdentityRankerTest {

  @Test
  void shouldPreserveCandidateOrderAndTruncateToLimit() {
    IdentityRanker<String, String> ranker = new IdentityRanker<>();

    RankedCandidate<String> c1 = ranked("p1", 10.0);
    RankedCandidate<String> c2 = ranked("p2", 8.0);
    RankedCandidate<String> c3 = ranked("p3", 6.0);

    List<RankedCandidate<String>> input = List.of(c1, c2, c3);
    RankingContext context = RankingContext.of(2);

    List<RankedCandidate<String>> output = ranker.rank("test-query", input, context);

    assertThat(output).containsExactly(c1, c2);
  }

  @Test
  void shouldReturnAllCandidatesWhenLimitExceedsSize() {
    IdentityRanker<String, String> ranker = new IdentityRanker<>();

    RankedCandidate<String> c1 = ranked("p1", 10.0);
    List<RankedCandidate<String>> input = List.of(c1);
    RankingContext context = RankingContext.of(10);

    List<RankedCandidate<String>> output = ranker.rank("test-query", input, context);

    assertThat(output).containsExactly(c1);
  }

  @Test
  void shouldRejectNullArguments() {
    IdentityRanker<String, String> ranker = new IdentityRanker<>();

    assertThatThrownBy(() -> ranker.rank("query", null, RankingContext.of(1)))
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(() -> ranker.rank("query", List.of(), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void shouldReturnAnIndependentUnmodifiableSnapshot() {
    var candidate = ranked("p1", 1.0);
    var input = new ArrayList<>(List.of(candidate));
    var output = new IdentityRanker<String, String>().rank(null, input, RankingContext.of(1));

    input.clear();

    assertThat(output).containsExactly(candidate);
    assertThatThrownBy(() -> output.add(candidate))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldAcceptEmptyInput() {
    assertThat(new IdentityRanker<String, String>().rank(null, List.of(), RankingContext.of(1)))
        .isEmpty();
  }

  @Test
  void shouldRejectNullSelectedCandidates() {
    List<RankedCandidate<String>> input = Arrays.asList((RankedCandidate<String>) null);
    assertThatThrownBy(
            () -> new IdentityRanker<String, String>().rank(null, input, RankingContext.of(1)))
        .isInstanceOf(NullPointerException.class);
  }

  private static RankedCandidate<String> ranked(String id, double score) {
    return new RankedCandidate<>(
        id, score, List.of(new SourceContribution("src", 1, score)), Map.of());
  }
}
