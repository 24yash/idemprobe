package dev.idemprobe.demo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record ReservationRequest(
        @NotBlank String sku,
        @Positive int quantity) {
}
