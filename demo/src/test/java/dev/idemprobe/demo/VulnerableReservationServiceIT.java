package dev.idemprobe.demo;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
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
        properties = "idemprobe.demo.mode=vulnerable")
@Import(ConcurrentPostBarrierConfiguration.class)
@Testcontainers
class VulnerableReservationServiceIT {

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
    void vulnerableModeCreatesOneReservationPerDuplicate() {
        CountDownLatch clientsReady = new CountDownLatch(DUPLICATE_COUNT);
        CountDownLatch startClients = new CountDownLatch(1);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<ResponseEntity<ReservationResponse>>> futures =
                new ArrayList<>(DUPLICATE_COUNT);
        boolean barrierArmed = false;

        try {
            serverBarrier.arm(DUPLICATE_COUNT);
            barrierArmed = true;
            for (int index = 0; index < DUPLICATE_COUNT; index++) {
                futures.add(executor.submit(() -> {
                    clientsReady.countDown();
                    if (!startClients.await(5, SECONDS)) {
                        throw new AssertionError("client start gate timed out");
                    }
                    return postReservationEntity("same-key", "PHONE", 1);
                }));
            }

            assertThat(clientsReady.await(5, SECONDS)).isTrue();
            startClients.countDown();
            assertThat(serverBarrier.awaitAllEntered(5, SECONDS)).isTrue();
            assertThat(serverBarrier.observedRequests()).isEqualTo(DUPLICATE_COUNT);
            serverBarrier.release();

            List<ResponseEntity<ReservationResponse>> responses =
                    getAll(futures);
            assertThat(responses).allSatisfy(response -> {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().reservationId()).isNotNull();
                assertThat(response.getBody().sku()).isEqualTo("PHONE");
                assertThat(response.getBody().quantity()).isEqualTo(1);
            });
            assertThat(responses)
                    .extracting(ResponseEntity::getBody)
                    .extracting(ReservationResponse::reservationId)
                    .doesNotHaveDuplicates();
            assertThat(getReservationCount()).isEqualTo(DUPLICATE_COUNT);
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

    @Test
    void postReturnsCreatedReservationAndCountReflectsTheSideEffect() {
        HttpHeaders headers = requestHeaders("a-key");
        HttpEntity<ReservationRequest> entity =
                new HttpEntity<>(new ReservationRequest("TABLET", 2), headers);

        ResponseEntity<ReservationResponse> created = rest.exchange(
                "/reservations", HttpMethod.POST, entity, ReservationResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().reservationId()).isNotNull();
        assertThat(created.getBody().sku()).isEqualTo("TABLET");
        assertThat(created.getBody().quantity()).isEqualTo(2);
        assertThat(getReservationCount()).isEqualTo(1);
    }

    @Test
    void invalidReservationDoesNotCreateASideEffect() {
        ResponseEntity<String> invalid = postReservationRaw("a-key", "PHONE", 0);

        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getReservationCount()).isZero();
    }

    @Test
    void blankIdempotencyKeyDoesNotCreateASideEffect() {
        ResponseEntity<String> invalid = postReservationRaw("   ", "PHONE", 1);

        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getReservationCount()).isZero();
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
