package io.github.ahmetyasarkaplan.kervan.pgvector;

import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps one current JDBC row to a canonical ID, native score, and application attributes.
 *
 * @param <ID> canonical identifier type
 */
@FunctionalInterface
public interface PgvectorCandidateMapper<ID> {
  /**
   * Maps the current row without advancing the cursor. The score is oriented by the retriever's
   * explicit score direction after mapping; SQL ordering remains application-owned.
   *
   * @param row result set positioned on a row, never retain it
   * @return candidate carrying the raw native similarity or distance
   * @throws SQLException if a column cannot be read
   */
  SourceCandidate<ID> map(ResultSet row) throws SQLException;
}
