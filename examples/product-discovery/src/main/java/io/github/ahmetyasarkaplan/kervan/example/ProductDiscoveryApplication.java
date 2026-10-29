package io.github.ahmetyasarkaplan.kervan.example;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.github.ahmetyasarkaplan.kervan.api.PipelineResult;
import io.github.ahmetyasarkaplan.kervan.spring.KervanPipelineRegistry;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Runs one search after seeding a local catalog and prints the ranked IDs and provider status. */
@SpringBootApplication
public class ProductDiscoveryApplication {

  /** Creates the demo application entry point. */
  public ProductDiscoveryApplication() {}

  /**
   * Starts Spring Boot, seeds the local catalog, and runs one pipeline request.
   *
   * @param args Spring Boot command-line arguments
   * @throws IOException if catalog setup cannot communicate with Elasticsearch
   */
  public static void main(String[] args) throws IOException {
    try (var application = SpringApplication.run(ProductDiscoveryApplication.class, args)) {
      DemoCatalog.seed(application.getBean(ElasticsearchClient.class));
      var pipeline =
          application
              .getBean(KervanPipelineRegistry.class)
              .pipeline("discovery", ProductQuery.class, String.class);
      PipelineResult<String> result =
          pipeline.execute(new ProductQuery("camera tripod", List.of(1.0f, 0.0f, 0.0f)));
      System.out.println(
          "\nRanked product IDs (partial="
              + result.isPartial()
              + ", degraded="
              + result.isDegraded()
              + ", ranking="
              + result.rankingOutcome().status()
              + "):");
      result
          .candidates()
          .forEach(
              candidate ->
                  System.out.printf(
                      "%s score=%.6f fusion=%s sources=%s%n",
                      candidate.id(),
                      candidate.score(),
                      candidate
                          .rerankingScore()
                          .map(scores -> scores.fusionScore())
                          .orElse(candidate.score()),
                      candidate.contributions().stream().map(source -> source.source()).toList()));
      result
          .retrievalResults()
          .forEach(
              provider -> System.out.println(provider.retrieverName() + ": " + provider.status()));
    }
  }
}
