
package dev.resqnet.mesh.simulator;

import dev.resqnet.mesh.routing.RetryPolicy;
import dev.resqnet.mesh.routing.RouterConfig;

/**
 * Everything needed to reproduce a simulation run exactly.
 *
 * <p>The whole configuration is a value: same config plus same seed gives the
 * same event stream on any machine. That is the property that makes a routing
 * regression show up as a diff rather than as "it seemed flaky that one time".
 *
 * @param topology network shape
 * @param messageCount how many messages to originate
 * @param broadcastRatio fraction of messages addressed to everyone, in [0,1]
 * @param startEpochSeconds simulated wall-clock start
 * @param stepMillis simulated time advanced per round
 * @param rounds number of rounds to run
 * @param seed master seed for topology, message ids and retry jitter
 * @param linkDropProbability probability that any single frame is lost, in [0,1]
 * @param churnEveryRounds cut and restore one link every N rounds, 0 disables
 * @param hopLimit hop budget for originated messages
 * @param messageLifetimeSeconds lifetime of originated messages
 * @param startPartitioned whether to begin with the busiest relay offline
 */
public record SimulationConfig(
        Topology topology,
        int messageCount,
        double broadcastRatio,
        long startEpochSeconds,
        long stepMillis,
        int rounds,
        long seed,
        double linkDropProbability,
        int churnEveryRounds,
        int hopLimit,
        long messageLifetimeSeconds,
        boolean startPartitioned
) {

    public SimulationConfig {
        if (topology == null) {
            throw new IllegalArgumentException("topology is required");
        }

        if (messageCount < 0 || rounds < 1 || stepMillis <= 0) {
            throw new IllegalArgumentException(
                    "messageCount >= 0, rounds >= 1, stepMillis > 0");
        }

        if (!Double.isFinite(broadcastRatio)
                || broadcastRatio < 0 || broadcastRatio > 1) {
            throw new IllegalArgumentException(
                    "broadcastRatio must be within [0,1]");
        }

        if (!Double.isFinite(linkDropProbability)
                || linkDropProbability < 0 || linkDropProbability > 1) {
            throw new IllegalArgumentException(
                    "linkDropProbability must be within [0,1]");
        }

        if (messageLifetimeSeconds <= 0) {
            throw new IllegalArgumentException(
                    "messageLifetimeSeconds must be positive");
        }

        if (hopLimit < 1 || hopLimit > 32) {
            throw new IllegalArgumentException(
                    "hopLimit must be within [1,32]");
        }

        if (churnEveryRounds < 0) {
            throw new IllegalArgumentException(
                    "churnEveryRounds must not be negative");
        }
    }

    /** A sensible default configuration for the given topology. */
    public static SimulationConfig defaults(Topology topology, long seed) {
        return new SimulationConfig(
                topology, 24, 0.25, 1_760_000_000L, 250L, 60, seed,
                0.0, 12, 8, 900L, false);
    }

    /** Router configuration derived from this scenario. */
    public RouterConfig routerConfig() {
        return RouterConfig.builder()
                .hopLimit(hopLimit)
                .lifetimeMillis(messageLifetimeSeconds * 1000L)
                .maxPendingMessages(256)
                .maxRelayMessages(256)
                .maxFanOut(8)
                .acknowledgementsEnabled(true)
                .build();
    }

    /**
     * Deterministic retry policy shared by every node in the run.
     *
     * <p>The four second base is chosen from radio economics rather than
     * convenience. Retrying a message whose neighbours have already received it
     * is pure battery cost, so an unconfirmed message must not be re-flooded
     * faster than that. A genuine topology change still overrides the schedule,
     * so a link that comes back is used immediately.
     */
    public RetryPolicy retryPolicy() {
        return RetryPolicy.deterministic(4_000L, 180_000L, 6, seed);
    }

    /** Copy with a different message count. */
    public SimulationConfig withMessageCount(int count) {
        return new SimulationConfig(
                topology, count, broadcastRatio, startEpochSeconds,
                stepMillis, rounds, seed, linkDropProbability, churnEveryRounds,
                hopLimit, messageLifetimeSeconds, startPartitioned);
    }

    /** Copy with a different drop probability. */
    public SimulationConfig withLinkDropProbability(double probability) {
        return new SimulationConfig(
                topology, messageCount, broadcastRatio, startEpochSeconds,
                stepMillis, rounds, seed, probability, churnEveryRounds,
                hopLimit, messageLifetimeSeconds, startPartitioned);
    }

    /** Copy with churn enabled or disabled. */
    public SimulationConfig withChurnEveryRounds(int interval) {
        return new SimulationConfig(
                topology, messageCount, broadcastRatio, startEpochSeconds,
                stepMillis, rounds, seed, linkDropProbability, interval,
                hopLimit, messageLifetimeSeconds, startPartitioned);
    }

    /** Copy starting from a partitioned topology. */
    public SimulationConfig startingPartitioned() {
        return new SimulationConfig(
                topology, messageCount, broadcastRatio, startEpochSeconds,
                stepMillis, rounds, seed, linkDropProbability, churnEveryRounds,
                hopLimit, messageLifetimeSeconds, true);
    }

    /** Copy with a different hop budget. */
    public SimulationConfig withHopLimit(int hops) {
        if (hops < 1 || hops > 32) {
            throw new IllegalArgumentException(
                    "hopLimit must be within [1,32]");
        }

        return new SimulationConfig(
                topology, messageCount, broadcastRatio, startEpochSeconds,
                stepMillis, rounds, seed, linkDropProbability, churnEveryRounds,
                hops, messageLifetimeSeconds, startPartitioned);
    }

    /** Copy with a different round count. */
    public SimulationConfig withRounds(int count) {
        return new SimulationConfig(
                topology, messageCount, broadcastRatio, startEpochSeconds,
                stepMillis, count, seed, linkDropProbability, churnEveryRounds,
                hopLimit, messageLifetimeSeconds, startPartitioned);
    }

    public long endEpochSeconds() {
        return startEpochSeconds + (rounds * stepMillis) / 1000L;
    }
}
