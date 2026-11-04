package dev.resqnet.mesh.simulator;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class SimulatorCliTest {

    @Test
    void rejectsIntegerAboveMaximum() {
        Result result = run("--nodes", "2147483648");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("--nodes");
    }

    @Test
    void rejectsIntegerBelowMinimum() {
        Result result = run("--nodes", "-2147483649");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error()).contains("--nodes");
    }

    @Test
    void validIntegerReachesNormalValidation() {
        Result result = run("--nodes", "1");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.error())
                .doesNotContain("--nodes expects an integer");
    }

    private static Result run(String... args) {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();

        try (PrintStream capturedErr = new PrintStream(
                errorOutput, true, StandardCharsets.UTF_8)) {
            System.setErr(capturedErr);
            int exitCode = SimulatorCli.run(args);
            capturedErr.flush();

            return new Result(exitCode,
                    errorOutput.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(originalErr);
        }
    }

    private record Result(int exitCode, String error) {
    }
}