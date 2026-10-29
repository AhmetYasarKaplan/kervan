package io.github.ahmetyasarkaplan.kervan.pgvector;

import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Creates and binds application-owned SQL on the adapter's invocation connection.
 *
 * @param <Q> application query type
 */
@FunctionalInterface
public interface PgvectorStatementFactory<Q> {
  /**
   * Creates a prepared query. Bind user input with JDBC parameters. The adapter closes the returned
   * statement and connection; the factory must close any statement if it fails before returning it.
   *
   * @param connection invocation-scoped connection, never retain it
   * @param query application query
   * @param context candidate limit and available invocation budget
   * @return bound prepared statement, owned by the adapter after return
   * @throws SQLException if query preparation or parameter binding fails
   */
  PreparedStatement create(Connection connection, Q query, RetrievalContext context)
      throws SQLException;
}
