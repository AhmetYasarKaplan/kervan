package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.FusionContext;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalResult;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.api.SourceHit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class FirstSeenFusionTest {

  @Test
  void shouldApplyOutputLimitAfterMergingEqualIds() {
    var search =
        RetrievalResult.success(
            "search",
            List.of(
                new SourceHit<>(SourceCandidate.of("a", 3.0), 1),
                new SourceHit<>(SourceCandidate.of("shared", 2.0), 2)),
            Duration.ZERO);
    var recommendations =
        RetrievalResult.success(
            "recommendations",
            List.of(
                new SourceHit<>(SourceCandidate.of("shared", 0.8), 1),
                new SourceHit<>(SourceCandidate.of("c", 0.7), 2)),
            Duration.ZERO);

    var fused =
        new FirstSeenFusion<String>().fuse(List.of(search, recommendations), FusionContext.of(2));

    assertThat(fused).extracting(candidate -> candidate.id()).containsExactly("a", "shared");
    assertThat(fused.get(1).contributions()).hasSize(2);
    assertThatThrownBy(fused::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldAcceptEmptyProviderResults() {
    var result = RetrievalResult.<String>empty("search", Duration.ZERO);
    assertThat(new FirstSeenFusion<String>().fuse(List.of(result), FusionContext.of(1))).isEmpty();
  }
}
