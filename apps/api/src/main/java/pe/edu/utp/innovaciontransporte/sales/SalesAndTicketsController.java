package pe.edu.utp.innovaciontransporte.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.utp.innovaciontransporte.common.AuditService;
import pe.edu.utp.innovaciontransporte.common.JdbcHelper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class SalesAndTicketsController {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public SalesAndTicketsController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @PostMapping("/sales")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createSale(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateSaleRequest body,
            Authentication auth
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("La cabecera Idempotency-Key es obligatoria.");
        }
        String key = idempotencyKey.trim();
        BigDecimal normalizedAmount = body.amountPen().setScale(2, RoundingMode.HALF_UP);
        String payloadHash = sha256(body.tripId() + "|" + body.seatNumber() + "|" + body.passengerId() + "|" + normalizedAmount.toPlainString());

        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, payload_hash FROM sales WHERE idempotency_key = ?",
                key
        );
        if (!existing.isEmpty()) {
            String storedHash = String.valueOf(existing.get(0).get("payload_hash"));
            if (!storedHash.equals(payloadHash)) {
                throw new IllegalStateException("La clave de idempotencia ya fue utilizada con un contenido diferente.");
            }
            UUID existingSaleId = JdbcHelper.uuid(existing.get(0).get("id"));
            return loadSaleResponse(existingSaleId, true);
        }

        List<Map<String, Object>> seatRows = jdbc.queryForList(
                "SELECT id, enabled, status FROM trip_seats WHERE trip_id = ? AND seat_number = ? FOR UPDATE",
                body.tripId(),
                body.seatNumber()
        );
        if (seatRows.isEmpty()) {
            throw new NoSuchElementException("Asiento o viaje no encontrado.");
        }
        Map<String, Object> seat = seatRows.get(0);
        boolean enabled = Boolean.TRUE.equals(seat.get("enabled"));
        String seatStatus = String.valueOf(seat.get("status"));
        if (!enabled || !"DISPONIBLE".equals(seatStatus)) {
            throw new IllegalStateException("El asiento " + body.seatNumber() + " no está disponible para venta.");
        }

        UUID saleId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        String ticketCode = "TKT-" + ticketId.toString().substring(0, 8).toUpperCase();
        String actor = auth != null ? auth.getName() : "sistema";

        jdbc.update(
                """
                INSERT INTO sales (id, trip_id, passenger_id, seat_number, amount_pen, status, idempotency_key, payload_hash, sold_by)
                VALUES (?, ?, ?, ?, ?, 'VIGENTE', ?, ?, ?)
                """,
                saleId, body.tripId(), body.passengerId(), body.seatNumber(), normalizedAmount, key, payloadHash, actor
        );
        jdbc.update(
                """
                INSERT INTO tickets (id, sale_id, trip_id, passenger_id, seat_number, ticket_code, amount_pen, status, active_seat_lock)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'EMITIDO', ?)
                """,
                ticketId, saleId, body.tripId(), body.passengerId(), body.seatNumber(), ticketCode, normalizedAmount,
                body.tripId() + ":" + body.seatNumber()
        );
        jdbc.update(
                "UPDATE trip_seats SET status = 'VENDIDO', active_ticket_id = ? WHERE id = ?",
                ticketId,
                JdbcHelper.uuid(seat.get("id"))
        );

        audit.record(auth, "sales", "CREATE_SALE", "SALE", saleId.toString(),
                "Venta registrada viaje=" + body.tripId() + " asiento=" + body.seatNumber() + " montoPEN=" + normalizedAmount);
        return loadSaleResponse(saleId, false);
    }

    @PostMapping("/sales/{id}/cancel")
    @Transactional
    public Map<String, Object> cancelSale(
            @PathVariable UUID id,
            @Valid @RequestBody CancelSaleRequest body,
            Authentication auth
    ) {
        List<Map<String, Object>> saleRows = jdbc.queryForList(
                "SELECT id, trip_id, seat_number, status FROM sales WHERE id = ? FOR UPDATE",
                id
        );
        if (saleRows.isEmpty()) {
            throw new NoSuchElementException("Venta no encontrada: " + id);
        }
        Map<String, Object> sale = saleRows.get(0);
        if (!"VIGENTE".equals(String.valueOf(sale.get("status")))) {
            throw new IllegalStateException("La venta ya se encuentra anulada.");
        }

        UUID tripId = JdbcHelper.uuid(sale.get("trip_id"));
        int seatNumber = ((Number) sale.get("seat_number")).intValue();
        String actor = auth != null ? auth.getName() : "sistema";

        jdbc.update(
                "UPDATE sales SET status = 'ANULADA', cancelled_by = ?, cancel_reason = ?, cancelled_at = CURRENT_TIMESTAMP WHERE id = ?",
                actor, body.reason().trim(), id
        );
        jdbc.update(
                "UPDATE tickets SET status = 'ANULADO', active_seat_lock = NULL WHERE sale_id = ?",
                id
        );
        jdbc.update(
                "UPDATE trip_seats SET status = 'DISPONIBLE', active_ticket_id = NULL WHERE trip_id = ? AND seat_number = ?",
                tripId, seatNumber
        );

        audit.record(auth, "sales", "CANCEL_SALE", "SALE", id.toString(), "Venta anulada asiento=" + seatNumber);
        return Map.of(
                "saleId", id,
                "status", "ANULADA",
                "tripId", tripId,
                "seatNumber", seatNumber
        );
    }

    @GetMapping("/tickets/{id}")
    public Map<String, Object> getTicket(@PathVariable UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT tk.id AS ticket_id, tk.sale_id, tk.trip_id, tk.seat_number, tk.ticket_code, tk.amount_pen, tk.status, tk.issued_at,
                       p.full_name AS passenger_name, r.code AS route_code, t.scheduled_departure_utc, b.plate AS bus_plate
                FROM tickets tk
                JOIN passengers p ON p.id = tk.passenger_id
                JOIN trips t ON t.id = tk.trip_id
                JOIN routes r ON r.id = t.route_id
                JOIN buses b ON b.id = t.bus_id
                WHERE tk.id = ?
                """,
                id
        );
        if (rows.isEmpty()) {
            throw new NoSuchElementException("Boleto interno no encontrado: " + id);
        }
        Map<String, Object> row = rows.get(0);
        return Map.ofEntries(
                Map.entry("ticketId", JdbcHelper.uuid(row.get("ticket_id"))),
                Map.entry("saleId", JdbcHelper.uuid(row.get("sale_id"))),
                Map.entry("tripId", JdbcHelper.uuid(row.get("trip_id"))),
                Map.entry("seatNumber", ((Number) row.get("seat_number")).intValue()),
                Map.entry("ticketCode", String.valueOf(row.get("ticket_code"))),
                Map.entry("amountPen", JdbcHelper.decimal(row.get("amount_pen"))),
                Map.entry("status", String.valueOf(row.get("status"))),
                Map.entry("passengerName", String.valueOf(row.get("passenger_name"))),
                Map.entry("routeCode", String.valueOf(row.get("route_code"))),
                Map.entry("busPlate", String.valueOf(row.get("bus_plate"))),
                Map.entry("scheduledDepartureUtc", JdbcHelper.instant(row.get("scheduled_departure_utc")).toString()),
                Map.entry("issuedAtUtc", JdbcHelper.instant(row.get("issued_at")).toString()),
                Map.entry("documentType", "BOLETO_INTERNO_NO_FISCAL")
        );
    }

    private Map<String, Object> loadSaleResponse(UUID saleId, boolean idempotentReplay) {
        Map<String, Object> row = jdbc.queryForMap(
                """
                SELECT s.id AS sale_id, s.trip_id, s.seat_number, s.amount_pen, s.status AS sale_status,
                       tk.id AS ticket_id, tk.ticket_code, tk.status AS ticket_status
                FROM sales s
                JOIN tickets tk ON tk.sale_id = s.id
                WHERE s.id = ?
                """,
                saleId
        );
        return Map.of(
                "saleId", JdbcHelper.uuid(row.get("sale_id")),
                "ticketId", JdbcHelper.uuid(row.get("ticket_id")),
                "ticketCode", String.valueOf(row.get("ticket_code")),
                "tripId", JdbcHelper.uuid(row.get("trip_id")),
                "seatNumber", ((Number) row.get("seat_number")).intValue(),
                "amountPen", JdbcHelper.decimal(row.get("amount_pen")),
                "saleStatus", String.valueOf(row.get("sale_status")),
                "ticketStatus", String.valueOf(row.get("ticket_status")),
                "idempotentReplay", idempotentReplay
        );
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo calcular hash de idempotencia.", ex);
        }
    }

    public record CreateSaleRequest(
            @NotNull UUID tripId,
            @Min(1) int seatNumber,
            @NotNull UUID passengerId,
            @NotNull @DecimalMin("1.00") BigDecimal amountPen
    ) {
    }

    public record CancelSaleRequest(@NotBlank String reason) {
    }
}
