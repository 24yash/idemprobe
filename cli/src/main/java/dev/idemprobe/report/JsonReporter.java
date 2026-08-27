package dev.idemprobe.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.idemprobe.engine.HttpInvocationResult;
import dev.idemprobe.engine.InvocationResult;
import dev.idemprobe.evaluation.Finding;
import dev.idemprobe.evaluation.RunResult;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Comparator;
import java.util.List;

public final class JsonReporter implements Reporter {
    private static final int SCHEMA_VERSION = 1;
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
            .defaultPropertyInclusion(JsonInclude.Value.construct(
                    JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
            .build();

    @Override
    public void write(RunResult result, OutputStream output) throws IOException {
        Report report = new Report(
                SCHEMA_VERSION,
                result.exitCode(),
                verdict(result.exitCode()),
                result.execution().invocations().stream()
                        .sorted(Comparator.comparing(InvocationResult::phase)
                                .thenComparingInt(InvocationResult::index))
                        .map(JsonReporter::evidence)
                        .toList(),
                evidence(result.execution().verificationResult()),
                result.findings());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(output, report);
        output.write('\n');
    }

    private static InvocationEvidence evidence(InvocationResult result) {
        if (result instanceof HttpInvocationResult http) {
            return new InvocationEvidence(
                    http.index(), http.phase().name(), "HTTP", http.statusCode());
        }
        return new InvocationEvidence(
                result.index(), result.phase().name(), "TRANSPORT_FAILURE", null);
    }

    private static String verdict(int exitCode) {
        return switch (exitCode) {
            case 0 -> "PASS";
            case 1 -> "FAIL";
            default -> "ERROR";
        };
    }

    private record Report(
            int schemaVersion,
            int exitCode,
            String verdict,
            List<InvocationEvidence> invocations,
            InvocationEvidence verification,
            List<Finding> findings) {}

    private record InvocationEvidence(
            int index,
            String phase,
            String outcome,
            Integer statusCode) {}
}
