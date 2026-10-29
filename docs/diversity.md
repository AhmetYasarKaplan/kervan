# Maximal marginal relevance diversity

`MaximalMarginalRelevanceRanker<Q, ID>` is an optional core ranker that selects relevant candidates
while penalizing similarity to results already selected. The application supplies both functions;
Kervan imposes no embedding model, document schema, category system, or remote client.

For every selection it maximizes:

```text
lambda * relevance(candidate)
    - (1 - lambda) * max(similarity(candidate, selectedCandidate))
```

The similarity term is zero before the first selection. Relevance and similarity must be finite and
in `[0, 1]`; normalize raw fusion or model scores explicitly before using them. A larger lambda favors
relevance. `lambda = 1` ranks only by relevance and skips similarity callbacks. `lambda = 0` chooses
the first input candidate initially, then chooses the least similar remaining candidate. Equal
utilities always retain input order, so opaque IDs need only support equality and hashing.

## Configure a bounded diversity stage

```java
import io.github.ahmetyasarkaplan.kervan.api.RankerFailurePolicy;
import io.github.ahmetyasarkaplan.kervan.core.MaximalMarginalRelevanceRanker;
import io.github.ahmetyasarkaplan.kervan.core.RankerSpec;
import java.time.Duration;
import java.util.Map;

// Application-owned normalized features, hydrated before ranking.
var relevance = Map.of("camera-a", 1.0, "camera-b", 0.95, "tripod", 0.8);
var groups = Map.of("camera-a", "camera", "camera-b", "camera", "tripod", "accessory");
var mmr = new MaximalMarginalRelevanceRanker<String, String>(
    0.5,
    50,
    candidate -> relevance.get(candidate.id()),
    (candidate, selected) ->
        groups.get(candidate.id()).equals(groups.get(selected.id())) ? 1.0 : 0.0);

// Attach to an otherwise configured RankingPipeline.Builder<String, String>.
builder.ranker(new RankerSpec<>(
    mmr, 50, 20, Duration.ofMillis(100), RankerFailurePolicy.KEEP_FUSED));
```

This example illustrates category overlap; applications can supply cosine-based embedding
similarity, feature overlap, or another normalized similarity measure. Missing feature data should
have an explicit application policy. The example maps must cover every candidate offered to MMR.
Callbacks receive original candidates, including source contributions and application attributes.
Similarity is always called as `similarity(candidate, selectedCandidate)`; supply a symmetric
function for standard MMR. Callbacks should be deterministic and safe for concurrent executions
when sharing a pipeline. The ranker does not cache features across calls.

With relevance `a=1, b=.95, c=.8`, similarities `a/b=.95, a/c=.1, b/c=.1` and lambda `.5`, the first
selection is `a`. Next, `b` has utility `0` and `c` has utility `.35`, so the order starts `a, c`.

The constructor's positive `maxCandidates` is also enforced for direct calls. Oversized inputs are
rejected before evaluating either callback; they are never silently truncated. Configure the
`RankerSpec.inputLimit` at or below this bound. Relevance is evaluated once for every input,
including with lambda zero. Similarities are evaluated only as needed, with incremental maxima,
using `O(n * k)` work and `O(n)` additional space for `n` inputs and `k` selected outputs. No pairs
are evaluated after reaching `context.candidateLimit()`.

## Scores, deadlines, and final policies

MMR changes ordering and selection, preserving the original `RankedCandidate` objects, scores,
reranking scores, source contributions and typed attributes. Its selection utility is not a model
score and is never stored in `score()` or `rerankingScore()`. Consequently, result scores need not
decrease in MMR order. Returned lists are immutable snapshots. Empty inputs invoke no callbacks;
null candidates and duplicate input IDs are rejected.

Each callback value is checked when evaluated. Invalid values or runtime callback failures throw a
sanitized `IllegalStateException` without retaining the callback cause. Relevance is still validated
with lambda zero; unused similarities with lambda one are not evaluated. Thread interruption and
expired `context.remainingBudget()` stop work between callbacks and loop iterations without
returning a partial selection. An interrupt flag remains set. Callbacks must themselves cooperate
with cancellation because this local ranker cannot preempt a blocked callback.

The ranker creates no executor, retries, or fallback. The surrounding [bounded ranking stage](reranking.md)
owns deadlines and the configured `KEEP_FUSED` or `FAIL` behavior. `KEEP_FUSED` can return the
original fused ordering when diversity fails, so diversity is best effort under that failure policy.

MMR runs before [final ranking policies](ranking-policies.md). Pins can move selected candidates to
the front and quotas can remove them, changing the diversity outcome. A pin cannot recover a
candidate omitted by the MMR output limit; retain a sufficiently wide ranked window before applying
final policies and the final result limit. For MMR order `a(one), c(two), b(one)`, pinning `b` then
keeping one per group yields `b, c`; applying the quota before pins yields `a, c` because `b` has
already been removed. Hard per-group quotas and MMR novelty penalties solve different constraints
and can be configured together deliberately.

To diversify after a model reranker, retain a wider model-scored window and use
the same selector as a final policy with `builder.policy(mmr::rank)`. MMR preserves
candidate values, so this satisfies the policy contract and selects up to the
final policy limit. Supply an explicitly normalized relevance function for the
model scores; do not assume every model's numeric scale is already in [0, 1].

The selection rule follows Carbonell and Goldstein's
[1998 MMR paper](https://doi.org/10.1145/290941.291025). Normalization, bounded execution and preserving
candidate score provenance are Kervan's explicit adapter contracts.
