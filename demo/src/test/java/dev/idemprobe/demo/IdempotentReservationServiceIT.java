package dev.idemprobe.demo;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "idemprobe.demo.mode=fixed")
@Import(ConcurrentPostBarrierConfiguration.class)
@Testcontainers
class IdempotentReservationServiceIT {

    private static final int DUPLICATE_COUNT = 20;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .waitingFor(Wait.forListeningPort());

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConcurrentPostBarrier serverBarrier;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE TABLE idempotency_record, reservation");
    }

    @Test
    void fixedModeCreatesOneReservationAndOneStableIdentity() {
        List<ResponseEntity<ReservationResponse>> responses = concurrently(
                DUPLICATE_COUNT,
                () -> postReservationEntity("same-key", "PHONE", 1));

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().sku()).isEqualTo("PHONE");
            assertThat(response.getBody().quantity()).isEqualTo(1);
        });
        assertThat(responses)
                .extracting(ResponseEntity::getBody)
                .extracting(ReservationResponse::reservationId)
                .containsOnly(responses.getFirst().getBody().reservationId());
        assertThat(getReservationCount()).isEqualTo(1);
        assertThat(getIdempotencyRecordCount()).isEqualTo(1);
    }

    @Test
    void repeatedRequestAfterCommitReturnsStoredResponse() {
        ReservationResponse first = postReservation("same-key", "PHONE", 1);

        ReservationResponse repeated = postReservation("same-key", "PHONE", 1);

        assertThat(repeated).isEqualTo(first);
        assertThat(getReservationCount()).isEqualTo(1);
        assertThat(getIdempotencyRecordCount()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentPayloadReturns422WithoutSecondSideEffect() {
        postReservation("same-key", "PHONE", 1);

        ResponseEntity<String> mismatch =
                postReservationRaw("same-key", "LAPTOP", 1);

        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(getReservationCount()).isEqualTo(1);
        assertThat(getIdempotencyRecordCount()).isEqualTo(1);
    }

    @Test
    void concurrentMismatchesAfterCommitAllReturn422WithoutSideEffects() {
        ReservationResponse original = postReservation("same-key", "PHONE", 1);

        List<ResponseEntity<String>> mismatches = concurrently(
                DUPLICATE_COUNT,
                () -> postReservationRaw("same-key", "LAPTOP", 1));

        assertThat(mismatches)
                .extracting(ResponseEntity::getStatusCode)
                .containsOnly(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(getReservationCount()).isEqualTo(1);
        assertThat(getIdempotencyRecordCount()).isEqualTo(1);
        assertThat(postReservation("same-key", "PHONE", 1)).isEqualTo(original);
    }

    @Test
    void canonicalPayloadFingerprintUsesSkuThenQuantityAndLowercaseSha256() {
        postReservation("same-key", "PHONE", 1);

        String storedHash = jdbc.queryForObject(
                "SELECT request_hash FROM idempotency_record WHERE idempotency_key = ?",
                String.class,
                "same-key");

        assertThat(storedHash)
                .isEqualTo("398c984932d29b33330edbf571490e9c8c11bf0849f70dd98e2d7367a45e237c");
    }

    private ReservationResponse postReservation(String key, String sku, int quantity) {
        ResponseEntity<ReservationResponse> response = postReservationEntity(key, sku, quantity);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private ResponseEntity<ReservationResponse> postReservationEntity(
            String key, String sku, int quantity) {
        HttpEntity<ReservationRequest> entity =
                new HttpEntity<>(new ReservationRequest(sku, quantity), requestHeaders(key));
        return rest.exchange(
                "/reservations", HttpMethod.POST, entity, ReservationResponse.class);
    }

    private ResponseEntity<String> postReservationRaw(String key, String sku, int quantity) {
        HttpEntity<ReservationRequest> entity =
                new HttpEntity<>(new ReservationRequest(sku, quantity), requestHeaders(key));
        return rest.exchange("/reservations", HttpMethod.POST, entity, String.class);
    }

    private HttpHeaders requestHeaders(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private int getReservationCount() {
        JsonNode response = rest.getForObject("/reservations/count", JsonNode.class);
        return response.required("count").asInt();
    }

    private int getIdempotencyRecordCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class);
        return count == null ? 0 : count;
    }

    private <T> List<T> concurrently(int count, Callable<T> operation) {
        CountDownLatch clientsReady = new CountDownLatch(count);
        CountDownLatch startClients = new CountDownLatch(1);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<T>> futures = new ArrayList<>(count);
        boolean barrierArmed = false;

        try {
            serverBarrier.arm(count);
            barrierArmed = true;
            for (int index = 0; index < count; index++) {
                futures.add(executor.submit(() -> {
                    clientsReady.countDown();
                    if (!startClients.await(5, SECONDS)) {
                        throw new AssertionError("client start gate timed out");
                    }
                    return operation.call();
                }));
            }

            assertThat(clientsReady.await(5, SECONDS)).isTrue();
            startClients.countDown();
            assertThat(serverBarrier.awaitAllEntered(5, SECONDS)).isTrue();
            assertThat(serverBarrier.observedRequests()).isEqualTo(count);
            serverBarrier.release();
            return getAll(futures);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrency test interrupted", interrupted);
        } finally {
            startClients.countDown();
            if (barrierArmed) {
                serverBarrier.release();
            }
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            try {
                awaitTermination(executor);
            } finally {
                if (barrierArmed) {
                    serverBarrier.disarm();
                }
            }
        }
    }

    private <T> List<T> getAll(List<Future<T>> futures) {
        long deadline = System.nanoTime() + SECONDS.toNanos(5);
        return futures.stream().map(future -> get(future, deadline)).toList();
    }

    private <T> T get(Future<T> future, long deadline) {
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("concurrent requests timed out");
            }
            return future.get(remaining, NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("future wait interrupted", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new AssertionError("concurrent request failed", failure);
        }
    }

    private void awaitTermination(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(5, SECONDS)) {
                throw new AssertionError("client executor did not terminate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("executor shutdown interrupted", interrupted);
        }
    }
}
