package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.Candidate;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class IdentityRankerTest {

  @Test
  void shouldPreserveCandidateOrderAndTruncateToLimit() {
    IdentityRanker<String, String> ranker = new IdentityRanker<>();

    Candidate<String> c1 = Candidate.of("p1", 10.0, "src");
    Candidate<String> c2 = Candidate.of("p2", 8.0, "src");
    Candidate<String> c3 = Candidate.of("p3", 6.0, "src");

    List<Candidate<String>> input = List.of(c1, c2, c3);
    RankingContext context = RankingContext.of(2);

    List<Candidate<String>> output = ranker.rank("test-query", input, context);

    assertThat(output).containsExactly(c1, c2);
  }

  @Test
  void shouldReturnAllCandidatesWhenLimitExceedsSize() {
    IdentityRanker<String, String> ranker = new IdentityRanker<>();

    Candidate<String> c1 = Candidate.of("p1", 10.0, "src");
    List<Candidate<String>> input = List.of(c1);
    RankingContext context = RankingContext.of(10);

    List<Candidate<String>> output = ranker.rank("test-query", input, context);

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
    var candidate = Candidate.of("p1", 1.0, "src");
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
    List<Candidate<String>> input = Arrays.asList((Candidate<String>) null);
    assertThatThrownBy(
            () -> new IdentityRanker<String, String>().rank(null, input, RankingContext.of(1)))
        .isInstanceOf(NullPointerException.class);
  }
}
