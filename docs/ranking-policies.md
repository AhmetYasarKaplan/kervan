# Deterministic ranking policies

Policies reorder or filter candidates after fusion and optional model reranking. They preserve each
candidate's scores, attributes, source contributions, and reranking metadata. They do not fetch or
inject candidates. The application supplies IDs and group keys; Kervan does not define a document
schema or a business taxonomy.

## Pin existing candidates

`PinnedRankingPolicy<Q, ID>` moves matching pins to the front in the requested order. Candidates not
pinned retain their input order. Absent pins are skipped, so a pin does not guarantee inclusion unless
the candidate was retrieved and retained in the ranked window. Null or duplicate pin IDs are rejected.

```java
var fixedPins = new PinnedRankingPolicy<String, String>(List.of("featured-42", "featured-7"));

var queryPins = new PinnedRankingPolicy<String, String>(
    query -> query.equals("cameras") ? List.of("featured-42") : List.of());
```

The fixed-list constructor snapshots its configuration. The query callback is evaluated on each
application and must return a non-null list. IDs use equality and hashing; they need not be strings or
sortable values.

## Limit candidates per group

`GroupQuotaPolicy<Q, ID, G>` walks the ranked window in order and keeps up to a positive maximum per
group. It continues scanning past rejected candidates to fill the result from later eligible groups.
For example, keeping one candidate per group turns `a(one), b(one), c(two), d(three)` into `a, c, d`.

```java
var quota = new GroupQuotaPolicy<String, String, Object>(
    candidate -> candidate.attributes().get("brand"), 2);
```

The application chooses how to extract its group key, including whether to use top-level attributes,
a particular source contribution, or application-owned data. Group keys use equality and hashing.
Every null key belongs to one shared missing-group bucket with the same cap as other groups. Return
an application-specific key instead if missing values need different treatment.

## Compose policies before applying the final limit

`CompositeRankingPolicy<Q, ID>` applies policies in list order, retains the full available window
between policies, then applies the caller's final output limit once. Each intermediate policy receives
an immutable candidate list, a limit at least as large as the original input window, and the current
remaining execution budget. A chain cannot recover candidates filtered out by an earlier policy.

```java
RankingPolicy<String, String> finalPolicy = new CompositeRankingPolicy<>(List.of(
    new PinnedRankingPolicy<String, String>(List.of("featured-42")),
    new GroupQuotaPolicy<String, String, Object>(
        candidate -> candidate.attributes().get("brand"), 1)));

// Attach this finalPolicy to an existing RankingPipeline builder:
builder.policy(finalPolicy);
```

Order is meaningful: pins followed by a quota count the pinned candidates against their groups' caps.
A quota followed by pins may already have removed a requested pin. With input
`a(one), b(one), c(two), d(three)`, pinning `b` then keeping one per group yields `b, c, d`; reversing
those policies yields `a, c, d` because `b` was removed before pinning. For a final limit of three, the
chain keeps `d` available even though it starts below the top three.

Every intermediate output must contain unique IDs and unchanged candidate values from its immediate
input. Composition rejects injection, rewriting, duplication, and resurrection at the step where they
occur, even if a later policy would hide the violation. An empty policy list acts as an identity policy
with the final limit. Outputs are immutable snapshots; nested application attribute values retain the
API's shallow-copy semantics.

All policies require non-null candidate lists and contexts, reject null candidates and duplicate input
IDs, and return valid empty results for empty input. They use no executor and add no timeout or
fallback behavior. Application callbacks should remain deterministic, respect their surrounding
pipeline's budget, and be safe for concurrent calls when the pipeline is shared.
