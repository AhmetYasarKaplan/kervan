# Qdrant adapter

`kervan-qdrant` turns an ordered result from Qdrant's official Java client into
one Kervan provider list. Qdrant performs vector, filtered, or native hybrid
retrieval within its collection; Kervan combines that list with independently
invoked providers by canonical application ID.

Add `kervan-qdrant` alongside `kervan-core` to the application. Create and own a
`QdrantClient` for the lifetime of the application. For example, with a cosine
collection whose payload contains a `canonicalId` string:

```java
import static io.qdrant.client.QueryFactory.nearest;

import io.github.ahmetyasarkaplan.kervan.api.ScoreDirection;
import io.github.ahmetyasarkaplan.kervan.qdrant.QdrantCandidateRetriever;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import java.util.List;

record ProductQuery(List<Float> vector) {}

var vectorRetriever =
    QdrantCandidateRetriever.<ProductQuery, String>builder(client)
        .requestFactory(
            (query, context) ->
                QueryPoints.newBuilder()
                    .setCollectionName("products")
                    .setQuery(nearest(query.vector()))
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true)))
        .idMapper(point -> point.getPayloadOrThrow("canonicalId").getStringValue())
        .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
        .build();
```

Register `vectorRetriever` using a named `RetrieverSpec` or a Spring Boot
`CandidateRetriever` bean. The adapter overrides the query's top-level limit with
`RetrievalContext.candidateLimit()` and passes its remaining time budget to the
Qdrant client. Its future is also awaited only within that budget. The client is
caller-owned and is not closed by the adapter.

The request factory can build any native `QueryPoints` query, including filters,
named vectors, and prefetches with Qdrant's own RRF or DBSF fusion. Set any
prefetch limits large enough for the intended final window; Kervan only controls
the top-level limit. Native Qdrant hybrid fusion is one provider result in Kervan,
not a replacement for Kervan's cross-provider fusion.

## Score direction

Qdrant's native score may be a higher-is-better similarity or a lower-is-better
distance depending on the collection metric and query. Declare the direction
explicitly. For Euclidean distance use
`.scoreDirection(ScoreDirection.LOWER_IS_BETTER)`. The adapter negates the
distance before placing it in `SourceCandidate.score()`; the original result
order stays unchanged. `SourceContribution.score()` therefore contains the
oriented value. If the native distance is needed for explanation or diagnostics,
copy it into a namespaced attribute through `attributesMapper`. This prevents
score-based fusion such as
`WeightedMinMaxFusion` from reversing relevance. `ReciprocalRankFusion` uses
ranks and does not compare raw source scores.

`attributesMapper` can copy small payload-derived features into candidate
attributes when required for application ranking. Request those payload fields
from Qdrant explicitly. The adapter reports sanitized transport, remote,
timeout, and invalid-response failures without retaining server messages.

The Testcontainers integration test creates cosine and Euclidean collections,
queries real points, checks canonical ID mapping and score direction, and combines
Qdrant with an independent recommendation source.

See [Qdrant's query API](https://qdrant.tech/documentation/search/hybrid-queries/)
for native query options and [its score metric behavior](https://qdrant.tech/documentation/search/)
when choosing the score direction.
