# Deadline and failure policies

## Decision

Independent providers run concurrently on a virtual-thread executor owned and
reused by each pipeline. Callers close a pipeline when it is no longer needed.
The public call remains synchronous. The pipeline uses monotonic elapsed time for
a global deadline and each provider's timeout. A retriever receives the smaller
remaining budget and should apply it to its own client when possible.

`PARTIAL_RESULTS` tolerates optional provider failure or timeout when at least
one provider succeeds, including an empty result. Required failure and total
provider failure are fatal. `FAIL_FAST` makes every failure or timeout fatal.
Unfinished work is interrupted on a best-effort basis. Provider failures are
represented by status; raw exception messages do not enter public results.

At the global deadline, unfinished providers receive `DEADLINE_EXCEEDED`.
Finalizing completed results may use at most 25 ms beyond the deadline, capped
at one quarter of the configured timeout. Fusion and ranking are also cancelled
if this bounded budget expires.

## Consequences

Results stay deterministic because collection and fusion use provider registration
order, regardless of completion order. A timeout bounds how long Kervan waits; it
cannot guarantee that a remote client or server stops work. Applications must
close pipelines and configure provider-native timeouts where available.
