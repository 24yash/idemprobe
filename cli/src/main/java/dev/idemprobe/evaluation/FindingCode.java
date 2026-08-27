package dev.idemprobe.evaluation;

public enum FindingCode {
    STATUS_NOT_ALLOWED(false),
    IDENTITY_MISSING(false),
    IDENTITY_DIVERGED(false),
    SIDE_EFFECT_COUNT_MISMATCH(false),
    TRANSPORT_ERROR(true),
    PARSING_ERROR(true),
    VERIFICATION_STATUS_ERROR(true),
    EXECUTION_INVALID(true);

    private final boolean preventsVerdict;

    FindingCode(boolean preventsVerdict) {
        this.preventsVerdict = preventsVerdict;
    }

    public boolean preventsVerdict() {
        return preventsVerdict;
    }
}
