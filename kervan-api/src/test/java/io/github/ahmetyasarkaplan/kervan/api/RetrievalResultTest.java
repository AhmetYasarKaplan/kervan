package io.github.ahmetyasarkaplan.kervan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class RetrievalResultTest {

  @Test
  void shouldCreateSuccessfulResult() {
    Candidate<String> candidate = Candidate.of("doc-1", 10.0, "lexical");
    RetrievalResult<String> result =
        RetrievalResult.success("lexical", List.of(candidate), Duration.ofMillis(25));

    assertThat(result.retrieverName()).isEqualTo("lexical");
    assertThat(result.candidates()).containsExactly(candidate);
    assertThat(result.duration()).isEqualTo(Duration.ofMillis(25));
    assertThat(result.status()).isEqualTo(RetrievalStatus.SUCCESS);
    assertThat(result.failureReason()).isNull();
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
    RetrievalResult<String> result =
        RetrievalResult.failure("vector", "Connection refused", Duration.ofMillis(50));

    assertThat(result.status()).isEqualTo(RetrievalStatus.FAILED);
    assertThat(result.candidates()).isEmpty();
    assertThat(result.failureReason()).isEqualTo("Connection refused");
  }

  @Test
  void shouldCreateTimedOutResult() {
    RetrievalResult<String> result = RetrievalResult.timedOut("lexical", Duration.ofMillis(100));

    assertThat(result.status()).isEqualTo(RetrievalStatus.TIMED_OUT);
    assertThat(result.candidates()).isEmpty();
    assertThat(result.failureReason()).isEqualTo("Retrieval timed out");
  }

  @Test
  void shouldRejectNullOrBlankRetrieverName() {
    assertThatThrownBy(() -> new RetrievalResult<>(null, List.of(), Duration.ZERO, RetrievalStatus.EMPTY, null))
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(() -> new RetrievalResult<>("  ", List.of(), Duration.ZERO, RetrievalStatus.EMPTY, null))
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
}
