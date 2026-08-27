package dev.idemprobe.demo;

import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        String sku,
        int quantity) {
}
