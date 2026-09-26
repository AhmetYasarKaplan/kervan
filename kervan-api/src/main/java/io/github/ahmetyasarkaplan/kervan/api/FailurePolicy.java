package io.github.ahmetyasarkaplan.kervan.api;

/** Determines whether an optional provider failure can produce a partial pipeline result. */
public enum FailurePolicy {
  /** Return usable results when optional providers fail; required providers remain mandatory. */
  PARTIAL_RESULTS,

  /** Fail as soon as any provider fails or exceeds its time budget. */
  FAIL_FAST
}
