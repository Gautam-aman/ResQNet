package dev.resqnet.mesh.simulator;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.resqnet.mesh.routing.RoutingMetrics;
import dev.resqnet.mesh.simulator.json.JsonObject;

/**
 * Everything a simulation run measured.
 *
 * <p>Every number here was produced by a real {@code MeshRouter} during the run.
 * Nothing is modelled, extrapolated or benchmarked from a previous experiment.
 * When a value could not be measured it is reported as {@code -1} rather than
 * replaced with a plausible guess, because a fabricated figure on an emergency
 * dashboard is worse than a missing one.
 */
public final class SimulationReport {

    private final SimulationConfig config;
    private final int nodeCount;
    private final long originatedCount;
    /** Originated messages that named a destination and so can be confirmed. */
    private final long directedOriginatedCount;
    /** Originated broadcasts, which by construction can never be acknowledged. */
    private final long broadcastOriginatedCount;
    private final long deliveredCount;
    private final long acknowledgedCount;
    private final long expiredCount;
    private final long failedCount;
    private final long queuedCount;
    private final long duplicateSuppressed;
    private final long rejectedCount;
    private final long relayedCount;
    private final double deliveryRate;
    private final double acknowledgementRate;
    private final long medianLatencyMillis;
    private final long p95LatencyMillis;
    private final long p99LatencyMillis;
    private final long maxLatencyMillis;
    private final double amplificationFactor;
    private final Map<Integer, Long> hopDistribution;
    private final Map<Integer, Long> hopLatencySum;
    private final long framesSent;
    private final long framesDelivered;
    private final long framesDropped;
    private final long linkChangeCount;
    private final int partitions;
    private final List<NodeReport> nodes;
    private final List<SimulationEvent> events;
    private final List<MessageReport> messages;
    private final List<LinkChangeReport> linkChanges;
    private final long totalEventCount;
    private final long simulatedDurationMillis;
    private final long wallClockMillis;

    SimulationReport(SimulationConfig config, List<NodeReport> nodes, List<SimulationEvent> events,
                     long framesSent, long framesDelivered, long framesDropped,
                     long linkChangeCount, int partitions, long simulatedDurationMillis,
                     long wallClockMillis, long[] latencySamples, RoutingMetrics aggregate,
                     Map<Integer, Long> hopDistribution, Map<Integer, Long> hopLatencySum,
                     List<MessageReport> messages, List<LinkChangeReport> linkChangeRecords,
                     long totalEventCount) {
        this.config = config;
        this.nodes = List.copyOf(nodes);
        this.events = List.copyOf(events);
        this.messages = List.copyOf(messages);
        this.linkChanges = List.copyOf(linkChangeRecords);
        this.totalEventCount = totalEventCount;
        this.nodeCount = nodes.size();
        this.framesSent = framesSent;
        this.framesDelivered = framesDelivered;
        this.framesDropped = framesDropped;
        this.linkChangeCount = linkChangeCount;
        this.partitions = partitions;
        this.simulatedDurationMillis = simulatedDurationMillis;
        this.wallClockMillis = wallClockMillis;
        // Ordered, not Map.copyOf. The JDK randomises ImmutableCollections
        // iteration order per JVM run, which would make two runs with the same
        // seed emit the same numbers under different key orders and defeat any
        // attempt to diff the output.
        this.hopDistribution = sortedLongs(hopDistribution);
        this.hopLatencySum = sortedLongs(hopLatencySum);

        this.originatedCount = aggregate.originatedCount();
        this.acknowledgedCount = aggregate.acknowledgementReceivedCount();
        this.directedOriginatedCount = aggregate.directedOriginatedCount();
        this.broadcastOriginatedCount = Math.max(0, originatedCount - directedOriginatedCount);
        this.deliveredCount = countNodesByState(dev.resqnet.mesh.protocol.DeliveryState.DELIVERED)
                + countNodesByState(dev.resqnet.mesh.protocol.DeliveryState.ACKNOWLEDGED);
        this.expiredCount = countNodesByState(dev.resqnet.mesh.protocol.DeliveryState.EXPIRED);
        this.failedCount = countNodesByState(dev.resqnet.mesh.protocol.DeliveryState.FAILED);
        this.queuedCount = countNodesByState(dev.resqnet.mesh.protocol.DeliveryState.QUEUED)
                + countNodesByState(dev.resqnet.mesh.protocol.DeliveryState.SENT_TO_PEER)
                + countNodesByState(dev.resqnet.mesh.protocol.DeliveryState.RELAYED);
        this.duplicateSuppressed = aggregate.duplicateSuppressedCount();
        this.rejectedCount = aggregate.totalDropCount();
        this.relayedCount = aggregate.forwardedCount();

        // Denominator is directed messages only.
        //
        // A broadcast has no destination, so MeshRouter.buildAcknowledgement
        // returns empty for one by design: there is nobody to acknowledge it
        // to. Counting broadcasts in the denominator therefore caps the
        // reported rate below 100% no matter how well the mesh performs, and
        // makes a healthy run look broken. Worse, it misstates the property a
        // responder cares about, which is "did the person it was addressed to
        // confirm receipt".
        this.deliveryRate = directedOriginatedCount == 0 ? 0
                : (double) acknowledgedCount / directedOriginatedCount;
        // Retained for compatibility with existing reports, with the same
        // directed-only denominator and a name that says what it means.
        this.acknowledgementRate = this.deliveryRate;
        this.amplificationFactor = aggregate.amplificationFactor();

        this.medianLatencyMillis = percentile(latencySamples, 50);
        this.p95LatencyMillis = percentile(latencySamples, 95);
        this.p99LatencyMillis = percentile(latencySamples, 99);
        this.maxLatencyMillis = latencySamples.length == 0
                ? -1 : Arrays.stream(latencySamples).max().orElse(-1);
    }

    private long countNodesByState(dev.resqnet.mesh.protocol.DeliveryState state) {
        long total = 0;
        for (NodeReport node : nodes) {
            total += node.countByState(state);
        }
        return total;
    }

    /** Nearest-rank percentile, or -1 when there are no samples. */
    static long percentile(long[] samples, int percentile) {
        if (samples.length == 0) {
            return -1;
        }
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    public SimulationConfig config() {
        return config;
    }

    public int nodeCount() {
        return nodeCount;
    }

    /** Messages composed on origin devices during the run. */
    public long originatedCount() {
        return originatedCount;
    }

    /**
     * Messages that named a destination, and so can be confirmed end to end.
     *
     * <p>This is the denominator of {@link #deliveryRate()}.
     */
    public long directedOriginatedCount() {
        return directedOriginatedCount;
    }

    /**
     * Messages originated as broadcasts.
     *
     * <p>Exposed rather than folded into {@link #originatedCount()} silently,
     * because "25% of what we sent can never be acknowledged" is a fact about
     * the workload and not a defect.
     */
    public long broadcastOriginatedCount() {
        return broadcastOriginatedCount;
    }

    /** Messages confirmed end-to-end by an acknowledgement from the recipient. */
    public long acknowledgedCount() {
        return acknowledgedCount;
    }

    /** Records that reached a destination inbox, whether or not they were confirmed. */
    public long deliveredCount() {
        return deliveredCount;
    }

    public long expiredCount() {
        return expiredCount;
    }

    public long failedCount() {
        return failedCount;
    }

    /** Still queued at the end of the run. */
    public long queuedCount() {
        return queuedCount;
    }

    public long duplicateSuppressed() {
        return duplicateSuppressed;
    }

    public long rejectedCount() {
        return rejectedCount;
    }

    /** Total forwards performed across every node. */
    public long relayedCount() {
        return relayedCount;
    }

    /** Fraction of originated messages that were confirmed delivered. */
    public double deliveryRate() {
        return deliveryRate;
    }

    public double amplificationFactor() {
        return amplificationFactor;
    }

    public long medianLatencyMillis() {
        return medianLatencyMillis;
    }

    public long p95LatencyMillis() {
        return p95LatencyMillis;
    }

    public long p99LatencyMillis() {
        return p99LatencyMillis;
    }

    public long maxLatencyMillis() {
        return maxLatencyMillis;
    }

    public Map<Integer, Long> hopDistribution() {
        return hopDistribution;
    }

    public long framesSent() {
        return framesSent;
    }

    public long framesDelivered() {
        return framesDelivered;
    }

    public long framesDropped() {
        return framesDropped;
    }

    public long linkChanges() {
        return linkChangeCount;
    }

    /** Number of partitions in the topology at the end of the simulation run. */
    public int partitions() {
        return partitions;
    }

    public long simulatedDurationMillis() {
        return simulatedDurationMillis;
    }

    /** Real CPU time to execute the run, for throughput measurement. */
    public long wallClockMillis() {
        return wallClockMillis;
    }

    /** Messages per second of real CPU time. Honest about being a simulation. */
    public double simulatedThroughputPerSecond() {
        return wallClockMillis == 0 ? 0 : (originatedCount * 1000.0) / wallClockMillis;
    }

    public List<NodeReport> nodes() {
        return nodes;
    }

    public List<SimulationEvent> events() {
        return events;
    }

    /**
     * Messages originated during the run, with the node that composed each one.
     *
     * <p>Recorded because a report that lists events without saying which
     * message went where cannot answer the question a responder actually
     * asks: did the person I addressed receive it?
     */
    public List<MessageReport> messages() {
        return messages;
    }

    /** Every link cut and restored during the run, in the order it happened. */
    public List<LinkChangeReport> linkChangeRecords() {
        return linkChanges;
    }

    /** Total events observed, including those outside the retained window. */
    public long totalEventCount() {
        return totalEventCount;
    }

    /**
     * True when the report could not keep every event.
     *
     * <p>A truncated history is still useful, but a viewer that does not know
     * it is truncated will draw conclusions from a partial run.
     */
    public boolean eventsTruncated() {
        return totalEventCount > events.size();
    }

    /** Average simulated latency observed for messages that took N hops. */
    public long averageLatencyForHopCount(int hops) {
        Long sum = hopLatencySum.get(hops);
        long count = hopDistribution.getOrDefault(hops, 0L);
        return sum == null || count == 0 ? -1 : sum / count;
    }

    /** Full report as JSON, suitable for the gateway or a CI artefact. */
    public JsonObject toJson() {
        return JsonObject.object()
                .put("simulation", JsonObject.object()
                        .put("topology", config.topology().shape().name())
                        .put("nodeCount", config.topology().nodeCount())
                        .put("linkCount", config.topology().linkCount())
                        .put("degree", config.topology().degree())
                        .put("seed", config.seed())
                        .put("messageCount", config.messageCount())
                        .put("rounds", config.rounds())
                        .put("stepMillis", config.stepMillis())
                        .put("hopLimit", config.hopLimit())
                        .put("linkDropProbability", config.linkDropProbability())
                        .put("broadcastRatio", config.broadcastRatio())
                        .put("churnEveryRounds", config.churnEveryRounds())
                        .put("messageLifetimeSeconds", config.messageLifetimeSeconds())
                        .put("startEpochSeconds", config.startEpochSeconds())
                        .put("startPartitioned", config.startPartitioned())
                        .put("edges", edgesJson()))
                .put("outcome", JsonObject.object()
                        .put("originated", originatedCount)
                        .put("directedOriginated", directedOriginatedCount)
                        .put("broadcastOriginated", broadcastOriginatedCount)
                        .put("acknowledged", acknowledgedCount)
                        .put("deliveredRecords", deliveredCount)
                        .put("expired", expiredCount)
                        .put("failed", failedCount)
                        .put("stillQueued", queuedCount)
                        .put("duplicatesSuppressed", duplicateSuppressed)
                        .put("rejected", rejectedCount)
                        .put("forwards", relayedCount))
                .put("deliveryRate", deliveryRate)
                .put("amplificationFactor", amplificationFactor)
                .put("latencyMillis", JsonObject.object()
                        .put("median", medianLatencyMillis)
                        .put("p95", p95LatencyMillis)
                        .put("p99", p99LatencyMillis)
                        .put("max", maxLatencyMillis))
                .put("hopDistribution", JsonObject.ofCounts(hopDistribution))
                .put("radio", JsonObject.object()
                        .put("framesSent", framesSent)
                        .put("framesDelivered", framesDelivered)
                        .put("framesDropped", framesDropped)
                        .put("linkChanges", linkChangeCount)
                        .put("partitions", partitions))
                .put("timing", JsonObject.object()
                        .put("simulatedMillis", simulatedDurationMillis)
                        .put("wallClockMillis", wallClockMillis)
                        .put("throughputPerSecond", simulatedThroughputPerSecond()))
                .put("eventWindow", JsonObject.object()
                        .put("retained", events.size())
                        .put("total", totalEventCount)
                        .put("truncated", eventsTruncated()))
                .putObjects("linkChanges", linkChanges.stream()
                        .map(LinkChangeReport::toJson).toList())
                .putObjects("messages", messages.stream().map(MessageReport::toJson).toList())
                .putObjects("nodes", nodes.stream().map(NodeReport::toJson).toList())
                .putObjects("events", events.stream().map(SimulationEvent::toJson).toList());
    }

    /**
     * The initial topology as {@code [from, to]} pairs.
     *
     * <p>Recorded rather than drawn: a viewer that shows these links is
     * showing the graph the simulator actually built, not a decorative mesh.
     */
    private dev.resqnet.mesh.simulator.json.JsonArray edgesJson() {
        dev.resqnet.mesh.simulator.json.JsonArray edges =
                new dev.resqnet.mesh.simulator.json.JsonArray();
        for (int[] edge : config.topology().edges()) {
            dev.resqnet.mesh.simulator.json.JsonArray pair =
                    new dev.resqnet.mesh.simulator.json.JsonArray();
            pair.add(edge[0]);
            pair.add(edge[1]);
            edges.add(pair);
        }
        return edges;
    }

    /** A concise one-screen summary for a terminal. */
    public String toSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("ResQNet simulation — ").append(config.topology().shape())
                .append(", ").append(nodeCount).append(" nodes, seed ").append(config.seed())
                .append('\n');
        sb.append("─".repeat(64)).append('\n');
        sb.append(String.format("  originated            %d%n", originatedCount));
        sb.append(String.format("  acknowledged          %d%n", acknowledgedCount));
        sb.append(String.format("  delivery rate         %.2f%%%n", deliveryRate * 100));
        sb.append(String.format("  median latency        %d ms%n", medianLatencyMillis));
        sb.append(String.format("  p95 latency           %d ms%n", p95LatencyMillis));
        sb.append(String.format("  forwards              %d%n", relayedCount));
        sb.append(String.format("  amplification         %.3f%n", amplificationFactor));
        sb.append(String.format("  duplicates suppressed %d%n", duplicateSuppressed));
        sb.append(String.format("  expired / failed      %d / %d%n", expiredCount, failedCount));
        sb.append(String.format("  still queued          %d%n", queuedCount));
        sb.append(String.format("  radio frames          %d sent, %d dropped%n",
                framesSent, framesDropped));
        sb.append(String.format("  partitions at end     %d%n", partitions));
        sb.append(String.format("  simulated / cpu time  %d ms / %d ms%n",
                simulatedDurationMillis, wallClockMillis));
        sb.append(String.format("  throughput            %.1f msg/s of CPU time%n",
                simulatedThroughputPerSecond()));
        return sb.toString();
    }

    /**
     * Per-node measurements.
     *
     * @param stateCounts how many stored records currently sit in each delivery
     *     state, including the relay copies this node is holding for others
     */
    public record NodeReport(
            int index,
            String label,
            String deviceId,
            String keyFingerprint,
            long originated,
            long accepted,
            long forwarded,
            long delivered,
            long duplicatesSuppressed,
            long rejected,
            long untrustedDrops,
            long rateLimitDrops,
            long hopLimitDrops,
            long stillQueued,
            long medianLatencyMillis,
            long p95LatencyMillis,
            int degreeAtEnd,
            Map<dev.resqnet.mesh.protocol.DeliveryState, Integer> stateCounts
    ) {

        /** Builds a report for one node from its live router and store. */
        static NodeReport of(SimulatedNode node, int degreeAtEnd) {
            RoutingMetrics metrics = node.router().metrics();
            Map<dev.resqnet.mesh.protocol.DeliveryState, Integer> stateCounts =
                    new LinkedHashMap<>();
            for (dev.resqnet.mesh.protocol.DeliveryState state
                    : dev.resqnet.mesh.protocol.DeliveryState.values()) {
                int count = node.store().countByState(state);
                if (count > 0) {
                    stateCounts.put(state, count);
                }
            }
            return new NodeReport(node.index(), node.label(), node.deviceId(),
                    node.keyFingerprint(), metrics.originatedCount(), metrics.acceptedCount(),
                    metrics.forwardedCount(), metrics.deliveredCount(),
                    metrics.duplicateSuppressedCount(), metrics.totalDropCount(),
                    metrics.untrustedDropCount(), metrics.rateLimitDropCount(),
                    metrics.hopLimitDropCount(), node.store().pendingCount(),
                    metrics.medianDeliveryLatencyMillis(), metrics.p95DeliveryLatencyMillis(),
                    degreeAtEnd, sortedCounts(stateCounts));
        }

        long countByState(dev.resqnet.mesh.protocol.DeliveryState state) {
            return stateCounts.getOrDefault(state, 0);
        }

        public JsonObject toJson() {
            JsonObject stateJson = JsonObject.object();
            stateCounts.forEach((state, count) -> stateJson.put(state.name(), count));
            return JsonObject.object()
                    .put("index", index)
                    .put("label", label)
                    .put("deviceId", deviceId)
                    .put("keyFingerprint", keyFingerprint)
                    .put("originated", originated)
                    .put("accepted", accepted)
                    .put("forwarded", forwarded)
                    .put("delivered", delivered)
                    .put("duplicatesSuppressed", duplicatesSuppressed)
                    .put("rejected", rejected)
                    .put("untrustedDrops", untrustedDrops)
                    .put("rateLimitDrops", rateLimitDrops)
                    .put("hopLimitDrops", hopLimitDrops)
                    .put("stillQueued", stillQueued)
                    .put("medianLatencyMillis", medianLatencyMillis)
                    .put("p95LatencyMillis", p95LatencyMillis)
                    .put("degreeAtEnd", degreeAtEnd)
                    .put("stateCounts", stateJson);
        }
    }


    /**
     * One message that was composed during the run.
     *
     * @param messageId protocol message id
     * @param originIndex node that composed the message
     * @param destinationIndex node it was addressed to, {@code -1} for a broadcast
     * @param broadcast true when the message named no destination
     * @param originatedAtMillis simulated time the message was composed
     */
    public record MessageReport(
            String messageId,
            int originIndex,
            int destinationIndex,
            boolean broadcast,
            long originatedAtMillis
    ) {

        public JsonObject toJson() {
            return JsonObject.object()
                    .put("id", messageId)
                    .put("origin", originIndex)
                    .put("destination", broadcast ? null : destinationIndex)
                    .put("broadcast", broadcast)
                    .put("originatedAt", originatedAtMillis);
        }
    }

    /**
     * One link transition.
     *
     * @param atEpochMillis simulated time of the change
     * @param round simulation round, {@code -1} before the first round starts
     * @param fromIndex first node index
     * @param toIndex second node index
     * @param up true when the link came back, false when it was cut
     */
    public record LinkChangeReport(
            long atEpochMillis,
            int round,
            int fromIndex,
            int toIndex,
            boolean up
    ) {

        public JsonObject toJson() {
            return JsonObject.object()
                    .put("atEpochMillis", atEpochMillis)
                    .put("round", round)
                    .put("from", fromIndex)
                    .put("to", toIndex)
                    .put("up", up);
        }
    }

    /**
     * The JSON paths that cannot be byte-stable across runs of the same seed.
     *
     * <p>Documented rather than silently tolerated. Everything outside this set
     * is a routing decision and is reproducible, which is the property that
     * makes a report usable as a regression artefact.
     */
    public static java.util.List<String> volatileJsonFields() {
        return java.util.List.of(
                "timing.wallClockMillis",
                "timing.throughputPerSecond",
                "nodes[].deviceId",
                "nodes[].keyFingerprint");
    }

    /**
     * Freezes a map in a stable iteration order.
     *
     * <p>{@code Map.copyOf} discards insertion order and the JDK then iterates
     * its immutable maps in a per-JVM randomised order. Two runs with the same
     * seed produced identical numbers but different key ordering in the JSON,
     * so the output could not be diffed despite the CLI promising a byte for
     * byte reproducible report. Sorting by key makes the order a function of
     * the data instead of of the JVM.
     */
    private static Map<Integer, Long> sortedLongs(Map<Integer, Long> source) {
        Map<Integer, Long> out = new java.util.TreeMap<>();
        out.putAll(source);
        return java.util.Collections.unmodifiableMap(out);
    }

    private static <K extends Comparable<? super K>> Map<K, Integer> sortedCounts(
            Map<K, Integer> source) {
        Map<K, Integer> out = new java.util.TreeMap<>();
        out.putAll(source);
        return java.util.Collections.unmodifiableMap(out);
    }
}