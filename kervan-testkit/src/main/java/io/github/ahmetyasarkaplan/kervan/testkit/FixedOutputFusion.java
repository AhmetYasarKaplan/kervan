package io.github.ahmetyasarkaplan.kervan.testkit;

import io.github.ahmetyasarkaplan.kervan.api.FusionContext;
import io.github.ahmetyasarkaplan.kervan.api.FusionStrategy;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalResult;
import java.util.List;
import java.util.Objects;

/** Isolates downstream pipeline behavior with a fixed, ordered fusion result. */
public final class FixedOutputFusion<ID> implements FusionStrategy<ID> {

  private final List<RankedCandidate<ID>> candidates;

  public FixedOutputFusion(List<RankedCandidate<ID>> candidates) {
    this.candidates =
        List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
  }

  @Override
  public List<RankedCandidate<ID>> fuse(List<RetrievalResult<ID>> results, FusionContext context) {
    Objects.requireNonNull(results, "results must not be null");
    Objects.requireNonNull(context, "context must not be null");
    return candidates.subList(0, Math.min(candidates.size(), context.candidateLimit()));
  }
}
