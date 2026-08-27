package dev.idemprobe.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
@Testcontainers
class VulnerableReservationServiceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .waitingFor(Wait.forListeningPort());

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE TABLE idempotency_record, reservation");
    }

    @Test
    void vulnerableModeCreatesOneReservationPerDuplicate() {
        List<ReservationResponse> responses = IntStream.range(0, 20)
                .parallel()
                .mapToObj(index -> postReservation("same-key", "PHONE", 1))
                .toList();

        assertThat(responses)
                .allSatisfy(response -> {
                    assertThat(response.reservationId()).isNotNull();
                    assertThat(response.sku()).isEqualTo("PHONE");
                    assertThat(response.quantity()).isEqualTo(1);
                });
        assertThat(responses).extracting(ReservationResponse::reservationId).doesNotHaveDuplicates();
        assertThat(getReservationCount()).isEqualTo(20);
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

    private ReservationResponse postReservation(String key, String sku, int quantity) {
        HttpEntity<ReservationRequest> entity =
                new HttpEntity<>(new ReservationRequest(sku, quantity), requestHeaders(key));
        return rest.exchange(
                "/reservations", HttpMethod.POST, entity, ReservationResponse.class).getBody();
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
}
