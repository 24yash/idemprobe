package dev.idemprobe.config;

import java.net.URI;
import java.util.Map;

public record TargetSpec(String method, URI url, Map<String, String> headers, String body) {
    public TargetSpec {
        headers = Map.copyOf(headers);
    }
}
