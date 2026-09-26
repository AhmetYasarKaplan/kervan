package io.github.ahmetyasarkaplan.kervan.spring;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import java.util.List;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
class SampleApplication {

  @Bean
  CandidateRetriever<String, String> searchRetriever() {
    return (query, context) ->
        List.of(SourceCandidate.of("shared", 9.0), SourceCandidate.of("search-only", 7.0));
  }

  @Bean
  CandidateRetriever<String, String> recommendationRetriever() {
    return (query, context) ->
        List.of(SourceCandidate.of("recommendation-only", 9.0), SourceCandidate.of("shared", 8.0));
  }
}
