package dev.idemprobe.demo;

import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "idemprobe.demo.mode", havingValue = "fixed")
public class IdempotentReservationService implements ReservationService {

    private final JdbcTemplate jdbc;
    private final RequestFingerprint requestFingerprint;
    private final ObjectMapper objectMapper;

    public IdempotentReservationService(
            JdbcTemplate jdbc,
            RequestFingerprint requestFingerprint,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.requestFingerprint = requestFingerprint;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse reserve(String idempotencyKey, ReservationRequest request) {
        String requestHash = requestFingerprint.forRequest(request);
        int inserted = jdbc.update(
                """
                INSERT INTO idempotency_record (idempotency_key, request_hash)
                VALUES (?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """,
                idempotencyKey,
                requestHash);

        if (inserted == 1) {
            return createReservation(idempotencyKey, request);
        }

        StoredIdempotencyRecord stored = jdbc.queryForObject(
                """
                SELECT request_hash, response_body
                FROM idempotency_record
                WHERE idempotency_key = ?
                """,
                (resultSet, rowNumber) -> new StoredIdempotencyRecord(
                        resultSet.getString("request_hash"),
                        resultSet.getString("response_body")),
                idempotencyKey);

        if (!requestHash.equals(stored.requestHash())) {
            throw new IdempotencyConflictException(idempotencyKey);
        }
        return deserializeResponse(stored.responseBody());
    }

    @Override
    public int reservationCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class);
        return count == null ? 0 : count;
    }

    private ReservationResponse createReservation(
            String idempotencyKey, ReservationRequest request) {
        ReservationResponse response = new ReservationResponse(
                UUID.randomUUID(), request.sku(), request.quantity());
        jdbc.update(
                "INSERT INTO reservation (id, sku, quantity) VALUES (?, ?, ?)",
                response.reservationId(),
                response.sku(),
                response.quantity());

        String responseBody = serializeResponse(response);
        int updated = jdbc.update(
                """
                UPDATE idempotency_record
                SET response_body = ?
                WHERE idempotency_key = ?
                """,
                responseBody,
                idempotencyKey);
        if (updated != 1) {
            throw new IllegalStateException("Could not persist idempotent response");
        }
        return response;
    }

    private String serializeResponse(ReservationResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not serialize reservation response", failure);
        }
    }

    private ReservationResponse deserializeResponse(String responseBody) {
        if (responseBody == null) {
            throw new IllegalStateException("Stored idempotent response is missing");
        }
        try {
            return objectMapper.readValue(responseBody, ReservationResponse.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not deserialize stored reservation response", failure);
        }
    }

    private record StoredIdempotencyRecord(String requestHash, String responseBody) {
    }
}
