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

  @Test
  void shouldCreateSuccessfulResult() {
    Candidate<String> candidate = Candidate.of("doc-1", 10.0, "lexical");
    RetrievalResult<String> result =
        RetrievalResult.success("lexical", List.of(candidate), Duration.ofMillis(25));

    assertThat(result.retrieverName()).isEqualTo("lexical");
    assertThat(result.candidates()).containsExactly(candidate);
    assertThat(result.duration()).isEqualTo(Duration.ofMillis(25));
    assertThat(result.status()).isEqualTo(RetrievalStatus.SUCCESS);
  }

  @Test
  void shouldMarkEmptyListAsEmptyStatus() {
    RetrievalResult<String> result =
        RetrievalResult.success("lexical", List.of(), Duration.ofMillis(10));

    assertThat(result.status()).isEqualTo(RetrievalStatus.EMPTY);
    assertThat(result.candidates()).isEmpty();
  }

  @Test
  void shouldCreateEmptyResultUsingFactory() {
    RetrievalResult<String> result = RetrievalResult.empty("vector", Duration.ofMillis(15));

    assertThat(result.status()).isEqualTo(RetrievalStatus.EMPTY);
    assertThat(result.candidates()).isEmpty();
    assertThat(result.duration()).isEqualTo(Duration.ofMillis(15));
  }

  @Test
  void shouldCreateFailedResult() {
    RetrievalResult<String> result = RetrievalResult.failure("vector", Duration.ofMillis(50));

    assertThat(result.status()).isEqualTo(RetrievalStatus.FAILED);
    assertThat(result.candidates()).isEmpty();
  }

  @Test
  void shouldCreateTimedOutResult() {
    RetrievalResult<String> result = RetrievalResult.timedOut("lexical", Duration.ofMillis(100));

    assertThat(result.status()).isEqualTo(RetrievalStatus.TIMED_OUT);
    assertThat(result.candidates()).isEmpty();
  }

  @Test
  void shouldRejectNullOrBlankRetrieverName() {
    assertThatThrownBy(
            () -> new RetrievalResult<>(null, List.of(), Duration.ZERO, RetrievalStatus.EMPTY))
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(
            () -> new RetrievalResult<>("  ", List.of(), Duration.ZERO, RetrievalStatus.EMPTY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retrieverName must not be blank");
  }

  @Test
  void shouldEnforceImmutabilityOnCandidates() {
    List<Candidate<String>> mutableList = new ArrayList<>();
    mutableList.add(Candidate.of("c1", 1.0, "src"));

    RetrievalResult<String> result =
        RetrievalResult.success("src", mutableList, Duration.ofMillis(5));

    mutableList.add(Candidate.of("c2", 2.0, "src"));

    assertThat(result.candidates()).hasSize(1);
    assertThatThrownBy(() -> result.candidates().add(Candidate.of("c3", 3.0, "src")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldRejectSuccessfulStatusWithoutCandidatesInDirectConstructor() {
    assertThatThrownBy(
            () -> new RetrievalResult<>("src", List.of(), Duration.ZERO, RetrievalStatus.SUCCESS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SUCCESS requires at least one candidate");
  }

  @ParameterizedTest
  @EnumSource(
      value = RetrievalStatus.class,
      names = {"EMPTY", "FAILED", "TIMED_OUT"})
  void shouldRejectCandidatesForNonSuccessfulStatus(RetrievalStatus status) {
    assertThatThrownBy(
            () ->
                new RetrievalResult<>(
                    "src", List.of(Candidate.of("doc", 1.0, "src")), Duration.ZERO, status))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Only SUCCESS may contain candidates");
  }

  @ParameterizedTest
  @EnumSource(RetrievalStatus.class)
  void shouldRejectNegativeDurationForEveryStatus(RetrievalStatus status) {
    List<Candidate<String>> candidates =
        status == RetrievalStatus.SUCCESS ? List.of(Candidate.of("doc", 1.0, "src")) : List.of();
    assertThatThrownBy(() -> new RetrievalResult<>("src", candidates, Duration.ofNanos(-1), status))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duration must not be negative");
  }

  @Test
  void shouldRejectNullFieldsAndCandidates() {
    assertThatThrownBy(
            () -> new RetrievalResult<>("src", null, Duration.ZERO, RetrievalStatus.EMPTY))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RetrievalResult<>("src", List.of(), null, RetrievalStatus.EMPTY))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RetrievalResult<>("src", List.of(), Duration.ZERO, null))
        .isInstanceOf(NullPointerException.class);
    List<Candidate<String>> candidates = Arrays.asList((Candidate<String>) null);
    assertThatThrownBy(
            () -> new RetrievalResult<>("src", candidates, Duration.ZERO, RetrievalStatus.SUCCESS))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RetrievalResult.success("src", null, Duration.ZERO))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void shouldCopyCandidatesInDirectConstructor() {
    var first = Candidate.of("first", 1.0, "src");
    var second = Candidate.of("second", 2.0, "src");
    var input = new ArrayList<>(List.of(first, second));
    var result = new RetrievalResult<>("src", input, Duration.ZERO, RetrievalStatus.SUCCESS);
    input.clear();
    assertThat(result.candidates()).containsExactly(first, second);
    assertThatThrownBy(() -> result.candidates().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
