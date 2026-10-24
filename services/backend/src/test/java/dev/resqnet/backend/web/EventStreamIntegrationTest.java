package dev.resqnet.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import dev.resqnet.backend.domain.Category;
import dev.resqnet.backend.domain.IncidentStatus;
import dev.resqnet.backend.domain.ResponderAccount;
import dev.resqnet.backend.domain.ResponderRole;
import dev.resqnet.backend.repository.AuditEventRepository;
import dev.resqnet.backend.repository.IncidentMessageRepository;
import dev.resqnet.backend.repository.IncidentRepository;
import dev.resqnet.backend.repository.ResponderAccountRepository;
import dev.resqnet.backend.security.JwtService;
import dev.resqnet.backend.security.ResQNetPrincipal;
import dev.resqnet.backend.security.WebSocketAuthInterceptor;
import dev.resqnet.backend.service.GatewaySyncService;
import dev.resqnet.backend.service.IncidentService;

/**
 * The live event stream, end to end over a real socket.
 *
 * <p>Deliberately not transactional. An outer test transaction would never
 * commit, so every AFTER_COMMIT listener would silently not fire and the tests
 * would pass for the wrong reason: the exact failure this class exists to
 * prevent. Rows are cleaned in setUp instead, and the database is shared
 * in-memory so nothing leaks between methods anyway.
 *
 * <p>Also deliberately not mocked. Before this the handler existed, the
 * configuration existed, and {@code broadcast()} had no callers at all. A test
 * that called the handler directly would have passed in exactly the state where
 * the feature did not work.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class EventStreamIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private IncidentService incidents;

    @Autowired
    private GatewaySyncService gateway;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private IncidentMessageRepository messages;

    @Autowired
    private AuditEventRepository audit;

    @Autowired
    private ResponderAccountRepository accounts;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private JwtService jwt;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** Frames received on every socket opened by this test. */
    private final List<String> received = new CopyOnWriteArrayList<>();

    /**
     * Runs a body in its own transaction so a failure is a real rollback.
     *
     * <p>Built in setUp rather than inline, because field initialisers run
     * before dependency injection and would capture a null manager.
     */
    private org.springframework.transaction.support.TransactionTemplate failingTransaction;

    @BeforeEach
    void setUp() {
        received.clear();
        failingTransaction = new org.springframework.transaction.support
                .TransactionTemplate(transactionManager);
        audit.deleteAll();
        messages.deleteAll();
        incidentRepository.deleteAll();
    }

    /**
     * Cleans up after as well as before.
     *
     * <p>This class cannot use a test transaction, because an outer transaction
     * would never commit and every AFTER_COMMIT listener would silently not
     * fire. Committing for real means the rows outlive the method, and the
     * in-memory database is shared with every other test class in the module.
     * Without this, ApiSecurityTest started failing on an audit row it had
     * never written.
     */
    @AfterEach
    void tearDown() {
        audit.deleteAll();
        messages.deleteAll();
        incidentRepository.deleteAll();
    }

    @Test
    @DisplayName("refuses an upgrade with no usable credential")
    void refusesUnusableCredentials() {
        assertThat(upgradeSucceeds(null)).isFalse();
        assertThat(upgradeSucceeds("bearer.not-a-token")).isFalse();
        assertThat(upgradeSucceeds("bearer.expired.jwt.value")).isFalse();
    }

    @Test
    @DisplayName("refuses the shared device token, which may report but must not watch")
    void refusesDeviceToken() {
        assertThat(upgradeSucceeds("bearer.test-gateway-token"))
                .as("a reporting credential must not become a reading credential")
                .isFalse();
    }

    @Test
    @DisplayName("accepts a responder session with the expected subprotocol")
    void acceptsResponder() throws Exception {
        try (Subscription subscription = open(
                tokenFor(ResponderRole.RESPONDER, "ws-ok"))) {

            assertThat(subscription).isNotNull();

            assertThat(subscription.session.getAcceptedProtocol())
                    .as("the server must negotiate the ResQNet WebSocket protocol")
                    .isEqualTo(WebSocketAuthInterceptor.PROTOCOL);

            assertThat(subscription.awaitFrame("connected"))
                    .as("an accepted socket is greeted immediately")
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("an incident created by a device reaches a connected dashboard")
    void deviceCreatedIncidentIsBroadcast() throws Exception {
        try (Subscription subscription = open(
                tokenFor(ResponderRole.RESPONDER, "ws-inc"))) {

            assertThat(subscription.awaitFrame("connected")).isNotNull();

            gateway.ingest(new Dtos.GatewaySyncRequest("android", "rqn1_ws_1",
                    List.of(new Dtos.GatewayMessageReport("m-ws", "ACKNOWLEDGED", "SOS",
                            "EMERGENCY_REPORT", "medical", 0.8, "rqn1_origin", "command",
                            1_760_000_000_000L, 1_760_000_900_000L, 1, "ws test", null))),
                    "req-ws");

            String created = subscription.awaitFrame("incident.created");

            assertThat(created)
                    .as("a dashboard must learn about a new incident without polling")
                    .isNotNull();

            assertThat(created).contains("\"advisory\":true");
        }
    }

    @Test
    @DisplayName("resolving an incident produces a resolution event, not a generic update")
    void resolutionHasItsOwnEventType() throws Exception {
        gateway.ingest(new Dtos.GatewaySyncRequest("android", "rqn1_ws_2",
                List.of(new Dtos.GatewayMessageReport("m-ws2", "ACKNOWLEDGED", "SOS",
                        "EMERGENCY_REPORT", "fire", 0.8, "rqn1_origin", "command",
                        1_760_000_000_000L, 1_760_000_900_000L, 1, "ws test", null))),
                "req-ws2");

        String reference = incidentRepository.findAll().get(0).getReference();

        try (Subscription subscription = open(
                tokenFor(ResponderRole.ADMIN, "ws-res"))) {

            assertThat(subscription.awaitFrame("connected")).isNotNull();

            incidents.updateStatus(reference,
                    new Dtos.UpdateStatusRequest(
                            IncidentStatus.RESOLVED, "handled", null),
                    ResQNetPrincipal.responder("op", ResponderRole.ADMIN, 1L),
                    "req-resolve");

            assertThat(subscription.awaitFrame("incident.resolved"))
                    .as("a dashboard that wants to celebrate a closure should not have"
                            + " to compare a previous state it may never have seen")
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("a gateway sync produces one summary event, not one per record")
    void gatewaySyncIsSummarisedOnce() throws Exception {
        try (Subscription subscription = open(
                tokenFor(ResponderRole.RESPONDER, "ws-sync"))) {

            assertThat(subscription.awaitFrame("connected")).isNotNull();

            gateway.ingest(new Dtos.GatewaySyncRequest("android", "rqn1_ws_3",
                    List.of(
                            new Dtos.GatewayMessageReport("m-ws3", "ACKNOWLEDGED", "SOS",
                                    "EMERGENCY_REPORT", "medical", 0.8, "rqn1_origin",
                                    "command", 1_760_000_000_000L, 1_760_000_900_000L,
                                    1, "a", null),
                            new Dtos.GatewayMessageReport("m-ws4", "ACKNOWLEDGED", "SOS",
                                    "EMERGENCY_REPORT", "fire", 0.8, "rqn1_origin2",
                                    "command", 1_760_000_000_000L, 1_760_000_900_000L,
                                    1, "b", null))),
                    "req-ws3");

            String sync = subscription.awaitFrame("gateway.sync");

            assertThat(sync)
                    .as("a handset reconnecting after a gap may report dozens of records;"
                            + " fifty identical frames tell a dashboard nothing one does not")
                    .isNotNull();

            assertThat(sync).contains("2 record(s)");
        }
    }

    @Test
    @DisplayName("a rolled back transaction publishes nothing")
    void rollbackPublishesNothing() throws Exception {
        try (Subscription subscription = open(
                tokenFor(ResponderRole.RESPONDER, "ws-rb"))) {

            assertThat(subscription.awaitFrame("connected")).isNotNull();

            // A transaction that stages events and then rolls back.
            assertThatThrownBy(() -> failingTransaction.executeWithoutResult(status -> {
                incidents.createManually(
                        new Dtos.CreateIncidentRequest(
                                "rolled back", Category.FLOODING, 3, null, null),
                        ResQNetPrincipal.responder("op", ResponderRole.ADMIN, 1L),
                        "req-rollback");

                throw new IllegalStateException("simulated failure after staging");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(subscription.awaitFrame("incident.created", 600))
                    .as("an event about a change that never committed is worse than no event"
                            + " at all: the operator opens the incident and finds nothing")
                    .isNull();

            assertThat(incidentRepository.findAll())
                    .as("and nothing was written either")
                    .noneMatch(i -> "rolled back".equals(i.getTitle()));
        }
    }

    // ------------------------------------------------------------- plumbing

    private String findAnyReference() {
        return incidentRepository.findAll().stream()
                .map(dev.resqnet.backend.domain.Incident::getReference)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no incident to update"));
    }

    private String tokenFor(ResponderRole role, String username) {
        ResponderAccount account = accounts.save(new ResponderAccount(
                username + "-" + System.nanoTime(),
                username,
                encoder.encode("pw"),
                role));

        return jwt.issue(account.getUsername(), role, account.getId());
    }

    private boolean upgradeSucceeds(String token) {
        try (Subscription subscription = open(token)) {
            return subscription != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Opens a socket, returning null when the handshake was refused.
     *
     * <p>Uses the subprotocol channel, which is the mechanism a browser can
     * actually use and the one the interceptor documents. The query-parameter
     * fallback exists for command line tools and is deliberately not what a
     * browser would rely on.
     */
    private Subscription open(String token) throws Exception {
        String url = "ws://localhost:" + port + "/ws/incidents";

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();

        headers.setSecWebSocketProtocol(token == null
                ? List.of(WebSocketAuthInterceptor.PROTOCOL)
                : List.of(
                        WebSocketAuthInterceptor.PROTOCOL,
                        WebSocketAuthInterceptor.CREDENTIAL_PREFIX + token));

        WebSocketSession session = new StandardWebSocketClient()
                .execute(new RecordingHandler(received), headers, URI.create(url))
                .get();

        if (!session.isOpen()) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return null;
        }

        return new Subscription(session);
    }

    /** Keeps one socket open for the duration of a test. */
    private final class Subscription implements AutoCloseable {
        private final WebSocketSession session;

        Subscription(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void close() throws Exception {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        }

        /** Waits for a frame whose "type" is the given value. Null on timeout. */
        String awaitFrame(String type) throws InterruptedException {
            return awaitFrame(type, 4000);
        }

        String awaitFrame(String type, long millis) throws InterruptedException {
            String needle = "\"type\":\"" + type + "\"";
            long deadline = System.currentTimeMillis() + millis;

            while (System.currentTimeMillis() < deadline) {
                for (String frame : received) {
                    if (frame.contains(needle)) {
                        return frame;
                    }
                }

                Thread.sleep(40);
            }

            return null;
        }
    }

    private static final class RecordingHandler extends TextWebSocketHandler {
        private final List<String> sink;

        RecordingHandler(List<String> sink) {
            this.sink = sink;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            sink.add(message.getPayload());
        }
    }
}