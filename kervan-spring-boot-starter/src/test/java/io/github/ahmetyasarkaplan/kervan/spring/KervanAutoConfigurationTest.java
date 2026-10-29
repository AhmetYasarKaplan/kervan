package io.github.ahmetyasarkaplan.kervan.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.FusionStrategy;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.Ranker;
import io.github.ahmetyasarkaplan.kervan.api.RankingPolicy;
import io.github.ahmetyasarkaplan.kervan.api.RankingStatus;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.core.BatchScoringRanker;
import io.github.ahmetyasarkaplan.kervan.core.FirstSeenFusion;
import io.github.ahmetyasarkaplan.kervan.core.PipelineObserver;
import io.github.ahmetyasarkaplan.kervan.core.RankingPipeline;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

class KervanAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(SampleApplication.class);

  private final ApplicationContextRunner yamlRunner =
      runner.withInitializer(
          context -> {
            try {
              new YamlPropertySourceLoader()
                  .load("sample-kervan", new ClassPathResource("sample-kervan.yml"))
                  .forEach(context.getEnvironment().getPropertySources()::addLast);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });

  @Test
  void autoConfiguresCrossProviderPipelineFromYaml() {
    yamlRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          KervanPipelineRegistry registry = context.getBean(KervanPipelineRegistry.class);
          assertThat(registry.size()).isEqualTo(1);
          assertThat(registry.names()).containsExactly("sample");

          RankingPipeline<String, String> pipeline =
              registry.pipeline("sample", String.class, String.class);
          var result = pipeline.execute("camera");

          assertThat(result.pipelineName()).isEqualTo("sample");
          assertThat(result.isPartial()).isFalse();
          assertThat(result.candidates())
              .extracting(RankedCandidate::id)
              .containsExactly("shared", "recommendation-only", "search-only");
          assertThat(result.candidates().getFirst().contributions())
              .extracting(contribution -> contribution.source())
              .containsExactly("search", "recommendations");
        });
  }

  @Test
  void configuresWeightedRrfFromRetrieverWeights() {
    configuredRunner(
            "kervan.pipelines.sample.retrievers[0].weight=1",
            "kervan.pipelines.sample.retrievers[1].weight=3",
            "kervan.pipelines.sample.fusion.type=weighted-rrf",
            "kervan.pipelines.sample.fusion.rank-constant=2")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var result =
                  context
                      .getBean(KervanPipelineRegistry.class)
                      .pipeline("sample", String.class, String.class)
                      .execute("camera");
              assertThat(result.candidates())
                  .extracting(RankedCandidate::id)
                  .containsExactly("shared", "recommendation-only", "search-only");
              assertThat(result.candidates().getFirst().score()).isEqualTo(1.0 / 3 + 3.0 / 4);
              assertThat(result.candidates().get(1).score()).isEqualTo(3.0 / 3);
            });
  }

  @Test
  void attachesApplicationObserverBeanToConfiguredPipeline() {
    yamlRunner
        .withUserConfiguration(ObserverComponents.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              context
                  .getBean(KervanPipelineRegistry.class)
                  .pipeline("sample", String.class, String.class)
                  .execute("camera");
              assertThat(context.getBean(AtomicInteger.class)).hasValue(1);
            });
  }

  @Test
  void resolvesCustomFusionAndRankerBeans() {
    configuredRunner(
            "kervan.pipelines.sample.fusion.type=custom",
            "kervan.pipelines.sample.fusion.bean=customFusion",
            "kervan.pipelines.sample.ranker.type=custom",
            "kervan.pipelines.sample.ranker.bean=customRanker")
        .withUserConfiguration(CustomComponents.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var pipeline =
                  context
                      .getBean(KervanPipelineRegistry.class)
                      .pipeline("sample", String.class, String.class);
              var result = pipeline.execute("camera");
              assertThat(result.candidates())
                  .extracting(RankedCandidate::id)
                  .containsExactly("recommendation-only", "search-only", "shared");
              assertThat(result.candidates().getLast().score()).isEqualTo(9.0);
            });
  }

  @Test
  void configuresBoundedBatchRankingAndReportsFallback() {
    configuredRunner(
            "kervan.pipelines.sample.ranker.type=custom",
            "kervan.pipelines.sample.ranker.bean=batchRanker",
            "kervan.pipelines.sample.ranker.input-limit=3",
            "kervan.pipelines.sample.ranker.candidate-limit=2",
            "kervan.pipelines.sample.ranker.timeout=500ms",
            "kervan.pipelines.sample.ranker.failure-policy=keep-fused")
        .withUserConfiguration(BatchComponents.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var pipeline =
                  context
                      .getBean(KervanPipelineRegistry.class)
                      .pipeline("sample", String.class, String.class);
              var ranked = pipeline.execute("camera");
              assertThat(ranked.candidates())
                  .extracting(RankedCandidate::id)
                  .containsExactly("search-only", "recommendation-only");
              assertThat(ranked.rankingOutcome().status()).isEqualTo(RankingStatus.SUCCESS);
              assertThat(ranked.isDegraded()).isFalse();
              assertThat(ranked.candidates().getFirst().rerankingScore()).isPresent();
              var fallback = pipeline.execute("fail-ranking");
              assertThat(fallback.candidates())
                  .extracting(RankedCandidate::id)
                  .containsExactly("shared", "recommendation-only");
              assertThat(fallback.rankingOutcome().status()).isEqualTo(RankingStatus.FAILED);
              assertThat(fallback.rankingOutcome().fallbackUsed()).isTrue();
              assertThat(fallback.isPartial()).isFalse();
              assertThat(fallback.isDegraded()).isTrue();
              assertThat(fallback.candidates())
                  .allSatisfy(candidate -> assertThat(candidate.rerankingScore()).isEmpty());
            });
  }

  @Test
  void rejectsIncompleteOrContradictoryBoundedRankingSettings() {
    configuredRunner(
            "kervan.pipelines.sample.ranker.type=identity",
            "kervan.pipelines.sample.ranker.timeout=100ms")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("ranker.input-limit"));
    configuredRunner(
            "kervan.pipelines.sample.ranker.type=identity",
            "kervan.pipelines.sample.ranker.input-limit=2",
            "kervan.pipelines.sample.ranker.candidate-limit=3",
            "kervan.pipelines.sample.ranker.timeout=100ms",
            "kervan.pipelines.sample.ranker.failure-policy=fail")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("ranker.candidate-limit"));
    configuredRunner(
            "kervan.pipelines.sample.ranker.type=identity",
            "kervan.pipelines.sample.ranker.input-limit=3",
            "kervan.pipelines.sample.ranker.timeout=100ms")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("ranker.failure-policy"));
  }

  @Test
  void resolvesCustomPolicyBeanAndFiltersFinalCandidates() {
    configuredRunner(
            "kervan.pipelines.sample.policy.type=custom",
            "kervan.pipelines.sample.policy.bean=customPolicy")
        .withUserConfiguration(CustomComponents.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var result =
                  context
                      .getBean(KervanPipelineRegistry.class)
                      .pipeline("sample", String.class, String.class)
                      .execute("camera");
              assertThat(result.candidates())
                  .extracting(RankedCandidate::id)
                  .containsExactly("recommendation-only", "search-only");
            });
  }

  @Test
  void missingRetrieverBeanFailsAtStartupWithConfigurationPath() {
    configuredRunner("kervan.pipelines.sample.retrievers[0].bean=missingRetriever")
        .run(
            context -> {
              assertThat(context.getStartupFailure())
                  .isNotNull()
                  .hasStackTraceContaining("sample")
                  .hasStackTraceContaining("missingRetriever")
                  .hasStackTraceContaining("bean");
            });
  }

  @Test
  void wrongBeanTypeFailsAtStartupWithConfigurationPath() {
    configuredRunner("kervan.pipelines.sample.retrievers[0].bean=wrongRetriever")
        .withUserConfiguration(WrongBeanType.class)
        .run(
            context -> {
              assertThat(context.getStartupFailure())
                  .isNotNull()
                  .hasStackTraceContaining("sample")
                  .hasStackTraceContaining("wrongRetriever");
            });
  }

  @Test
  void duplicateRetrieverNamesFailAtStartup() {
    configuredRunner("kervan.pipelines.sample.retrievers[1].name=search")
        .run(
            context -> {
              assertThat(context.getStartupFailure())
                  .isNotNull()
                  .hasStackTraceContaining("sample")
                  .hasStackTraceContaining("search")
                  .hasStackTraceContaining("name");
            });
  }

  @Test
  void invalidCandidateLimitFailsAtStartup() {
    configuredRunner("kervan.pipelines.sample.retrievers[0].candidate-limit=0")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .hasStackTraceContaining("candidate-limit"));
  }

  @Test
  void detectableGenericMismatchFailsAtStartup() {
    configuredRunner("kervan.pipelines.sample.retrievers[1].bean=incompatibleRetriever")
        .withUserConfiguration(IncompatibleRetriever.class)
        .run(
            context -> {
              assertThat(context.getStartupFailure())
                  .isNotNull()
                  .hasStackTraceContaining("kervan.pipelines.sample.retrievers[1].bean")
                  .hasStackTraceContaining("incompatibleRetriever")
                  .hasStackTraceContaining("java.lang.Integer")
                  .hasStackTraceContaining("java.lang.String");
            });
  }

  @Test
  void rejectsUnknownConfigurationKey() {
    configuredRunner("kervan.pipelines.sample.unrecognized-setting=value")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .hasStackTraceContaining("kervan.pipelines.sample.unrecognized-setting"));
  }

  @Test
  void missingCustomFusionBeanFailsAtStartup() {
    configuredRunner(
            "kervan.pipelines.sample.fusion.type=custom",
            "kervan.pipelines.sample.fusion.bean=missingFusion")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .hasStackTraceContaining("kervan.pipelines.sample.fusion.bean")
                    .hasStackTraceContaining("missingFusion"));
  }

  @Test
  void missingCustomRankerBeanFailsAtStartup() {
    configuredRunner(
            "kervan.pipelines.sample.ranker.type=custom",
            "kervan.pipelines.sample.ranker.bean=missingRanker")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .hasStackTraceContaining("kervan.pipelines.sample.ranker.bean")
                    .hasStackTraceContaining("missingRanker"));
  }

  @Test
  void missingCustomPolicyBeanFailsAtStartup() {
    configuredRunner(
            "kervan.pipelines.sample.policy.type=custom",
            "kervan.pipelines.sample.policy.bean=missingPolicy")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .hasStackTraceContaining("kervan.pipelines.sample.policy.bean")
                    .hasStackTraceContaining("missingPolicy"));
  }

  @Test
  void detectablePolicyGenericMismatchFailsAtStartup() {
    configuredRunner(
            "kervan.pipelines.sample.policy.type=custom",
            "kervan.pipelines.sample.policy.bean=incompatiblePolicy")
        .withUserConfiguration(IncompatiblePolicy.class)
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .hasStackTraceContaining("kervan.pipelines.sample.policy.bean")
                    .hasStackTraceContaining("java.lang.Integer"));
  }

  @Test
  void applicationCanRegisterAdditionalCustomRetrieverBean() {
    configuredRunner(
            "kervan.pipelines.sample.retrievers[2].name=popularity",
            "kervan.pipelines.sample.retrievers[2].bean=popularityRetriever",
            "kervan.pipelines.sample.retrievers[2].candidate-limit=1",
            "kervan.pipelines.sample.retrievers[2].timeout=500ms",
            "kervan.pipelines.sample.retrievers[2].required=false")
        .withUserConfiguration(AdditionalRetriever.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var pipeline =
                  context
                      .getBean(KervanPipelineRegistry.class)
                      .pipeline("sample", String.class, String.class);
              var result = pipeline.execute("camera");
              assertThat(result.retrievalResults())
                  .extracting(retrieval -> retrieval.retrieverName())
                  .containsExactly("search", "recommendations", "popularity");
              assertThat(result.candidates())
                  .extracting(RankedCandidate::id)
                  .contains("popularity-only");
            });
  }

  @Test
  void registryRejectsAccessWithIncompatibleTypeTokens() {
    yamlRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          var registry = context.getBean(KervanPipelineRegistry.class);
          assertThatThrownBy(() -> registry.pipeline("sample", Integer.class, String.class))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("sample");
        });
  }

  @Test
  void closingRegistryClosesManagedPipelines() {
    yamlRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          var registry = context.getBean(KervanPipelineRegistry.class);
          var pipeline = registry.pipeline("sample", String.class, String.class);
          registry.close();

          assertThatThrownBy(() -> pipeline.execute("camera"))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("closed");
        });
  }

  @Test
  void exposesHealthIndicatorWhenActuatorIsPresent() {
    yamlRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasBean("kervanHealthIndicator");
          var health = context.getBean("kervanHealthIndicator", HealthIndicator.class).health();
          assertThat(health).isNotNull();
          assertThat(health.getStatus()).isEqualTo(Status.UP);
          assertThat(health.getDetails()).doesNotContainKeys("query", "candidateId", "credentials");
        });
  }

  @Test
  void doesNotExposeHealthIndicatorWhenActuatorIsAbsent() {
    yamlRunner
        .withClassLoader(new FilteredClassLoader(HealthIndicator.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean("kervanHealthIndicator");
            });
  }

  private ApplicationContextRunner configuredRunner(String... overrides) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("kervan.pipelines.sample.timeout", "2s");
    values.put("kervan.pipelines.sample.result-size", "3");
    values.put("kervan.pipelines.sample.failure-policy", "partial-results");
    values.put("kervan.pipelines.sample.retrievers[0].name", "search");
    values.put("kervan.pipelines.sample.retrievers[0].bean", "searchRetriever");
    values.put("kervan.pipelines.sample.retrievers[0].candidate-limit", "2");
    values.put("kervan.pipelines.sample.retrievers[0].timeout", "500ms");
    values.put("kervan.pipelines.sample.retrievers[0].required", "true");
    values.put("kervan.pipelines.sample.retrievers[1].name", "recommendations");
    values.put("kervan.pipelines.sample.retrievers[1].bean", "recommendationRetriever");
    values.put("kervan.pipelines.sample.retrievers[1].candidate-limit", "2");
    values.put("kervan.pipelines.sample.retrievers[1].timeout", "500ms");
    values.put("kervan.pipelines.sample.retrievers[1].required", "false");
    for (String override : overrides) {
      int separator = override.indexOf('=');
      values.put(override.substring(0, separator), override.substring(separator + 1));
    }
    return runner.withPropertyValues(
        values.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .toArray(String[]::new));
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomComponents {
    @Bean
    FusionStrategy<String> customFusion() {
      return new FirstSeenFusion<>();
    }

    @Bean
    Ranker<String, String> customRanker() {
      return (query, candidates, context) -> {
        List<RankedCandidate<String>> reversed = new ArrayList<>(candidates);
        Collections.reverse(reversed);
        return List.copyOf(
            reversed.subList(0, Math.min(reversed.size(), context.candidateLimit())));
      };
    }

    @Bean
    RankingPolicy<String, String> customPolicy() {
      return (query, candidates, context) ->
          candidates.stream().filter(candidate -> !candidate.id().equals("shared")).toList();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class BatchComponents {
    @Bean
    Ranker<String, String> batchRanker() {
      return new BatchScoringRanker<>(
          (query, candidates, context) -> {
            if (query.equals("fail-ranking")) {
              throw new IllegalStateException("private remote response");
            }
            assertThat(context.remainingBudget())
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofMillis(500));
            return Map.of("shared", 0.1, "recommendation-only", 0.5, "search-only", 0.9);
          });
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class IncompatiblePolicy {
    @Bean
    RankingPolicy<Integer, String> incompatiblePolicy() {
      return (query, candidates, context) -> candidates;
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class WrongBeanType {
    @Bean
    String wrongRetriever() {
      return "not a retriever";
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class IncompatibleRetriever {
    @Bean
    CandidateRetriever<Integer, String> incompatibleRetriever() {
      return (query, context) -> List.of();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AdditionalRetriever {
    @Bean
    CandidateRetriever<String, String> popularityRetriever() {
      return (query, context) -> List.of(SourceCandidate.of("popularity-only", 5.0));
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ObserverComponents {
    @Bean
    AtomicInteger completedPipelines() {
      return new AtomicInteger();
    }

    @Bean
    PipelineObserver pipelineObserver(AtomicInteger completedPipelines) {
      return name ->
          new PipelineObserver.Execution() {
            @Override
            public void succeeded(boolean partial, Duration duration, int candidates) {
              completedPipelines.incrementAndGet();
            }
          };
    }
  }
}
