package io.github.ahmetyasarkaplan.kervan.api;

/** Sanitized outcome of the ranking stage. */
public enum RankingStatus {
  /** No ranking outcome was supplied by the result producer. */
  NOT_REPORTED,
  /** Ranking completed successfully with candidates. */
  SUCCESS,
  /** Ranking completed successfully without candidates. */
  EMPTY,
  /** Ranking failed or returned malformed candidates. */
  FAILED,
  /** Ranking exceeded its stage or remaining pipeline budget. */
  TIMED_OUT,
  /** Ranking was not invoked because the pipeline budget was exhausted. */
  SKIPPED_NO_BUDGET
}
