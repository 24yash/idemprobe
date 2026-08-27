package dev.idemprobe.evaluation;

import java.util.Objects;

public record Finding(FindingCode code, String message) {
    public Finding {
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");
    }
}
