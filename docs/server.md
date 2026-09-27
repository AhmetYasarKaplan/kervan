# HTTP server adapter

`kervan-server` exposes configured pipelines from a Spring Boot application over
HTTP. It delegates execution to the same `KervanPipelineRegistry` used by embedded
applications; retrieval, fusion, ranking, policies, deadlines, and failure
handling stay in the library modules.

Add the module alongside your provider implementations. Within this repository:

```kotlin
dependencies {
  implementation(project(":kervan-server"))
}
```

Configure a named pipeline as described in the [Spring Boot guide](spring-boot.md),
then explicitly allow that pipeline to be called over HTTP:

```java
@Bean
HttpPipelineBinding<ProductQuery, String> discoveryHttpBinding() {
    return new HttpPipelineBinding<>("discovery", ProductQuery.class, String.class);
}
```

The application's query type must be readable from JSON. The `Class` tokens must
match the configured retriever beans' concrete query and canonical ID types.
Bindings are checked against the registry at startup; duplicate names or type
mismatches stop startup. A pipeline without a binding has no HTTP access. Request
bodies do not select Java classes.

Send a JSON query to `POST /kervan/pipelines/{name}/execute` with
`Content-Type: application/json`. A successful response serializes `PipelineResult`,
including ordered candidates, contributions, and sanitized provider outcomes.
An unknown binding returns `404`, malformed JSON returns `400`, and a pipeline
failure returns `503` or `504` with a sanitized reason code. Provider exception
messages and query contents are omitted from error responses.

The host Spring Boot application owns its HTTP authentication, authorization,
transport security, request size limits, and deployment configuration.
