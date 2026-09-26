package io.github.ahmetyasarkaplan.kervan.testkit;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import java.util.List;

class StubRetrieverContractTest
    implements CandidateRetrieverContract<String, String>,
        FailingCandidateRetrieverContract<String, String> {

  @Override
  public CandidateRetriever<String, String> retriever() {
    return new StubRetriever<>(
        List.of(
            SourceCandidate.of("canonical-a", 3),
            SourceCandidate.of("canonical-b", 2),
            SourceCandidate.of("canonical-c", 1)));
  }

  @Override
  public String query() {
    return "fixture";
  }

  @Override
  public List<String> expectedCanonicalIds() {
    return List.of("canonical-a", "canonical-b", "canonical-c");
  }

  @Override
  public CandidateRetriever<String, String> failingRetriever() {
    return new FailingRetriever<>(new IllegalStateException("fixture failure"));
  }

  @Override
  public String failingQuery() {
    return "failure fixture";
  }

  @Override
  public Class<? extends RuntimeException> expectedFailureType() {
    return IllegalStateException.class;
  }
}
