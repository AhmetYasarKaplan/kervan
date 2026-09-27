package io.github.ahmetyasarkaplan.kervan.example;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.elasticsearch.ElasticsearchCandidateRetriever;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ProductDiscoveryConfiguration {

  @Bean(destroyMethod = "close")
  ElasticsearchClient elasticsearchClient(
      @Value("${demo.elasticsearch-url:http://localhost:9200}") String url) {
    return ElasticsearchClient.of(builder -> builder.host(url));
  }

  @Bean
  CandidateRetriever<ProductQuery, String> searchRetriever(ElasticsearchClient client) {
    return ElasticsearchCandidateRetriever.<ProductQuery, String, Void>builder(client, Void.class)
        .requestFactory(
            (query, context) ->
                new SearchRequest.Builder()
                    .index(DemoCatalog.INDEX)
                    .query(q -> q.match(m -> m.field("title").query(query.text())))
                    .knn(
                        k ->
                            k.field("vector")
                                .queryVector(query.vector())
                                .k(context.candidateLimit())
                                .numCandidates(20))
                    .source(source -> source.fetch(false)))
        .idMapper(hit -> "catalog:" + hit.id())
        .build();
  }

  @Bean
  CandidateRetriever<ProductQuery, String> recommendationRetriever(
      @Value("${demo.recommendations-url:http://localhost:8085/recommendations}") String url) {
    return new RecommendationHttpRetriever(URI.create(url));
  }

  @Bean
  CandidateRetriever<ProductQuery, String> popularityRetriever() {
    List<SourceCandidate<String>> popular =
        List.of(
            SourceCandidate.of("catalog:camera-kit", 100),
            SourceCandidate.of("catalog:tripod", 80),
            SourceCandidate.of("catalog:lens", 60));
    return (query, context) ->
        popular.subList(0, Math.min(context.candidateLimit(), popular.size()));
  }
}
