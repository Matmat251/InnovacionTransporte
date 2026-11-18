package pe.edu.utp.innovaciontransporte.parcels;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.utp.innovaciontransporte.common.AuditService;
import pe.edu.utp.innovaciontransporte.common.JdbcHelper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ParcelsAndReportsController {

    private static final Map<String, String> VALID_TRANSITIONS = Map.of(
            "RECIBIDA", "EN_TRANSPORTE",
            "EN_TRANSPORTE", "ENTREGADA"
    );

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ParcelsAndReportsController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @PostMapping("/parcels")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createParcel(@Valid @RequestBody CreateParcelRequest body, Authentication auth) {
        List<Map<String, Object>> tripRows = jdbc.queryForList(
                "SELECT id, route_id, origin_terminal_code, destination_terminal_code FROM trips WHERE id = ?",
                body.tripId()
        );
        if (tripRows.isEmpty()) {
            throw new NoSuchElementException("Viaje no encontrado: " + body.tripId());
        }
        Map<String, Object> trip = tripRows.get(0);
        UUID parcelId = UUID.randomUUID();
        String trackingCode = "ENC-" + parcelId.toString().substring(0, 8).toUpperCase();
        String actor = auth != null ? auth.getName() : "sistema";

        jdbc.update(
                """
                INSERT INTO parcels (id, tracking_code, route_id, trip_id, sender_name, recipient_name, origin_terminal_code, destination_terminal_code, weight_kg, amount_pen, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECIBIDA')
                """,
                parcelId, trackingCode, JdbcHelper.uuid(trip.get("route_id")), body.tripId(),
                body.senderName().trim(), body.recipientName().trim(),
                trip.get("origin_terminal_code"), trip.get("destination_terminal_code"),
                body.weightKg(), body.amountPen().setScale(2, RoundingMode.HALF_UP)
        );
        jdbc.update(
                """
                INSERT INTO parcel_status_history (id, parcel_id, from_status, to_status, actor_username, notes)
                VALUES (?, ?, NULL, 'RECIBIDA', ?, 'Recepción en terminal de origen')
                """,
                UUID.randomUUID(), parcelId, actor
        );
        audit.record(auth, "parcels", "CREATE_PARCEL", "PARCEL", parcelId.toString(),
                "Encomienda registrada tracking=" + trackingCode + " pesoKg=" + body.weightKg());
        return Map.of(
                "id", parcelId,
                "trackingCode", trackingCode,
                "tripId", body.tripId(),
                "weightKg", body.weightKg(),
                "amountPen", body.amountPen().setScale(2, RoundingMode.HALF_UP),
                "status", "RECIBIDA"
        );
    }

    @GetMapping("/parcels")
    public Map<String, Object> listParcels(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String trackingCode,
            @RequestParam(required = false) UUID tripId
    ) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, page) * safeSize;
        String codeLike = trackingCode == null || trackingCode.isBlank() ? "%" : "%" + trackingCode.trim().toUpperCase() + "%";
        String tripStr = tripId != null ? tripId.toString() : "";

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM parcels WHERE UPPER(tracking_code) LIKE ? AND (? = '' OR CAST(trip_id AS VARCHAR) = ?)",
                Long.class,
                codeLike, tripStr, tripStr
        );

        List<Map<String, Object>> items = jdbc.query(
                """
                SELECT id, tracking_code, route_id, trip_id, sender_name, recipient_name,
                       origin_terminal_code, destination_terminal_code, weight_kg, amount_pen, status, created_at, updated_at
                FROM parcels
                WHERE UPPER(tracking_code) LIKE ? AND (? = '' OR CAST(trip_id AS VARCHAR) = ?)
                ORDER BY created_at DESC, tracking_code ASC
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> {
                    UUID parcelId = JdbcHelper.uuid(rs.getString("id"));
                    List<Map<String, Object>> history = jdbc.query(
                            """
                            SELECT from_status, to_status, actor_username, notes, changed_at
                            FROM parcel_status_history WHERE parcel_id = ? ORDER BY changed_at ASC
                            """,
                            (hrs, hi) -> Map.of(
                                    "fromStatus", hrs.getString("from_status") != null ? hrs.getString("from_status") : "",
                                    "toStatus", hrs.getString("to_status"),
                                    "actorUsername", hrs.getString("actor_username"),
                                    "notes", hrs.getString("notes") != null ? hrs.getString("notes") : "",
                                    "changedAtUtc", JdbcHelper.instant(hrs, "changed_at").toString()
                            ),
                            parcelId
                    );
                    return Map.ofEntries(
                            Map.entry("id", parcelId),
                            Map.entry("trackingCode", rs.getString("tracking_code")),
                            Map.entry("tripId", JdbcHelper.uuid(rs.getString("trip_id"))),
                            Map.entry("senderName", rs.getString("sender_name")),
                            Map.entry("recipientName", rs.getString("recipient_name")),
                            Map.entry("originTerminalCode", rs.getString("origin_terminal_code")),
                            Map.entry("destinationTerminalCode", rs.getString("destination_terminal_code")),
                            Map.entry("weightKg", rs.getBigDecimal("weight_kg")),
                            Map.entry("amountPen", rs.getBigDecimal("amount_pen")),
                            Map.entry("status", rs.getString("status")),
                            Map.entry("updatedAtUtc", JdbcHelper.instant(rs, "updated_at").toString()),
                            Map.entry("history", history)
                    );
                },
                codeLike, tripStr, tripStr, safeSize, offset
        );
        return JdbcHelper.pageResponse(items, page, safeSize, total != null ? total : 0L);
    }

    @PostMapping("/parcels/{id}/status-transitions")
    @Transactional
    public Map<String, Object> transitionParcelStatus(
            @PathVariable UUID id,
            @Valid @RequestBody TransitionParcelRequest body,
            Authentication auth
    ) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, tracking_code, status FROM parcels WHERE id = ? FOR UPDATE",
                id
        );
        if (rows.isEmpty()) {
            throw new NoSuchElementException("Encomienda no encontrada: " + id);
        }
        Map<String, Object> parcel = rows.get(0);
        String currentStatus = String.valueOf(parcel.get("status"));
        String expectedNext = VALID_TRANSITIONS.get(currentStatus);

        if (expectedNext == null || !expectedNext.equals(body.targetStatus())) {
            throw new IllegalStateException(
                    "Transición inválida de encomienda: " + currentStatus + " -> " + body.targetStatus()
                            + ". Solo se permite RECIBIDA -> EN_TRANSPORTE -> ENTREGADA."
            );
        }

        String actor = auth != null ? auth.getName() : "sistema";
        jdbc.update(
                "UPDATE parcels SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                body.targetStatus(), id
        );
        jdbc.update(
                """
                INSERT INTO parcel_status_history (id, parcel_id, from_status, to_status, actor_username, notes)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), id, currentStatus, body.targetStatus(), actor, body.notes()
        );
        audit.record(auth, "parcels", "TRANSITION_PARCEL", "PARCEL", id.toString(),
                "Estado encomienda " + currentStatus + " -> " + body.targetStatus());
        return Map.of(
                "id", id,
                "trackingCode", String.valueOf(parcel.get("tracking_code")),
                "previousStatus", currentStatus,
                "status", body.targetStatus()
        );
    }

    @GetMapping("/reports/operations")
    public Map<String, Object> operationsReport() {
        BigDecimal ticketRevenuePen = JdbcHelper.decimal(
                jdbc.queryForObject("SELECT COALESCE(SUM(amount_pen), 0) FROM sales WHERE status = 'VIGENTE'", BigDecimal.class)
        ).setScale(2, RoundingMode.HALF_UP);
        BigDecimal parcelRevenuePen = JdbcHelper.decimal(
                jdbc.queryForObject("SELECT COALESCE(SUM(amount_pen), 0) FROM parcels", BigDecimal.class)
        ).setScale(2, RoundingMode.HALF_UP);

        Integer validTickets = jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE status = 'EMITIDO'", Integer.class);
        Integer enabledSeats = jdbc.queryForObject("SELECT COUNT(*) FROM trip_seats WHERE enabled = TRUE", Integer.class);
        Integer totalTrips = jdbc.queryForObject("SELECT COUNT(*) FROM trips", Integer.class);
        Integer tripsWithActualTimes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trips WHERE actual_arrival_utc IS NOT NULL", Integer.class);
        Integer tripsWithValidWeather = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT trip_id) FROM weather_observations WHERE data_status IN ('VIGENTE', 'DEMO') AND expires_at_utc > CURRENT_TIMESTAMP",
                Integer.class);

        int sold = validTickets != null ? validTickets : 0;
        int enabled = enabledSeats != null ? enabledSeats : 0;
        double occupancyPct = enabled > 0
                ? BigDecimal.valueOf((sold * 100.0) / enabled).setScale(2, RoundingMode.HALF_UP).doubleValue()
                : 0.0;

        Integer recibida = jdbc.queryForObject("SELECT COUNT(*) FROM parcels WHERE status = 'RECIBIDA'", Integer.class);
        Integer enTransporte = jdbc.queryForObject("SELECT COUNT(*) FROM parcels WHERE status = 'EN_TRANSPORTE'", Integer.class);
        Integer entregada = jdbc.queryForObject("SELECT COUNT(*) FROM parcels WHERE status = 'ENTREGADA'", Integer.class);

        return Map.ofEntries(
                Map.entry("currency", "PEN"),
                Map.entry("validTicketRevenuePen", ticketRevenuePen),
                Map.entry("parcelRevenuePen", parcelRevenuePen),
                Map.entry("totalRevenuePen", ticketRevenuePen.add(parcelRevenuePen).setScale(2, RoundingMode.HALF_UP)),
                Map.entry("validTicketsCount", sold),
                Map.entry("enabledSeatsCount", enabled),
                Map.entry("occupancyPct", occupancyPct),
                Map.entry("totalTrips", totalTrips != null ? totalTrips : 0),
                Map.entry("tripsWithValidWeather", tripsWithValidWeather != null ? tripsWithValidWeather : 0),
                Map.entry("tripsWithActualTimes", tripsWithActualTimes != null ? tripsWithActualTimes : 0),
                Map.entry("parcelsByStatus", Map.of(
                        "RECIBIDA", recibida != null ? recibida : 0,
                        "EN_TRANSPORTE", enTransporte != null ? enTransporte : 0,
                        "ENTREGADA", entregada != null ? entregada : 0
                ))
        );
    }

    public record CreateParcelRequest(
            @NotNull UUID tripId,
            @NotBlank String senderName,
            @NotBlank String recipientName,
            @NotNull @DecimalMin("0.1") BigDecimal weightKg,
            @NotNull @DecimalMin("1.00") BigDecimal amountPen
    ) {
    }

    public record TransitionParcelRequest(
            @Pattern(regexp = "RECIBIDA|EN_TRANSPORTE|ENTREGADA") String targetStatus,
            String notes
    ) {
    }
}
