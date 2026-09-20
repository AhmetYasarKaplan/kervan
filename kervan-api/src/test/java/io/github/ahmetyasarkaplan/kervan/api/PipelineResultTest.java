package io.github.ahmetyasarkaplan.kervan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PipelineResultTest {
  @Test
  void shouldCopyCollectionsAndPreserveCandidateOrder() {
    var first = Candidate.of("first", 1.0, "lexical");
    var second = Candidate.of("second", 2.0, "lexical");
    var candidates = new ArrayList<>(List.of(first, second));
    var successes = new HashSet<>(Set.of("lexical"));
    var failures = new HashSet<>(Set.of("vector"));
    var result =
        new PipelineResult<>("search", candidates, true, Duration.ofMillis(3), successes, failures);

    candidates.clear();
    successes.clear();
    failures.clear();

    assertThat(result.pipelineName()).isEqualTo("search");
    assertThat(result.duration()).isEqualTo(Duration.ofMillis(3));
    assertThat(result.isPartial()).isTrue();
    assertThat(result.candidates()).containsExactly(first, second);
    assertThat(result.successfulStages()).containsExactly("lexical");
    assertThat(result.failedStages()).containsExactly("vector");
    assertThatThrownBy(() -> result.candidates().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> result.successfulStages().add("other"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> result.failedStages().add("other"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldDistinguishEmptySuccessFromEmptyPartialResult() {
    var complete =
        new PipelineResult<>(
            "search", List.of(), false, Duration.ZERO, Set.of("lexical"), Set.of());
    var partial =
        new PipelineResult<>("search", List.of(), true, Duration.ZERO, Set.of(), Set.of("lexical"));
    assertThat(complete.candidates()).isEmpty();
    assertThat(complete.isPartial()).isFalse();
    assertThat(partial.candidates()).isEmpty();
    assertThat(partial.isPartial()).isTrue();
  }

  @Test
  void shouldRejectOverlappingStageOutcomes() {
    assertThatThrownBy(
            () ->
                new PipelineResult<>(
                    "search", List.of(), true, Duration.ZERO, Set.of("lexical"), Set.of("lexical")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be disjoint");
  }

  @Test
  void shouldRejectPartialFlagInconsistentWithStageOutcomes() {
    assertThatThrownBy(
            () ->
                new PipelineResult<>(
                    "search", List.of(), false, Duration.ZERO, Set.of(), Set.of("lexical")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("isPartial");
    assertThatThrownBy(
            () ->
                new PipelineResult<>(
                    "search", List.of(), true, Duration.ZERO, Set.of("lexical"), Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("isPartial");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t\n"})
  void shouldRejectBlankPipelineAndStageNames(String name) {
    assertThatThrownBy(
            () -> new PipelineResult<>(name, List.of(), false, Duration.ZERO, Set.of(), Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pipelineName");
    assertThatThrownBy(
            () ->
                new PipelineResult<>(
                    "search", List.of(), false, Duration.ZERO, Set.of(name), Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stage names");
    assertThatThrownBy(
            () ->
                new PipelineResult<>(
                    "search", List.of(), true, Duration.ZERO, Set.of(), Set.of(name)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stage names");
  }

  @Test
  void shouldRejectNegativeDuration() {
    assertThatThrownBy(
            () ->
                new PipelineResult<>(
                    "search", List.of(), false, Duration.ofNanos(-1), Set.of(), Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duration");
  }

  @Test
  void shouldRejectNullFields() {
    assertThatThrownBy(
            () -> new PipelineResult<>(null, List.of(), false, Duration.ZERO, Set.of(), Set.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new PipelineResult<>("search", null, false, Duration.ZERO, Set.of(), Set.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new PipelineResult<>("search", List.of(), false, null, Set.of(), Set.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new PipelineResult<>("search", List.of(), false, Duration.ZERO, null, Set.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new PipelineResult<>("search", List.of(), false, Duration.ZERO, Set.of(), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void shouldRejectNullCollectionEntries() {
    List<Candidate<String>> candidates = Arrays.asList((Candidate<String>) null);
    Set<String> stages = new HashSet<>();
    stages.add(null);
    assertThatThrownBy(
            () ->
                new PipelineResult<>(
                    "search", candidates, false, Duration.ZERO, Set.of(), Set.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new PipelineResult<>("search", List.of(), false, Duration.ZERO, stages, Set.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new PipelineResult<>("search", List.of(), true, Duration.ZERO, Set.of(), stages))
        .isInstanceOf(NullPointerException.class);
  }
}
