# Cross-provider fusion

## Decision

Kervan offers reciprocal rank fusion for independently operated providers whose
score scales may differ. For each canonical candidate ID, it sums
`1 / (k + rank)` over the provider lists containing that ID, with a positive
rank constant `k` and one-based source ranks. This is the pipeline default with
`k = 60`. Without an explicit fusion limit, it admits up to the combined
configured provider candidate capacity. Raw provider scores remain in source
contributions but do not affect this fused score.

Weighted min-max fusion is available when score magnitudes within each provider
are meaningful. It normalizes each provider's returned, deduplicated scores to
`[0, 1]` and sums them with positive finite per-provider weights. An omitted
weight is 1.0. When all returned scores from a provider are equal, each is
normalized to 1.0. Weights do not affect reciprocal rank fusion.

Both strategies use provider registration order rather than completion order.
Equal fused scores are resolved by best source rank, then source ranks in
registration order, then first appearance. Original ranks, scores, and source
provenance are retained. Provider limits apply before fusion; a separate fusion
limit caps its output. Native hybrid retrieval within one search engine remains
that provider's responsibility.

## Consequences

Reciprocal rank fusion is appropriate when providers expose incomparable scores.
Weighted min-max fusion is sensitive to the candidates in each returned window:
changing a provider limit can change normalized scores. Applications should
choose weights and limits based on their own evaluation data. Equal-score
providers still contribute to the weighted result rather than disappearing.
