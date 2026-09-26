package io.github.ahmetyasarkaplan.kervan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PipelineExecutionExceptionTest {

  @Test
  void shouldSnapshotProviderResultsWithoutRawFailureDetails() {
    var results =
        new ArrayList<RetrievalResult<?>>(
            List.of(RetrievalResult.failure("search", Duration.ZERO)));
    var failure =
        new PipelineExecutionException(
            "discovery", PipelineExecutionException.Reason.REQUIRED_RETRIEVER_FAILED, results);
    results.clear();

    assertThat(failure.pipelineName()).isEqualTo("discovery");
    assertThat(failure.reason())
        .isEqualTo(PipelineExecutionException.Reason.REQUIRED_RETRIEVER_FAILED);
    assertThat(failure.retrievalResults()).hasSize(1);
    assertThat(failure.getMessage()).contains("discovery").doesNotContain("provider exception");
    assertThatThrownBy(() -> failure.retrievalResults().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
