package dev.idemprobe.config;

import java.net.URI;
import java.util.Map;

public record TargetSpec(String method, URI url, Map<String, String> headers, String body) {
    public TargetSpec {
        if (headers == null) {
            throw new IllegalArgumentException("target.headers must not be null");
        }
        if (headers.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
            throw new IllegalArgumentException("target.headers must not contain null keys or values");
        }
        headers = Map.copyOf(headers);
    }
}
