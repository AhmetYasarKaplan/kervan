package io.github.ahmetyasarkaplan.kervan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RankingOutcomeTest {
  @Test
  void compatibilityResultDoesNotInventRankingOutcome() {
    var result =
        new PipelineResult<String>(
            "pipeline",
            List.of(),
            Duration.ZERO,
            List.of(RetrievalResult.empty("source", Duration.ZERO)));
    assertThat(result.rankingOutcome().status()).isEqualTo(RankingStatus.NOT_REPORTED);
    assertThat(result.isPartial()).isFalse();
    assertThat(result.isDegraded()).isFalse();
  }

  @Test
  void degradationIncludesOptionalProvidersIndependentlyOfSuccessfulRanking() {
    var result =
        new PipelineResult<String>(
            "pipeline",
            List.of(),
            Duration.ZERO,
            List.of(
                RetrievalResult.empty("good", Duration.ZERO),
                RetrievalResult.failure("bad", Duration.ZERO)),
            new RankingOutcome(RankingStatus.EMPTY, 0, 0, Duration.ZERO));
    assertThat(result.isPartial()).isTrue();
    assertThat(result.isDegraded()).isTrue();
    assertThat(result.rankingOutcome().fallbackUsed()).isFalse();
  }

  @Test
  void validatesSanitizedCountsAndDurations() {
    assertThatThrownBy(() -> new RankingOutcome(null, 0, 0, Duration.ZERO))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RankingOutcome(RankingStatus.SUCCESS, -1, 1, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RankingOutcome(RankingStatus.SUCCESS, 1, -1, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RankingOutcome(RankingStatus.SUCCESS, 1, 1, Duration.ofNanos(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RankingOutcome(RankingStatus.TIMED_OUT, 1, 1, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RankingOutcome(RankingStatus.NOT_REPORTED, 1, 0, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
