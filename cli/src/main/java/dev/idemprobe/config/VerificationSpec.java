package dev.idemprobe.config;

import java.math.BigDecimal;
import java.net.URI;

public record VerificationSpec(String method, URI url, String valueAt, BigDecimal expectedValue) {
}
