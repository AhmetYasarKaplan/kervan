package io.github.ahmetyasarkaplan.kervan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RetrievalResultTest {

  private static SourceHit<String> hit(String id, int rank) {
    return new SourceHit<>(SourceCandidate.of(id, 1.0), rank);
  }

  @Test
  void shouldPreserveCoreAssignedRanksAndDuration() {
    var sourceHit = hit("product-42", 3);
    var result = RetrievalResult.success("search", List.of(sourceHit), Duration.ofMillis(25));
    assertThat(result.retrieverName()).isEqualTo("search");
    assertThat(result.candidates()).containsExactly(sourceHit);
    assertThat(result.status()).isEqualTo(RetrievalStatus.SUCCESS);
    assertThat(result.duration()).isEqualTo(Duration.ofMillis(25));
  }

  @Test
  void shouldDistinguishEmptyFailureAndTimeout() {
    assertThat(RetrievalResult.success("search", List.of(), Duration.ZERO).status())
        .isEqualTo(RetrievalStatus.EMPTY);
    assertThat(RetrievalResult.empty("search", Duration.ZERO).status())
        .isEqualTo(RetrievalStatus.EMPTY);
    assertThat(RetrievalResult.failure("search", Duration.ZERO).status())
        .isEqualTo(RetrievalStatus.FAILED);
    assertThat(RetrievalResult.timedOut("search", Duration.ZERO).status())
        .isEqualTo(RetrievalStatus.TIMED_OUT);
  }

  @Test
  void shouldCopyCandidateList() {
    var input = new ArrayList<>(List.of(hit("first", 1)));
    var result = RetrievalResult.success("search", input, Duration.ZERO);
    input.clear();
    assertThat(result.candidates()).hasSize(1);
    assertThatThrownBy(() -> result.candidates().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldRejectInvalidNameOrDuration() {
    assertThatThrownBy(() -> RetrievalResult.empty(" ", Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RetrievalResult.empty(null, Duration.ZERO))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RetrievalResult.empty("search", Duration.ofNanos(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRejectContradictoryStatusAndCandidates() {
    assertThatThrownBy(
            () ->
                new RetrievalResult<>("search", List.of(), Duration.ZERO, RetrievalStatus.SUCCESS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RetrievalResult<>(
                    "search", List.of(hit("id", 1)), Duration.ZERO, RetrievalStatus.EMPTY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @EnumSource(
      value = RetrievalStatus.class,
      names = {"EMPTY", "FAILED", "TIMED_OUT"})
  void shouldRejectHitsForNonSuccessStatus(RetrievalStatus status) {
    assertThatThrownBy(
            () -> new RetrievalResult<>("search", List.of(hit("id", 1)), Duration.ZERO, status))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRejectNullFieldsAndListElements() {
    assertThatThrownBy(() -> RetrievalResult.success("search", null, Duration.ZERO))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RetrievalResult.empty("search", null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RetrievalResult<>("search", List.of(), Duration.ZERO, null))
        .isInstanceOf(NullPointerException.class);
    List<SourceHit<String>> list = Arrays.asList((SourceHit<String>) null);
    assertThatThrownBy(() -> RetrievalResult.success("search", list, Duration.ZERO))
        .isInstanceOf(NullPointerException.class);
  }
}
