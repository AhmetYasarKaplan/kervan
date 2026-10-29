package io.github.ahmetyasarkaplan.kervan.api;

/** Behavior when a bounded ranker fails or has no available time. */
public enum RankerFailurePolicy {
  /** Fail the pipeline. */
  FAIL,
  /** Continue with the original fused ordering and scores. */
  KEEP_FUSED
}
