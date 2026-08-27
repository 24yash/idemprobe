package dev.idemprobe.demo;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Deliberately unsafe demo mode that creates a new reservation for every request,
 * regardless of the supplied idempotency key.
 */
@Service
@ConditionalOnProperty(name = "idemprobe.demo.mode", havingValue = "vulnerable")
public class VulnerableReservationService implements ReservationService {

    private final JdbcTemplate jdbc;

    public VulnerableReservationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ReservationResponse reserve(String idempotencyKey, ReservationRequest request) {
        UUID reservationId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO reservation (id, sku, quantity) VALUES (?, ?, ?)",
                reservationId,
                request.sku(),
                request.quantity());
        return new ReservationResponse(reservationId, request.sku(), request.quantity());
    }

    @Override
    public int reservationCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class);
        return count == null ? 0 : count;
    }
}
