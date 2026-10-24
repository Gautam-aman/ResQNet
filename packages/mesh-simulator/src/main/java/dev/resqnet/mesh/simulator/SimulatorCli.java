package dev.resqnet.mesh.simulator;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * Command line entry point for the ResQNet mesh simulator.
 *
 * <pre>
 * mvn -q -pl packages/mesh-simulator exec:java \
 *   -Dexec.args="--topology ring --nodes 8 --messages 40 --seed 42 --churn 10"
 *
 * mvn -q -pl packages/mesh-simulator exec:java \
 *   -Dexec.args="--topology mesh --nodes 12 --seed 7 --drop 0.2 --out report.json"
 * </pre>
 *
 * <p>Every run is fully described by its arguments plus its seed. Re-running with
 * the same arguments reproduces the same event stream, byte for byte, except
 * for the wall-clock timing and the freshly generated node keys listed in
 * {@code SimulationReport#volatileJsonFields()}. Every routing decision is
 * reproducible, which is what makes a report usable as a regression artefact
 * rather than a story.
 */
public final class SimulatorCli {

    private static final String USAGE = """
            ResQNet mesh simulator

            Usage:
              java -jar mesh-simulator.jar [options]

            Options:
              --topology <line|ring|star|mesh|two-cluster|random>  network shape (default: ring)
              --nodes <n>          node count, minimum 2 (default: 8)
              --degree <d>         target degree for random/mesh shapes (default: 3)
              --messages <m>       messages to originate (default: 24)
              --broadcast <ratio>  fraction of broadcasts, 0..1 (default: 0.25)
              --rounds <r>         simulation rounds (default: 60)
              --step <ms>          simulated time per round (default: 250)
              --seed <s>           master seed (default: 42)
              --drop <p>           probability a frame is lost, 0..1 (default: 0.0)
              --churn <n>          cut/restore one link every n rounds, 0 disables (default: 12)
              --hops <h>           hop limit for originated messages (default: 8)
              --lifetime <s>       message lifetime in seconds (default: 900)
              --partitioned        start with the busiest relay offline
              --out <file>         write the full JSON report to a file
              --gateway <url>      POST the event stream to a running backend
              --gateway-token <t>  shared device token for --gateway (or set
                                    RESQNET_GATEWAY_TOKEN)
              --quiet              only print the summary
              --help               show this message

            Examples:
              --topology ring --nodes 8 --seed 42
              --topology mesh --nodes 12 --seed 7 --drop 0.2 --churn 6
              --topology two-cluster --nodes 10 --partitioned --rounds 80
            """;

    private SimulatorCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Testable entry point. Returns the process exit code. */
    public static int run(String[] args) {
        if (args.length == 0 || hasFlag(args, "--help", "-h")) {
            System.out.println(USAGE);
            return args.length == 0 ? 1 : 0;
        }

        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            System.err.println(USAGE);
            return 2;
        }

        SimulationConfig config;
        try {
            config = options.toConfig();
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }

        try (MeshSimulation simulation = new MeshSimulation(config)) {
            SimulationReport report = simulation.run();
            if (!options.quiet) {
                System.out.println(report.toSummary());
                System.out.println("Topology links at end: "
                        + config.topology().edges().size() + " (initial)");
            }
            System.out.printf(Locale.ROOT,
                    "Nodes: %d | originated: %d | acknowledged: %d | delivery rate: %.1f%%%n",
                    report.nodeCount(), report.originatedCount(), report.acknowledgedCount(),
                    report.deliveryRate() * 100);
            // Stated separately because it changes how the rate above should be
            // read: broadcasts cannot be acknowledged, so the rate is a share of
            // directed messages only.
            System.out.printf(Locale.ROOT,
                    "  directed: %d (confirmable) | broadcast: %d (cannot be acknowledged)%n",
                    report.directedOriginatedCount(), report.broadcastOriginatedCount());

            if (options.output != null) {
                writeReport(report, options.output);
                System.out.println("Wrote " + options.output.toAbsolutePath());
            }
            if (options.gateway != null) {
                GatewayPublisher publisher = new GatewayPublisher(options.gateway,
                        options.gatewayToken);
                GatewayPublisher.Result result = publisher.publish(report);
                if (result.success()) {
                    System.out.println("Published " + result.acceptedEvents()
                            + " events to " + options.gateway
                            + " (run " + result.runId() + ")");
                } else {
                    System.err.println("Gateway publish failed: " + result.error());
                    return 3;
                }
            }
            return 0;
        } catch (Exception e) {
            System.err.println("simulation failed: " + e);
            return 4;
        }
    }

    private static void writeReport(SimulationReport report, Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, report.toJson().toPrettyString(),
                StandardCharsets.UTF_8);
    }

    private static boolean hasFlag(String[] args, String... names) {
        for (String arg : args) {
            for (String name : names) {
                if (name.equals(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Parsed command line. */
    private record Options(
            Topology.Shape shape,
            int nodes,
            int degree,
            int messages,
            double broadcastRatio,
            int rounds,
            long stepMillis,
            long seed,
            double dropProbability,
            int churn,
            int hops,
            long lifetimeSeconds,
            boolean partitioned,
            Path output,
            String gateway,
            String gatewayToken,
            boolean quiet
    ) {

        static Options parse(String[] args) {
            Topology.Shape shape = Topology.Shape.RING;
            int nodes = 8;
            int degree = 3;
            int messages = 24;
            double broadcast = 0.25;
            int rounds = 60;
            long step = 250;
            long seed = 42;
            double drop = 0.0;
            int churn = 12;
            int hops = 8;
            long lifetime = 900;
            boolean partitioned = false;
            Path output = null;
            String gateway = null;
            String gatewayToken = System.getenv("RESQNET_GATEWAY_TOKEN");
            boolean quiet = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--topology" -> shape = parseShape(value(args, ++i, "--topology"));
                    case "--nodes" -> nodes = parseInt(value(args, ++i, "--nodes"), "--nodes");
                    case "--degree" -> degree = parseInt(value(args, ++i, "--degree"), "--degree");
                    case "--messages" -> messages = parseInt(value(args, ++i, "--messages"),
                            "--messages");
                    case "--broadcast" -> broadcast = parseDouble(value(args, ++i,
                            "--broadcast"), "--broadcast");
                    case "--rounds" -> rounds = parseInt(value(args, ++i, "--rounds"), "--rounds");
                    case "--step" -> step = parseLong(value(args, ++i, "--step"), "--step");
                    case "--seed" -> seed = parseLong(value(args, ++i, "--seed"), "--seed");
                    case "--drop" -> drop = parseDouble(value(args, ++i, "--drop"), "--drop");
                    case "--churn" -> churn = parseInt(value(args, ++i, "--churn"), "--churn");
                    case "--hops" -> hops = parseInt(value(args, ++i, "--hops"), "--hops");
                    case "--lifetime" -> lifetime = parseLong(value(args, ++i, "--lifetime"),
                            "--lifetime");
                    case "--partitioned" -> partitioned = true;
                    case "--quiet" -> quiet = true;
                    case "--out" -> output = Path.of(value(args, ++i, "--out"));
                    case "--gateway" -> gateway = value(args, ++i, "--gateway");
                    case "--gateway-token" -> gatewayToken = value(args, ++i,
                            "--gateway-token");
                    default -> throw new IllegalArgumentException("unknown option: " + arg);
                }
            }
            if (gateway != null && (gatewayToken == null || gatewayToken.isBlank())) {
                throw new IllegalArgumentException(
                        "--gateway needs the shared device token: pass --gateway-token or set"
                                + " RESQNET_GATEWAY_TOKEN");
            }
            return new Options(shape, nodes, degree, messages, broadcast, rounds, step, seed,
                    drop, churn, hops, lifetime, partitioned, output, gateway, gatewayToken, quiet);
        }

        private static Topology.Shape parseShape(String value) {
            return switch (value.toLowerCase(Locale.ROOT).replace("-", "")) {
                case "line", "chain" -> Topology.Shape.LINE;
                case "ring", "circle" -> Topology.Shape.RING;
                case "star", "hub" -> Topology.Shape.STAR;
                case "mesh", "dense" -> Topology.Shape.MESH;
                case "twocluster", "partition", "split" -> Topology.Shape.TWO_CLUSTER;
                case "random" -> Topology.Shape.RANDOM;
                default -> throw new IllegalArgumentException("unknown topology: " + value);
            };
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }

        private static int parseInt(String value, String option) {
        try {
        return Integer.parseInt(value);
        } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
                option + " expects an integer, got " + value);
            }
        }

        private static long parseLong(String value, String option) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " expects an integer, got " + value);
            }
        }

        private static double parseDouble(String value, String option) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " expects a number, got " + value);
            }
        }

        SimulationConfig toConfig() {
            Topology topology = Topology.of(shape, nodes, degree, seed);
            return new SimulationConfig(topology, messages, broadcastRatio, 1_760_000_000L,
                    stepMillis, rounds, seed, dropProbability, churn, hops, lifetimeSeconds,
                    partitioned);
        }
    }

    /** Minimal HTTP client used to hand a run to a live backend. */
    static final class GatewayPublisher {

        private final URI endpoint;
        private final String token;
        private final HttpClient client;

        GatewayPublisher(String endpoint, String token) {
            this.token = token == null ? "" : token;
            this.endpoint = URI.create(endpoint);
            this.client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        }

        GatewayPublisher.Result publish(SimulationReport report) {
            try {
                String body = report.toJson().toString();
                HttpRequest request = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .header("X-ResQNet-Source", "simulator")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    return new GatewayPublisher.Result(false, null, 0,
                            "HTTP " + response.statusCode() + ": " + response.body());
                }
                long accepted = dev.resqnet.mesh.simulator.json.Json.parseObject(
                                response.body() == null ? "{}" : response.body())
                        .getOrDefault("acceptedEvents", 0L) instanceof Number n ? n.longValue() : 0;
                String runId = String.valueOf(
                        dev.resqnet.mesh.simulator.json.Json.parseObject(
                                response.body() == null ? "{}" : response.body())
                                .getOrDefault("runId", "unknown"));
                return new GatewayPublisher.Result(true, runId, accepted, null);
            } catch (IOException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return new GatewayPublisher.Result(false, null, 0, e.toString());
            }
        }

        record Result(boolean success, String runId, long acceptedEvents, String error) {
        }
    }

    /** Writes a report to an already-open stream; used by tooling and tests. */
    static void writeReport(SimulationReport report, OutputStream out) throws IOException {
        out.write(report.toJson().toPrettyString().getBytes(StandardCharsets.UTF_8));
    }
}