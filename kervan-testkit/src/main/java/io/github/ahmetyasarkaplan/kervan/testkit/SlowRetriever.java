package io.github.ahmetyasarkaplan.kervan.testkit;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Adds a configured delay before invoking another retriever. */
public final class SlowRetriever<Q, ID> implements CandidateRetriever<Q, ID> {

  private final Duration delay;
  private final CandidateRetriever<Q, ID> delegate;

  public SlowRetriever(Duration delay, CandidateRetriever<Q, ID> delegate) {
    this.delay = Objects.requireNonNull(delay, "delay must not be null");
    if (delay.isZero() || delay.isNegative()) {
      throw new IllegalArgumentException("delay must be positive");
    }
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
  }

  @Override
  public List<SourceCandidate<ID>> retrieve(Q query, RetrievalContext context) {
    Objects.requireNonNull(context, "context must not be null");
    try {
      Thread.sleep(delay);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedRetrievalException(e);
    }
    return delegate.retrieve(query, context);
  }

  /** Signals that the test invocation was interrupted before its delegate ran. */
  public static final class InterruptedRetrievalException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private InterruptedRetrievalException(InterruptedException cause) {
      super("slow retriever interrupted", cause);
    }
  }
}
