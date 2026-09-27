# Spring Boot integration

The `kervan-spring-boot-starter` module assembles named `RankingPipeline` instances
from application configuration and existing Spring beans. It adds a configuration
layer around the Java builder API; applications still implement their own
providers and choose how queries map to provider requests. In this repository,
applications can depend on `project(":kervan-spring-boot-starter")`.

## Configure a pipeline

Declare each provider as a named `CandidateRetriever<Q, ID>` bean. Concrete
generic types on the bean method let the starter check query and canonical ID
compatibility at startup and when the pipeline is retrieved.

```java
import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ProviderConfiguration {
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
```

Configure the pipeline in `application.yml` using those bean names:

```yaml
kervan:
  pipelines:
    discovery:
      timeout: 250ms
      result-size: 3
      failure-policy: partial-results
      retrievers:
        - name: search
          bean: searchRetriever
          candidate-limit: 2
          timeout: 140ms
          required: true
        - name: recommendations
          bean: recommendationRetriever
          candidate-limit: 2
          timeout: 100ms
          required: false
      fusion:
        type: rrf
        rank-constant: 60
        candidate-limit: 3
      ranker:
        type: identity
        candidate-limit: 3
      policy:
        type: identity
```

The retriever name identifies a source in Kervan results; `bean` identifies the
Spring bean that implements it. The configured order determines stable source
ordering. An optional positive `weight` on a retriever is used only with
`fusion.type: weighted-min-max`; omitted weights are 1.0. Fusion may be `rrf`,
`weighted-min-max`, or `custom`. Custom fusion requires a `bean` naming a
`FusionStrategy<ID>` bean. The ranker may be `identity` or `custom`, where a
custom ranker names a `Ranker<Q, ID>` bean. Without a fusion section, the core
uses RRF with rank constant 60. Without a ranker section, the starter uses an
identity ranker. The default fusion candidate limit is the sum of configured
retriever limits; the default ranker limit is `result-size`.

The final `policy` section is optional. It accepts `identity` or `custom`; a
custom policy requires a `bean` naming a `RankingPolicy<Q, ID>` bean. The
policy can filter or reorder existing ranked candidates while retaining their
canonical IDs and provenance. If it filters results, set the ranker candidate
limit above `result-size` to provide replacement candidates.

Inject `KervanPipelineRegistry` and request a named pipeline with explicit type
tokens:

```java
import io.github.ahmetyasarkaplan.kervan.api.PipelineResult;
import io.github.ahmetyasarkaplan.kervan.spring.KervanPipelineRegistry;
import org.springframework.stereotype.Service;

@Service
class DiscoveryService {
  private final KervanPipelineRegistry registry;

  DiscoveryService(KervanPipelineRegistry registry) {
    this.registry = registry;
  }

  PipelineResult<String> search(String query) {
    return registry.pipeline("discovery", String.class, String.class).execute(query);
  }
}
```

The result contains ranked canonical IDs, source contributions, provider
outcomes, and partial-result status. An Elasticsearch retriever can be one of
the application beans; its native hybrid request remains inside that provider.
See the [Elasticsearch adapter guide](elasticsearch.md) for that boundary.

## Validation, health, and lifecycle

The starter resolves named beans and constructs all configured pipelines during
application startup. Missing or wrongly typed beans, duplicate source names,
invalid durations or limits, unsupported strategy names, and detectable generic
type conflicts fail startup with the relevant `kervan.pipelines` property path.
Java's generic type erasure can prevent some conflicts from being detected;
declare concrete generic types on `@Bean` methods and use the registry's type
tokens rather than an unchecked cast.

When Spring Boot's health types are present, the starter contributes a
`kervanHealthIndicator`. It reports whether the registry is open and the number
of configured pipelines. It does not contact providers or report their
availability, and it does not include queries, candidate IDs, or credentials.
Without the health types, no Kervan health indicator is registered.

The application context owns the registry and closes its pipelines on shutdown.
Application code should obtain pipelines from the registry and let the context
manage their lifecycle.
