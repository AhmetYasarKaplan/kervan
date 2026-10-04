package io.github.ahmetyasarkaplan.kervan.qdrant;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.QueryFactory.nearest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.ScoreDirection;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.grpc.Context;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.QueryResponse;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.PointsGrpc;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class QdrantCandidateRetrieverTransportTest {
  private final CountDownLatch received = new CountDownLatch(1);
  private final CountDownLatch cancelled = new CountDownLatch(1);
  private final AtomicReference<QueryPoints> request = new AtomicReference<>();
  private final AtomicReference<Long> deadlineNanos = new AtomicReference<>();
  private volatile BiConsumer<QueryPoints, StreamObserver<QueryResponse>> handler =
      (query, response) -> {};
  private Server server;
  private QdrantClient client;

  @BeforeEach
  void startServer() throws Exception {
    server =
        ServerBuilder.forPort(0)
            .addService(
                new PointsGrpc.PointsImplBase() {
                  @Override
                  public void query(QueryPoints query, StreamObserver<QueryResponse> response) {
                    request.set(query);
                    if (Context.current().getDeadline() != null) {
                      deadlineNanos.set(
                          Context.current().getDeadline().timeRemaining(TimeUnit.NANOSECONDS));
                    }
                    ((ServerCallStreamObserver<QueryResponse>) response)
                        .setOnCancelHandler(cancelled::countDown);
                    received.countDown();
                    handler.accept(query, response);
                  }
                })
            .build()
            .start();
    client =
        new QdrantClient(
            QdrantGrpcClient.newBuilder("127.0.0.1", server.getPort(), false, false).build());
  }

  @AfterEach
  void stopServer() throws InterruptedException {
    if (client != null) {
      client.close();
    }
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void propagatesLimitAndDeadlineAndCapsAnOversizedProviderList() {
    handler =
        (query, response) -> respond(response, point(3, 0.9f), point(1, 0.8f), point(2, 0.7f));
    Duration budget = Duration.ofSeconds(5);

    var results = retriever().retrieve(List.of(1.0f), RetrievalContext.of(2, budget));

    assertThat(request.get().getLimit()).isEqualTo(2);
    assertThat(deadlineNanos.get()).isPositive().isLessThanOrEqualTo(budget.toNanos());
    assertThat(results).extracting(SourceCandidate::id).containsExactly(3L, 1L);
    assertThat(results)
        .extracting(SourceCandidate::score)
        .containsExactly((double) 0.9f, (double) 0.8f);
    assertThatThrownBy(() -> results.clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThat(retriever().retrieve(List.of(1.0f), RetrievalContext.of(1, budget))).hasSize(1);
  }

  @Test
  void timesOutAndCancelsThePendingRpc() throws InterruptedException {
    assertThatThrownBy(
            () ->
                retriever().retrieve(List.of(1.0f), RetrievalContext.of(1, Duration.ofSeconds(1))))
        .isInstanceOfSatisfying(
            QdrantRetrievalException.class,
            failure ->
                assertThat(failure.reason()).isEqualTo(QdrantRetrievalException.Reason.TIMED_OUT))
        .hasMessage("Qdrant retrieval failed: TIMED_OUT")
        .hasNoCause();

    assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void preservesWorkerInterruptionAndCancelsThePendingRpc() throws InterruptedException {
    AtomicReference<QdrantRetrievalException> failure = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean();
    CountDownLatch completed = new CountDownLatch(1);
    var retriever = retriever();
    Thread worker =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    retriever.retrieve(
                        List.of(1.0f), RetrievalContext.of(1, Duration.ofSeconds(30)));
                  } catch (QdrantRetrievalException e) {
                    failure.set(e);
                    interrupted.set(Thread.currentThread().isInterrupted());
                  } finally {
                    completed.countDown();
                  }
                });
    try {
      assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
      worker.interrupt();
      assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(failure.get()).isNotNull().hasNoCause();
      assertThat(failure.get().reason()).isEqualTo(QdrantRetrievalException.Reason.INTERRUPTED);
      assertThat(interrupted.get()).isTrue();
      assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      worker.interrupt();
      worker.join(Duration.ofSeconds(5));
    }
  }

  @Test
  void sanitizesTransportFailureDetails() {
    handler =
        (query, response) ->
            response.onError(
                Status.UNAVAILABLE
                    .withDescription("private credential detail")
                    .asRuntimeException());

    assertThatThrownBy(
            () ->
                retriever().retrieve(List.of(1.0f), RetrievalContext.of(1, Duration.ofSeconds(5))))
        .isInstanceOfSatisfying(
            QdrantRetrievalException.class,
            failure ->
                assertThat(failure.reason())
                    .isEqualTo(QdrantRetrievalException.Reason.TRANSPORT_FAILURE))
        .hasMessage("Qdrant retrieval failed: TRANSPORT_FAILURE")
        .hasNoCause();
  }

  @Test
  void rejectsInvalidNativeScoresWithoutExposingTheResponse() {
    handler = (query, response) -> respond(response, point(123, Float.NaN));

    assertThatThrownBy(
            () ->
                retriever().retrieve(List.of(1.0f), RetrievalContext.of(1, Duration.ofSeconds(5))))
        .isInstanceOfSatisfying(
            QdrantRetrievalException.class,
            failure ->
                assertThat(failure.reason())
                    .isEqualTo(QdrantRetrievalException.Reason.INVALID_RESPONSE))
        .hasMessage("Qdrant retrieval failed: INVALID_RESPONSE")
        .hasNoCause();
  }

  private QdrantCandidateRetriever<List<Float>, Long> retriever() {
    return QdrantCandidateRetriever.<List<Float>, Long>builder(client)
        .requestFactory(
            (query, context) ->
                QueryPoints.newBuilder()
                    .setCollectionName("items")
                    .setQuery(nearest(query))
                    .setLimit(99))
        .idMapper(point -> point.getId().getNum())
        .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
        .build();
  }

  private static ScoredPoint point(long id, float score) {
    return ScoredPoint.newBuilder().setId(id(id)).setScore(score).build();
  }

  private static void respond(StreamObserver<QueryResponse> response, ScoredPoint... points) {
    response.onNext(QueryResponse.newBuilder().addAllResult(List.of(points)).build());
    response.onCompleted();
  }
}
