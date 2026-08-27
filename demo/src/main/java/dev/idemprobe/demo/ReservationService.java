package dev.idemprobe.demo;

public interface ReservationService {

    ReservationResponse reserve(String idempotencyKey, ReservationRequest request);

    int reservationCount();
}
