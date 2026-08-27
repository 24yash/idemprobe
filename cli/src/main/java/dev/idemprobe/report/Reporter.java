package dev.idemprobe.report;

import dev.idemprobe.evaluation.RunResult;
import java.io.IOException;
import java.io.OutputStream;

public interface Reporter {
    void write(RunResult result, OutputStream output) throws IOException;
}
