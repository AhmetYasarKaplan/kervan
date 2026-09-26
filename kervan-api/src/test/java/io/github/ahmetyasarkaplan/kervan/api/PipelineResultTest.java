package io.github.ahmetyasarkaplan.kervan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PipelineResultTest {

  @Test
  void shouldCopyOrderedResultsAndDerivePartialMetadata() {
    var candidate =
        new RankedCandidate<>(
            "a", 1.0, List.of(new SourceContribution("search", 1, 1.0)), Map.of());
    var candidates = new ArrayList<>(List.of(candidate));
    List<RetrievalResult<String>> stages =
        new ArrayList<>(
            List.of(
                RetrievalResult.success(
                    "search",
                    List.of(new SourceHit<>(SourceCandidate.of("a", 1.0), 1)),
                    Duration.ZERO),
                RetrievalResult.timedOut("recommendations", Duration.ofMillis(5))));
    var result = new PipelineResult<>("discovery", candidates, Duration.ofMillis(5), stages);

    candidates.clear();
    stages.clear();

    assertThat(result.candidates()).containsExactly(candidate);
    assertThat(result.retrievalResults()).hasSize(2);
    assertThat(result.isPartial()).isTrue();
    assertThat(result.successfulStages()).containsExactly("search");
    assertThat(result.failedStages()).containsExactly("recommendations");
    assertThat(result.timedOutStages()).containsExactly("recommendations");
    assertThatThrownBy(() -> result.candidates().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> result.retrievalResults().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> result.failedStages().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldCountEmptyRetrievalAsUsableSuccess() {
    var result =
        new PipelineResult<String>(
            "discovery",
            List.of(),
            Duration.ZERO,
            List.of(RetrievalResult.empty("search", Duration.ZERO)));

    assertThat(result.candidates()).isEmpty();
    assertThat(result.isPartial()).isFalse();
    assertThat(result.successfulStages()).containsExactly("search");
  }

  @Test
  void shouldRejectInvalidOutcomes() {
    var empty = RetrievalResult.<String>empty("search", Duration.ZERO);
    var failed = RetrievalResult.<String>failure("search", Duration.ZERO);
    assertThatThrownBy(() -> new PipelineResult<>("discovery", List.of(), Duration.ZERO, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new PipelineResult<>("discovery", List.of(), Duration.ZERO, List.of(failed)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("usable");
    assertThatThrownBy(
            () ->
                new PipelineResult<>("discovery", List.of(), Duration.ZERO, List.of(empty, failed)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique");
    assertThatThrownBy(
            () ->
                new PipelineResult<>(
                    "discovery",
                    List.of(),
                    Duration.ZERO,
                    List.of(empty, RetrievalResult.cancelled("other", Duration.ZERO))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cancelled");
  }

  @Test
  void shouldRejectInvalidFieldsAndNullEntries() {
    var stages = List.of(RetrievalResult.<String>empty("search", Duration.ZERO));
    assertThatThrownBy(() -> new PipelineResult<>(" ", List.of(), Duration.ZERO, stages))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new PipelineResult<>("discovery", List.of(), Duration.ofNanos(-1), stages))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PipelineResult<>(null, List.of(), Duration.ZERO, stages))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PipelineResult<>("discovery", null, Duration.ZERO, stages))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PipelineResult<>("discovery", List.of(), null, stages))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PipelineResult<>("discovery", List.of(), Duration.ZERO, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new PipelineResult<>(
                    "discovery", List.of(), Duration.ZERO, Arrays.asList(emptyStage(), null)))
        .isInstanceOf(NullPointerException.class);
  }

  private static RetrievalResult<String> emptyStage() {
    return RetrievalResult.empty("search", Duration.ZERO);
  }
}
