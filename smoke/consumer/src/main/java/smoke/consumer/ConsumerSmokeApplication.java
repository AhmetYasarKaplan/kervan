package smoke.consumer;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.PipelineResult;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.Ranker;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalStatus;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import io.github.ahmetyasarkaplan.kervan.spring.KervanPipelineRegistry;
import java.util.List;
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
      check(registry.names().equals(Set.of("complete", "partial")), "auto-configured pipelines");

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
      System.out.println("Independent Kervan starter consumer smoke passed");
    }
  }

  @Bean
  CandidateRetriever<String, String> searchRetriever() {
    return (query, context) -> {
      check(query.equals("camera"), "query passed to search retriever");
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

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
