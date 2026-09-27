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

  public static void main(String[] args) throws IOException {
    try (var application = SpringApplication.run(ProductDiscoveryApplication.class, args)) {
      DemoCatalog.seed(application.getBean(ElasticsearchClient.class));
      var pipeline =
          application
              .getBean(KervanPipelineRegistry.class)
              .pipeline("discovery", ProductQuery.class, String.class);
      PipelineResult<String> result =
          pipeline.execute(new ProductQuery("camera", List.of(1.0f, 0.0f, 0.0f)));
      System.out.println("\nRanked product IDs (partial=" + result.isPartial() + "):");
      result
          .candidates()
          .forEach(
              candidate ->
                  System.out.printf(
                      "%s score=%.6f sources=%s%n",
                      candidate.id(),
                      candidate.score(),
                      candidate.contributions().stream().map(source -> source.source()).toList()));
      result
          .retrievalResults()
          .forEach(
              provider -> System.out.println(provider.retrieverName() + ": " + provider.status()));
    }
  }
}
