
package dev.resqnet.mesh.simulator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationConfigValidationTest {

    private SimulationConfig createConfig(
            double broadcastRatio,
            double linkDropProbability,
            long messageLifetimeSeconds) {

        return new SimulationConfig(
                Topology.of(Topology.Shape.RING, 4, 3, 1L),
                10,
                broadcastRatio,
                1_760_000_000L,
                250L,
                10,
                42L,
                linkDropProbability,
                0,
                8,
                messageLifetimeSeconds,
                false
        );
    }

    @Test
    @DisplayName("Rejects NaN broadcast ratio")
    void rejectsNaNBroadcastRatio() {
        assertThatThrownBy(() -> createConfig(Double.NaN, 0.0, 900L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects non-finite link drop probability")
    void rejectsInfiniteLinkDropProbability() {
        assertThatThrownBy(() -> createConfig(0.5, Double.POSITIVE_INFINITY, 900L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects zero message lifetime")
    void rejectsZeroMessageLifetime() {
        assertThatThrownBy(() -> createConfig(0.5, 0.0, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects negative message lifetime")
    void rejectsNegativeMessageLifetime() {
        assertThatThrownBy(() -> createConfig(0.5, 0.0, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
