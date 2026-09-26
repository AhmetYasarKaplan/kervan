package io.github.ahmetyasarkaplan.kervan.testkit;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import java.util.List;
import java.util.Objects;

/** Throws a caller-provided failure on every invocation. */
public final class FailingRetriever<Q, ID> implements CandidateRetriever<Q, ID> {

  private final RuntimeException failure;

  public FailingRetriever(RuntimeException failure) {
    this.failure = Objects.requireNonNull(failure, "failure must not be null");
  }

  @Override
  public List<SourceCandidate<ID>> retrieve(Q query, RetrievalContext context) {
    Objects.requireNonNull(context, "context must not be null");
    throw failure;
  }
}
