
package dev.resqnet.mesh.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimulatorCliValidationTest {

    @Test
    @DisplayName("Rejects NaN drop probability with exit code 2")
    void rejectsNaNDropProbability() throws Exception {
        assertInvalidArguments("--drop", "NaN");
    }

    @Test
    @DisplayName("Rejects infinite drop probability with exit code 2")
    void rejectsInfiniteDropProbability() throws Exception {
        assertInvalidArguments("--drop", "Infinity");
    }

    @Test
    @DisplayName("Rejects zero message lifetime with exit code 2")
    void rejectsZeroLifetime() throws Exception {
        assertInvalidArguments("--lifetime", "0");
    }

    @Test
    @DisplayName("Rejects negative message lifetime with exit code 2")
    void rejectsNegativeLifetime() throws Exception {
        assertInvalidArguments("--lifetime", "-1");
    }

    private void assertInvalidArguments(String option, String value)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(SimulatorCli.class.getName());
        command.add(option);
        command.add(value);

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        assertThat(exitCode)
                .as("CLI output: " + output)
                .isEqualTo(2);
        assertThat(output).contains("error:");
    }
}
