package pe.edu.utp.innovaciontransporte;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.risk-service-url=http://127.0.0.1:19999",
                "app.open-meteo-base-url=http://127.0.0.1:19998"
        }
)
@ActiveProfiles("test")
class OperationalFlowsIntegrationTest {

    private static final String TRIP_1_ID = "55555555-5555-5555-5555-555555555501";
    private static final String TRIP_2_ID = "55555555-5555-5555-5555-555555555502";
    private static final String BUS_VIABLE_ID = "66666666-6666-6666-6666-666666666602";
    private static final String PASSENGER_1_ID = "70000000-0000-0000-0000-000000000001";
    private static final String HYO_TICLIO_SEGMENT_ID = "44444444-4444-4444-4444-444444444503";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void rbacAndCsrfProtectionWorkAcrossRoles() throws Exception {
        SessionClient operador = login("operador.hyo", "Demo2026!");
        HttpResponse<String> meOp = operador.get("/api/v1/auth/me");
        assertThat(meOp.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(meOp.body()).path("role").asText()).isEqualTo("OPERADOR");

        HttpResponse<String> forbiddenUsers = operador.get("/api/v1/users");
        assertThat(forbiddenUsers.statusCode()).isEqualTo(403);

        // Mutación sin cabecera CSRF debe rechazarse con 403
        HttpResponse<String> missingCsrf = operador.postRawWithoutCsrf("/api/v1/passengers",
                Map.of("documentType", "DNI", "documentNumber", "79999999", "fullName", "Sin CSRF"));
        assertThat(missingCsrf.statusCode()).isEqualTo(403);

        SessionClient admin = login("admin", "Demo2026!");
        HttpResponse<String> okUsers = admin.get("/api/v1/users");
        assertThat(okUsers.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(okUsers.body()).path("items").size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void concurrentSalesEnforceSingleSeatLockIdempotencyAndCancellation() throws Exception {
        SessionClient clientA = login("operador.hyo", "Demo2026!");
        SessionClient clientB = login("admin", "Demo2026!");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);

        Callable<HttpResponse<String>> buySeat25A = () -> {
            ready.countDown();
            start.await();
            return clientA.post("/api/v1/sales",
                    Map.of("tripId", TRIP_2_ID, "seatNumber", 25, "passengerId", PASSENGER_1_ID, "amountPen", "65.00"),
                    "idem-seat-25-a");
        };
        Callable<HttpResponse<String>> buySeat25B = () -> {
            ready.countDown();
            start.await();
            return clientB.post("/api/v1/sales",
                    Map.of("tripId", TRIP_2_ID, "seatNumber", 25, "passengerId", PASSENGER_1_ID, "amountPen", "65.00"),
                    "idem-seat-25-b");
        };

        Future<HttpResponse<String>> fA = pool.submit(buySeat25A);
        Future<HttpResponse<String>> fB = pool.submit(buySeat25B);
        ready.await();
        start.countDown();

        HttpResponse<String> rA = fA.get();
        HttpResponse<String> rB = fB.get();
        pool.shutdown();

        List<Integer> statuses = List.of(rA.statusCode(), rB.statusCode());
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);

        HttpResponse<String> winner = rA.statusCode() == 201 ? rA : rB;
        SessionClient winnerClient = rA.statusCode() == 201 ? clientA : clientB;
        String winnerKey = rA.statusCode() == 201 ? "idem-seat-25-a" : "idem-seat-25-b";

        JsonNode winnerJson = objectMapper.readTree(winner.body());
        String saleId = winnerJson.path("saleId").asText();
        String ticketId = winnerJson.path("ticketId").asText();

        // Reintento con igual clave de idempotencia y contenido recupera la misma respuesta
        HttpResponse<String> replay = winnerClient.post("/api/v1/sales",
                Map.of("tripId", TRIP_2_ID, "seatNumber", 25, "passengerId", PASSENGER_1_ID, "amountPen", "65.00"),
                winnerKey);
        assertThat(replay.statusCode()).isEqualTo(201);
        JsonNode replayJson = objectMapper.readTree(replay.body());
        assertThat(replayJson.path("saleId").asText()).isEqualTo(saleId);
        assertThat(replayJson.path("ticketId").asText()).isEqualTo(ticketId);
        assertThat(replayJson.path("idempotentReplay").asBoolean()).isTrue();

        // Misma clave con contenido diferente devuelve 409 Conflict
        HttpResponse<String> mismatch = winnerClient.post("/api/v1/sales",
                Map.of("tripId", TRIP_2_ID, "seatNumber", 26, "passengerId", PASSENGER_1_ID, "amountPen", "65.00"),
                winnerKey);
        assertThat(mismatch.statusCode()).isEqualTo(409);

        // Anulación libera asiento y permite consultar boleto anulado
        HttpResponse<String> cancel = winnerClient.post("/api/v1/sales/" + saleId + "/cancel",
                Map.of("reason", "Cambio solicitado por pasajero"), null);
        assertThat(cancel.statusCode()).isEqualTo(200);

        HttpResponse<String> ticketRes = winnerClient.get("/api/v1/tickets/" + ticketId);
        assertThat(ticketRes.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(ticketRes.body()).path("status").asText()).isEqualTo("ANULADO");
    }

    @Test
    void parcelsEnforceStateTransitionsAndReportsSeparateRevenues() throws Exception {
        SessionClient op = login("operador.hyo", "Demo2026!");

        HttpResponse<String> created = op.post("/api/v1/parcels",
                Map.of("tripId", TRIP_1_ID, "senderName", "Remitente Test", "recipientName", "Destinatario Test",
                        "weightKg", 12.5, "amountPen", "30.00"),
                null);
        assertThat(created.statusCode()).isEqualTo(201);
        String parcelId = objectMapper.readTree(created.body()).path("id").asText();

        // Transición inválida RECIBIDA -> ENTREGADA devuelve 409
        HttpResponse<String> invalid = op.post("/api/v1/parcels/" + parcelId + "/status-transitions",
                Map.of("targetStatus", "ENTREGADA", "notes", "Salto inválido"), null);
        assertThat(invalid.statusCode()).isEqualTo(409);

        // Transición válida RECIBIDA -> EN_TRANSPORTE -> ENTREGADA
        HttpResponse<String> step1 = op.post("/api/v1/parcels/" + parcelId + "/status-transitions",
                Map.of("targetStatus", "EN_TRANSPORTE", "notes", "Despachada en ruta"), null);
        assertThat(step1.statusCode()).isEqualTo(200);

        HttpResponse<String> step2 = op.post("/api/v1/parcels/" + parcelId + "/status-transitions",
                Map.of("targetStatus", "ENTREGADA", "notes", "Entregada en terminal"), null);
        assertThat(step2.statusCode()).isEqualTo(200);

        HttpResponse<String> report = op.get("/api/v1/reports/operations");
        assertThat(report.statusCode()).isEqualTo(200);
        JsonNode reportJson = objectMapper.readTree(report.body());
        assertThat(reportJson.path("currency").asText()).isEqualTo("PEN");
        assertThat(reportJson.path("validTicketRevenuePen").asDouble()).isGreaterThanOrEqualTo(1560.0);
        assertThat(reportJson.path("parcelRevenuePen").asDouble()).isGreaterThanOrEqualTo(230.0);
        assertThat(reportJson.path("occupancyPct").asDouble()).isGreaterThan(0.0);
    }

    @Test
    void contingencyAcceptanceDemoHandlesReserveBusStaleVersionClosureAndRiskFallback() throws Exception {
        SessionClient op = login("operador.hyo", "Demo2026!");

        // Degradación controlada cuando FastAPI y Open-Meteo están caídos
        HttpResponse<String> riskDown = op.post("/api/v1/trips/" + TRIP_1_ID + "/risk-assessments", Map.of(), null);
        assertThat(riskDown.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(riskDown.body()).path("status").asText()).isEqualTo("INFORMACION_INSUFICIENTE");

        // Simulación sobre viaje 15:40 -> 16:15 (24 pasajes y 8 encomiendas)
        HttpResponse<String> simRes = op.post("/api/v1/trips/" + TRIP_1_ID + "/contingency-simulations",
                Map.of("delayScenariosMinutes", List.of(30, 60)), null);
        assertThat(simRes.statusCode()).isEqualTo(200);
        JsonNode sim = objectMapper.readTree(simRes.body());
        assertThat(sim.path("marginMinutes").asInt()).isEqualTo(15);
        assertThat(sim.path("delay30LostMinutes").asInt()).isEqualTo(15);
        assertThat(sim.path("delay60LostMinutes").asInt()).isEqualTo(45);
        assertThat(sim.path("soldTicketsCount").asInt()).isGreaterThanOrEqualTo(24);
        assertThat(sim.path("parcelCount").asInt()).isEqualTo(8);

        boolean c4r902Viable = false;
        boolean c4r903Rejected = false;
        boolean altPending = false;
        for (JsonNode opt : sim.path("options")) {
            String code = opt.path("optionCode").asText();
            if ("BUS_C4R-902".equals(code)) {
                c4r902Viable = opt.path("viable").asBoolean() && opt.path("confirmable").asBoolean();
            } else if ("BUS_C4R-903".equals(code)) {
                c4r903Rejected = !opt.path("viable").asBoolean()
                        && opt.path("reason").asText().contains("Asientos habilitados insuficientes");
            } else if ("ALT_HYO-LIM-ALT-01".equals(code)) {
                altPending = !opt.path("confirmable").asBoolean()
                        && "Por verificar".equals(opt.path("statusLabel").asText());
            }
        }
        assertThat(c4r902Viable).isTrue();
        assertThat(c4r903Rejected).isTrue();
        assertThat(altPending).isTrue();

        // Confirmar cambio al bus de reserva viable C4R-902
        String simulationId = sim.path("simulationId").asText();
        int tripVersion = sim.path("affectedTripVersion").asInt();

        HttpResponse<String> decisionRes = op.post("/api/v1/trips/" + TRIP_1_ID + "/contingency-decisions",
                Map.of(
                        "simulationId", simulationId,
                        "expectedTripVersion", tripVersion,
                        "actionType", "ASIGNAR_BUS_RESERVA",
                        "reserveBusId", BUS_VIABLE_ID,
                        "rationale", "Mitigar demora de 15 min usando bus de reserva en HYO-TERM"
                ),
                null);
        assertThat(decisionRes.statusCode()).isEqualTo(200);
        JsonNode decisionJson = objectMapper.readTree(decisionRes.body());
        assertThat(decisionJson.path("assignedBusId").asText()).isEqualTo(BUS_VIABLE_ID);
        assertThat(decisionJson.path("reassignedParcels").asInt()).isEqualTo(8);
        assertThat(decisionJson.path("newTripVersion").asInt()).isEqualTo(tripVersion + 1);

        // Reintento con versión desactualizada debe devolver 409 Conflict
        HttpResponse<String> stale = op.post("/api/v1/trips/" + TRIP_1_ID + "/contingency-decisions",
                Map.of(
                        "simulationId", simulationId,
                        "expectedTripVersion", tripVersion,
                        "actionType", "MANTENER_PLAN",
                        "rationale", "Intento desactualizado"
                ),
                null);
        assertThat(stale.statusCode()).isEqualTo(409);

        // Registrar cierre vial vigente en Ticlio -> excluye el itinerario y bloquea confirmar MANTENER_PLAN
        HttpResponse<String> alertRes = op.post("/api/v1/road-alerts",
                Map.of(
                        "segmentId", HYO_TICLIO_SEGMENT_ID,
                        "sourceName", "SUTRAN",
                        "referenceUrl", "https://alertasviales.dev.sutran.gob.pe/",
                        "status", "CERRADO",
                        "originType", "OFICIAL_MANUAL",
                        "description", "Cierre temporal por deslizamiento en Ticlio",
                        "validUntilUtc", Instant.now().plus(6, ChronoUnit.HOURS).toString()
                ),
                null);
        assertThat(alertRes.statusCode()).isEqualTo(201);

        HttpResponse<String> simClosedRes = op.post("/api/v1/trips/" + TRIP_1_ID + "/contingency-simulations",
                Map.of("delayScenariosMinutes", List.of(30, 60)), null);
        assertThat(simClosedRes.statusCode()).isEqualTo(200);
        JsonNode simClosed = objectMapper.readTree(simClosedRes.body());
        assertThat(simClosed.path("hasActiveRoadClosure").asBoolean()).isTrue();
        assertThat(simClosed.path("options").get(0).path("confirmable").asBoolean()).isFalse();

        HttpResponse<String> blockedConfirm = op.post("/api/v1/trips/" + TRIP_1_ID + "/contingency-decisions",
                Map.of(
                        "simulationId", simClosed.path("simulationId").asText(),
                        "expectedTripVersion", simClosed.path("affectedTripVersion").asInt(),
                        "actionType", "MANTENER_PLAN",
                        "rationale", "No debe permitirse con cierre vigente"
                ),
                null);
        assertThat(blockedConfirm.statusCode()).isEqualTo(409);
    }

    private SessionClient login(String username, String password) throws Exception {
        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = HttpClient.newBuilder().cookieHandler(cm).build();
        SessionClient client = new SessionClient("http://127.0.0.1:" + port, http, cm, objectMapper);
        HttpResponse<String> loginRes = client.post("/api/v1/auth/login",
                Map.of("username", username, "password", password), null);
        assertThat(loginRes.statusCode()).isEqualTo(200);
        client.refreshCsrf();
        return client;
    }

    private static final class SessionClient {
        private final String baseUrl;
        private final HttpClient http;
        private final CookieManager cm;
        private final ObjectMapper mapper;
        private String csrfToken = "";

        SessionClient(String baseUrl, HttpClient http, CookieManager cm, ObjectMapper mapper) {
            this.baseUrl = baseUrl;
            this.http = http;
            this.cm = cm;
            this.mapper = mapper;
        }

        void refreshCsrf() throws Exception {
            HttpResponse<String> r = get("/api/v1/auth/csrf");
            if (r.statusCode() == 200) {
                csrfToken = mapper.readTree(r.body()).path("token").asText();
            }
            cm.getCookieStore().getCookies().stream()
                    .filter(c -> "XSRF-TOKEN".equals(c.getName()))
                    .findFirst()
                    .ifPresent(c -> csrfToken = c.getValue());
        }

        HttpResponse<String> get(String path) throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, Object body, String idempotencyKey) throws Exception {
            String json = mapper.writeValueAsString(body);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (csrfToken != null && !csrfToken.isBlank()) {
                b.header("X-XSRF-TOKEN", csrfToken);
            }
            if (idempotencyKey != null) {
                b.header("Idempotency-Key", idempotencyKey);
            }
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> postRawWithoutCsrf(String path, Object body) throws Exception {
            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        }
    }
}
