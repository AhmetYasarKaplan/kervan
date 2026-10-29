package smoke.consumer;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.PipelineResult;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.Ranker;
import io.github.ahmetyasarkaplan.kervan.api.RankingStatus;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalStatus;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import io.github.ahmetyasarkaplan.kervan.core.BatchScoringRanker;
import io.github.ahmetyasarkaplan.kervan.spring.KervanPipelineRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/** Runs against the published starter from outside Kervan's multi-project build. */
@SpringBootApplication(proxyBeanMethods = false)
public class ConsumerSmokeApplication {
  public static void main(String[] args) {
    try (ConfigurableApplicationContext context =
        SpringApplication.run(ConsumerSmokeApplication.class, args)) {
      KervanPipelineRegistry registry = context.getBean(KervanPipelineRegistry.class);
      check(
          registry.names().equals(Set.of("complete", "partial", "bounded")),
          "auto-configured pipelines");

      PipelineResult<String> complete =
          registry.pipeline("complete", String.class, String.class).execute("camera");
      check(!complete.isPartial(), "complete result must not be partial");
      check(complete.retrievalResults().size() == 2, "complete provider count");
      check(
          complete.retrievalResults().stream()
              .allMatch(result -> result.status() == RetrievalStatus.SUCCESS),
          "complete provider status");
      RankedCandidate<String> shared =
          complete.candidates().stream()
              .filter(candidate -> candidate.id().equals("shared"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("shared candidate missing"));
      check(shared.score() > 10.0, "custom ranker did not modify score");
      check(shared.contributions().size() == 2, "shared candidate provenance count");
      check(
          shared.contributions().equals(
              List.of(
                  new SourceContribution("search", 1, 9.0),
                  new SourceContribution("recommendations", 1, 8.0))),
          "shared candidate provenance");

      PipelineResult<String> partial =
          registry.pipeline("partial", String.class, String.class).execute("camera");
      check(partial.isPartial(), "failed optional provider must mark result partial");
      check(partial.failedStages().equals(Set.of("unavailable")), "failed provider name");
      check(partial.retrievalResults().get(1).status() == RetrievalStatus.FAILED, "failure status");
      check(partial.candidates().stream().anyMatch(candidate -> candidate.id().equals("shared")),
          "successful provider candidates must survive optional failure");
      check(context.getBean(AtomicInteger.class).get() == 2, "custom ranker invocation count");

      PipelineResult<String> bounded =
          registry.pipeline("bounded", String.class, String.class).execute("camera");
      check(!bounded.isPartial() && !bounded.isDegraded(), "successful bounded result");
      check(bounded.rankingOutcome().status() == RankingStatus.SUCCESS, "bounded ranking status");
      check(bounded.rankingOutcome().inputCandidates() == 3, "bounded ranking input window");
      check(bounded.rankingOutcome().outputCandidates() == 2, "bounded ranking output limit");
      check(!bounded.rankingOutcome().fallbackUsed(), "successful ranker must not use fallback");
      check(
          bounded.candidates().stream().map(RankedCandidate::id).toList()
              .equals(List.of("recommendation-only", "search-only")),
          "batch scores must reorder the full fused window before output limiting");
      check(bounded.candidates().getFirst().score() == 100.0, "first model score");
      check(bounded.candidates().get(1).score() == 50.0, "second model score");
      check(
          bounded.candidates().getFirst().contributions()
              .equals(List.of(new SourceContribution("recommendations", 2, 6.0))),
          "batch ranker must retain source provenance");
      for (RankedCandidate<String> candidate : bounded.candidates()) {
        var scoring = candidate.rerankingScore().orElseThrow();
        check(scoring.rerankScore() == candidate.score(), "separate model score metadata");
        double expectedFusionScore = candidate.contributions().stream()
            .mapToDouble(contribution -> 1.0 / (60 + contribution.rank())).sum();
        check(Math.abs(scoring.fusionScore() - expectedFusionScore) < 1e-12,
            "original fusion score metadata");
      }

      PipelineResult<String> fallback =
          registry.pipeline("bounded", String.class, String.class).execute("camera-fallback");
      check(!fallback.isPartial() && fallback.isDegraded(), "ranking degradation is independent of retrieval");
      check(fallback.rankingOutcome().status() == RankingStatus.FAILED, "failed scorer status");
      check(fallback.rankingOutcome().fallbackUsed(), "failed scorer must use configured fallback");
      check(fallback.rankingOutcome().outputCandidates() == 0, "failed scorer output count");
      check(
          fallback.candidates().stream().map(RankedCandidate::id).toList()
              .equals(List.of("shared", "search-only")),
          "fused ordering must survive model failure and respect the output limit");
      check(Math.abs(fallback.candidates().getFirst().score() - 2.0 / 61) < 1e-12,
          "fallback must retain the fusion score");
      check(fallback.candidates().getFirst().contributions().equals(shared.contributions()),
          "fallback must retain all source contributions");
      check(fallback.candidates().stream().allMatch(candidate -> candidate.rerankingScore().isEmpty()),
          "fallback must not invent model scores");
      check(context.getBean(AtomicInteger.class).get() == 2, "legacy ranker invocation count must remain two");
      System.out.println("Independent Kervan starter consumer smoke passed");
    }
  }

  @Bean
  CandidateRetriever<String, String> searchRetriever() {
    return (query, context) -> {
      check(query.equals("camera") || query.equals("camera-fallback"), "query passed to search retriever");
      return List.of(SourceCandidate.of("shared", 9.0), SourceCandidate.of("search-only", 7.0));
    };
  }

  @Bean
  CandidateRetriever<String, String> recommendationRetriever() {
    return (query, context) ->
        List.of(
            SourceCandidate.of("shared", 8.0),
            SourceCandidate.of("recommendation-only", 6.0));
  }

  @Bean
  CandidateRetriever<String, String> unavailableRetriever() {
    return (query, context) -> {
      throw new IllegalStateException("simulated optional provider outage");
    };
  }

  @Bean
  AtomicInteger rankerCalls() {
    return new AtomicInteger();
  }

  @Bean
  Ranker<String, String> boostRanker(AtomicInteger rankerCalls) {
    return (query, candidates, context) -> {
      rankerCalls.incrementAndGet();
      return candidates.stream()
          .limit(context.candidateLimit())
          .map(
              candidate ->
                  new RankedCandidate<>(
                      candidate.id(),
                      candidate.score() + 10.0,
                      candidate.contributions(),
                      candidate.attributes()))
          .toList();
    };
  }

  @Bean
  Ranker<String, String> batchRanker() {
    return new BatchScoringRanker<>(
        (query, candidates, context) -> {
          check(candidates.size() == 3, "scorer receives the bounded fused window");
          check(context.candidateLimit() == 2, "scorer receives the final ranking limit");
          check(context.remainingBudget().compareTo(Duration.ZERO) > 0,
              "scorer receives a positive remaining time budget");
          check(context.remainingBudget().compareTo(Duration.ofMillis(500)) <= 0,
              "scorer budget must be bounded by the configured timeout");
          if (query.equals("camera-fallback")) {
            throw new IllegalStateException("simulated model outage");
          }
          check(query.equals("camera"), "query passed to batch scorer");
          return Map.of("shared", 1.0, "search-only", 50.0, "recommendation-only", 100.0);
        });
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
