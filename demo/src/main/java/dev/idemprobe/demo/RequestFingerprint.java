package dev.idemprobe.demo;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.springframework.stereotype.Component;

@Component
public class RequestFingerprint {

    private final ObjectWriter canonicalWriter;

    public RequestFingerprint(ObjectMapper objectMapper) {
        canonicalWriter = objectMapper.writerFor(CanonicalReservationRequest.class);
    }

    public String forRequest(ReservationRequest request) {
        byte[] canonicalPayload;
        try {
            canonicalPayload = canonicalWriter.writeValueAsString(
                    new CanonicalReservationRequest(request.sku(), request.quantity()))
                    .getBytes(UTF_8);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not canonicalize reservation request", failure);
        }

        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalPayload));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    @JsonPropertyOrder({"sku", "quantity"})
    private record CanonicalReservationRequest(
            @JsonProperty("sku") String sku,
            @JsonProperty("quantity") int quantity) {
    }
}
