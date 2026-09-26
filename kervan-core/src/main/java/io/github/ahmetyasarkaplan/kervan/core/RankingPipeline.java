package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.FusionContext;
import io.github.ahmetyasarkaplan.kervan.api.FusionStrategy;
import io.github.ahmetyasarkaplan.kervan.api.PipelineResult;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.Ranker;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalResult;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.api.SourceHit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Executes registered providers sequentially, then fuses and ranks their canonical candidates. */
public final class RankingPipeline<Q, ID> {

  private final String name;
  private final List<RetrieverSpec<Q, ID>> retrievers;
  private final FusionStrategy<ID> fusion;
  private final int fusionLimit;
  private final Ranker<Q, ID> ranker;
  private final int rankingLimit;
  private final int resultLimit;

  private RankingPipeline(Builder<Q, ID> builder) {
    name = builder.name;
    retrievers = List.copyOf(builder.retrievers);
    fusion = builder.fusion;
    fusionLimit = builder.fusionLimit;
    ranker = builder.ranker;
    rankingLimit = builder.rankingLimit;
    resultLimit = builder.resultLimit;
  }

  public static <Q, ID> Builder<Q, ID> builder(String name) {
    return new Builder<>(name);
  }

  /**
   * Invokes providers in registration order. Provider exceptions propagate; failure policies and
   * deadlines are defined separately from this sequential execution path.
   */
  public PipelineResult<ID> execute(Q query) {
    long pipelineStart = System.nanoTime();
    List<RetrievalResult<ID>> results = new ArrayList<>(retrievers.size());
    Set<String> successfulStages = new LinkedHashSet<>();

    for (RetrieverSpec<Q, ID> spec : retrievers) {
      long retrievalStart = System.nanoTime();
      CandidateRetriever<Q, ID> retriever = spec.retriever();
      List<SourceCandidate<ID>> raw =
          retriever.retrieve(query, RetrievalContext.of(spec.candidateLimit()));
      List<SourceHit<ID>> hits = sanitize(raw, spec.candidateLimit());
      Duration duration = Duration.ofNanos(System.nanoTime() - retrievalStart);
      results.add(RetrievalResult.success(spec.name(), hits, duration));
      successfulStages.add(spec.name());
    }

    List<RankedCandidate<ID>> fused =
        limitUnique(fusion.fuse(List.copyOf(results), FusionContext.of(fusionLimit)), fusionLimit);
    List<RankedCandidate<ID>> ranked =
        limitUnique(ranker.rank(query, fused, RankingContext.of(rankingLimit)), rankingLimit);
    List<RankedCandidate<ID>> finalCandidates =
        List.copyOf(ranked.subList(0, Math.min(ranked.size(), resultLimit)));
    return new PipelineResult<>(
        name,
        finalCandidates,
        false,
        Duration.ofNanos(System.nanoTime() - pipelineStart),
        successfulStages,
        Set.of());
  }

  private static <ID> List<SourceHit<ID>> sanitize(
      List<SourceCandidate<ID>> raw, int candidateLimit) {
    Objects.requireNonNull(raw, "retriever must not return null");
    var unique = new LinkedHashMap<ID, SourceHit<ID>>();
    for (int index = 0; index < raw.size(); index++) {
      SourceCandidate<ID> candidate =
          Objects.requireNonNull(raw.get(index), "retriever must not return null candidates");
      if (unique.size() < candidateLimit) {
        unique.putIfAbsent(candidate.id(), new SourceHit<>(candidate, index + 1));
      }
    }
    return List.copyOf(unique.values());
  }

  private static <ID> List<RankedCandidate<ID>> limitUnique(
      List<RankedCandidate<ID>> candidates, int limit) {
    List<RankedCandidate<ID>> snapshot =
        List.copyOf(Objects.requireNonNull(candidates, "stage must not return null"));
    Set<ID> ids = new HashSet<>();
    for (RankedCandidate<ID> candidate : snapshot) {
      if (!ids.add(candidate.id())) {
        throw new IllegalArgumentException("stage must not return duplicate candidate IDs");
      }
    }
    return List.copyOf(snapshot.subList(0, Math.min(snapshot.size(), limit)));
  }

  public static final class Builder<Q, ID> {
    private final String name;
    private final List<RetrieverSpec<Q, ID>> retrievers = new ArrayList<>();
    private FusionStrategy<ID> fusion;
    private int fusionLimit;
    private Ranker<Q, ID> ranker;
    private int rankingLimit;
    private int resultLimit;

    private Builder(String name) {
      this.name = Objects.requireNonNull(name, "name must not be null");
      if (name.isBlank()) {
        throw new IllegalArgumentException("name must not be blank");
      }
    }

    public Builder<Q, ID> addRetriever(RetrieverSpec<Q, ID> spec) {
      retrievers.add(Objects.requireNonNull(spec, "spec must not be null"));
      return this;
    }

    public Builder<Q, ID> fusion(FusionStrategy<ID> strategy, int candidateLimit) {
      fusion = Objects.requireNonNull(strategy, "strategy must not be null");
      fusionLimit = positive(candidateLimit, "fusion candidateLimit");
      return this;
    }

    public Builder<Q, ID> ranker(Ranker<Q, ID> implementation, int candidateLimit) {
      ranker = Objects.requireNonNull(implementation, "ranker must not be null");
      rankingLimit = positive(candidateLimit, "ranking candidateLimit");
      return this;
    }

    public Builder<Q, ID> resultLimit(int candidateLimit) {
      resultLimit = positive(candidateLimit, "resultLimit");
      return this;
    }

    public RankingPipeline<Q, ID> build() {
      if (retrievers.isEmpty()) {
        throw new IllegalStateException("at least one retriever is required");
      }
      Set<String> names = new HashSet<>();
      for (RetrieverSpec<Q, ID> spec : retrievers) {
        if (!names.add(spec.name())) {
          throw new IllegalStateException("retriever names must be unique");
        }
      }
      if (fusion == null || ranker == null || resultLimit == 0) {
        throw new IllegalStateException("fusion, ranker and resultLimit are required");
      }
      return new RankingPipeline<>(this);
    }

    private static int positive(int value, String name) {
      if (value <= 0) {
        throw new IllegalArgumentException(name + " must be positive");
      }
      return value;
    }
  }
}
