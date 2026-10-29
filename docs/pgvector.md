# pgvector adapter

`kervan-pgvector` executes application-owned SQL through a caller-owned `javax.sql.DataSource`.
Production dependencies contain only `kervan-api`; choose your own PostgreSQL driver and connection pool.
The adapter does not create tables, indexes, embeddings, transactions, or a universal SQL query language.
It works with dense or native hybrid SQL as long as the query returns ordered canonical candidates.

```kotlin
implementation("io.github.ahmetyasarkaplan.kervan:kervan-pgvector:<kervan-version>")
// Your application supplies a PostgreSQL JDBC driver and DataSource.
```

Prepare and bind native SQL, then map its current result row:

```java
record Query(String vectorLiteral, String tenant) {}

// dataSource is configured and owned by your application.
try (var retriever = PgvectorCandidateRetriever.<Query, String>builder(dataSource)
    .statementFactory((connection, query, context) -> {
      var statement = connection.prepareStatement("""
          SELECT canonical_id, tenant,
                 embedding <=> CAST(? AS vector) AS distance
          FROM products
          WHERE tenant = ?
          ORDER BY distance ASC
          LIMIT ?
          """);
      try {
        statement.setString(1, query.vectorLiteral());
        statement.setString(2, query.tenant());
        statement.setInt(3, context.candidateLimit());
        return statement;
      } catch (SQLException failure) {
        statement.close();
        throw failure;
      }
    })
    .candidateMapper(row -> new SourceCandidate<>(
        row.getString("canonical_id"), row.getDouble("distance"),
        Map.of("tenant", row.getString("tenant"))))
    .scoreDirection(ScoreDirection.LOWER_IS_BETTER)
    .build()) {
  var candidates = retriever.retrieve(
      new Query("[1,0,0]", "store-42"),
      RetrievalContext.of(100, Duration.ofMillis(500)));
}
```

Import the adapter types from `io.github.ahmetyasarkaplan.kervan.pgvector`, API types from
`io.github.ahmetyasarkaplan.kervan.api`, and standard JDBC/time/collection classes.
Always bind user values with parameters. The callback receives a fresh remaining time budget after
connection acquisition; prepare promptly. If the callback throws before returning a prepared statement,
it must close that statement. The adapter closes every returned statement, result set, and borrowed connection.
Do not retain invocation resources or advance the cursor in the candidate mapper. JDBC `getDouble`
returns zero for SQL NULL: call `wasNull()` and reject it if your score expression can produce NULL.

Use canonical IDs shared by your other providers so fusion recognizes the same item across sources.
Attributes are copied into `SourceCandidate` and remain available in the provider contributions after
fusion. Scores must be finite. `ScoreDirection` is required: cosine distance (`<=>`) and L2 distance
(`<->`) use `LOWER_IS_BETTER`; SQL `1 - cosine_distance` uses `HIGHER_IS_BETTER`. Distance is negated
into Kervan's higher-is-better score convention. Native row order is retained, so order SQL accordingly.
The adapter enforces both JDBC `setMaxRows` and an output cap; putting `LIMIT ?` in SQL also avoids
unnecessary database work. Native full-text/vector joins, filters, reranking, and hybrid SQL remain
application-owned. pgvector documents these operators and SQL techniques in its
[official README](https://github.com/pgvector/pgvector#querying).

Register the retriever under one provider name using `RetrieverSpec.required` or `optional`. Native
hybrid SQL is one provider result. Add an independent recommendations or search provider separately;
Kervan then fuses by canonical ID. Close the pipeline before closing the retriever. The pipeline does
not own registered retrievers; close this adapter explicitly, for example as an application bean with
a destroy method, and close the data source at the application's lifecycle boundary.

The retriever owns reusable virtual-thread executors for JDBC work and asynchronous cancellation.
The caller wait is bounded over acquisition, preparation, execution, mapping, and resource closure.
It applies the remaining budget to JDBC network timeout when supported and a rounded-up whole-second
query timeout, then also enforces a nanosecond future deadline. It never changes the data source's
global login timeout. Timeout or interruption requests `Future.cancel`, `Statement.cancel`, and
`Connection.abort` without waiting for driver cleanup. `close()` rejects new calls, requests active
cancellation, and shuts down executors without waiting on a blocked driver. Late-acquired connections
are still closed or aborted. Configure your pool acquisition, PostgreSQL socket/connect, and server
statement timeouts as well: a driver ignoring cancellation can keep work or cleanup alive after the
caller has returned, and JDBC cannot guarantee immediate server termination.

`PgvectorRetrievalException.reason()` supplies sanitized categories. SQL, query values, credentials,
driver messages, causes, and suppressed JDBC cleanup failures do not cross the adapter boundary.

The module's Docker integration tests pin `pgvector/pgvector:0.8.7-pg17` and test-only PgJDBC
`42.7.14`, verifying real cosine/L2 retrieval, bound tenant filtering, canonical fusion with an
independent provider, slow-query cancellation, and sanitized failures. Run:

```powershell
.\gradlew.bat :kervan-pgvector:test
```
