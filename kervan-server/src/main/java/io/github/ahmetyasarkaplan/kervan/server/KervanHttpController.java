package io.github.ahmetyasarkaplan.kervan.server;

import io.github.ahmetyasarkaplan.kervan.api.PipelineExecutionException;
import io.github.ahmetyasarkaplan.kervan.api.PipelineResult;
import io.github.ahmetyasarkaplan.kervan.spring.KervanPipelineRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Exposes explicitly bound pipelines without adding any retrieval or ranking logic. */
@RestController
final class KervanHttpController {

  private final KervanPipelineRegistry registry;
  private final Map<String, HttpPipelineBinding<?, ?>> bindings;
  private final JsonMapper jsonMapper;

  KervanHttpController(
      KervanPipelineRegistry registry,
      List<HttpPipelineBinding<?, ?>> bindings,
      JsonMapper jsonMapper) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
    var byName = new LinkedHashMap<String, HttpPipelineBinding<?, ?>>();
    for (HttpPipelineBinding<?, ?> binding : Objects.requireNonNull(bindings, "bindings")) {
      Objects.requireNonNull(binding, "binding must not be null");
      if (byName.putIfAbsent(binding.pipelineName(), binding) != null) {
        throw new IllegalArgumentException(
            "Duplicate HTTP binding for pipeline '" + binding.pipelineName() + "'");
      }
      requirePipeline(registry, binding);
    }
    this.bindings = Map.copyOf(byName);
  }

  @PostMapping(
      path = "/kervan/pipelines/{name}/execute",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> execute(@PathVariable("name") String name, @RequestBody String body) {
    HttpPipelineBinding<?, ?> binding = bindings.get(name);
    if (binding == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown_pipeline"));
    }
    try {
      return ResponseEntity.ok(runPipeline(registry, binding, jsonMapper, body));
    } catch (JacksonException | IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
    } catch (PipelineExecutionException e) {
      HttpStatus status =
          e.reason() == PipelineExecutionException.Reason.GLOBAL_DEADLINE_EXCEEDED
              ? HttpStatus.GATEWAY_TIMEOUT
              : HttpStatus.SERVICE_UNAVAILABLE;
      return ResponseEntity.status(status)
          .body(Map.of("error", "pipeline_failed", "reason", e.reason().name()));
    }
  }

  private static <Q, ID> void requirePipeline(
      KervanPipelineRegistry registry, HttpPipelineBinding<Q, ID> binding) {
    registry.pipeline(binding.pipelineName(), binding.queryType(), binding.idType());
  }

  private static <Q, ID> PipelineResult<ID> runPipeline(
      KervanPipelineRegistry registry,
      HttpPipelineBinding<Q, ID> binding,
      JsonMapper jsonMapper,
      String body) {
    Q query = jsonMapper.readValue(body, binding.queryType());
    if (query == null) {
      throw new IllegalArgumentException("query must not be null");
    }
    return registry
        .pipeline(binding.pipelineName(), binding.queryType(), binding.idType())
        .execute(query);
  }
}
