package pe.edu.utp.innovaciontransporte.trips;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.utp.innovaciontransporte.common.AuditService;
import pe.edu.utp.innovaciontransporte.common.JdbcHelper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class CatalogAndTripsController {

    private static final UUID DEMO_COMPANY_ID = UUID.fromString("11111111-1111-1111-1111-111111111101");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public CatalogAndTripsController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/passengers")
    public Map<String, Object> listPassengers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String documentNumber
    ) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, page) * safeSize;
        String filter = documentNumber == null ? "" : documentNumber.trim();
        String like = "%" + filter + "%";

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM passengers WHERE document_number LIKE ?",
                Long.class,
                like
        );
        List<Map<String, Object>> items = jdbc.query(
                """
                SELECT id, document_type, document_number, full_name, created_at
                FROM passengers
                WHERE document_number LIKE ?
                ORDER BY full_name
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> Map.of(
                        "id", JdbcHelper.uuid(rs.getString("id")),
                        "documentType", rs.getString("document_type"),
                        "documentNumber", rs.getString("document_number"),
                        "fullName", rs.getString("full_name"),
                        "createdAtUtc", JdbcHelper.instant(rs, "created_at").toString()
                ),
                like, safeSize, offset
        );
        return JdbcHelper.pageResponse(items, page, safeSize, total != null ? total : 0L);
    }

    @PostMapping("/passengers")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createPassenger(@Valid @RequestBody CreatePassengerRequest body, Authentication auth) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO passengers (id, document_type, document_number, full_name) VALUES (?, ?, ?, ?)",
                id, body.documentType(), body.documentNumber().trim(), body.fullName().trim()
        );
        audit.record(auth, "fleet", "CREATE_PASSENGER", "PASSENGER", id.toString(), "Pasajero registrado sin PII en log");
        return Map.of(
                "id", id,
                "documentType", body.documentType(),
                "documentNumber", body.documentNumber().trim(),
                "fullName", body.fullName().trim()
        );
    }

    @GetMapping("/buses")
    public Map<String, Object> listBuses(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String terminalCode
    ) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, page) * safeSize;
        String term = terminalCode == null || terminalCode.isBlank() ? "%" : terminalCode.trim();
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM buses WHERE current_terminal_code LIKE ?",
                Long.class,
                term
        );
        List<Map<String, Object>> items = jdbc.query(
                """
                SELECT id, plate, model, seat_capacity, enabled_seats, cargo_capacity_kg, current_terminal_code, status, turnaround_minutes
                FROM buses
                WHERE current_terminal_code LIKE ?
                ORDER BY plate
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> Map.of(
                        "id", JdbcHelper.uuid(rs.getString("id")),
                        "plate", rs.getString("plate"),
                        "model", rs.getString("model"),
                        "seatCapacity", rs.getInt("seat_capacity"),
                        "enabledSeats", rs.getInt("enabled_seats"),
                        "cargoCapacityKg", rs.getBigDecimal("cargo_capacity_kg"),
                        "currentTerminalCode", rs.getString("current_terminal_code"),
                        "status", rs.getString("status"),
                        "turnaroundMinutes", rs.getInt("turnaround_minutes")
                ),
                term, safeSize, offset
        );
        return JdbcHelper.pageResponse(items, page, safeSize, total != null ? total : 0L);
    }

    @PostMapping("/buses")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createBus(@Valid @RequestBody CreateBusRequest body, Authentication auth) {
        UUID id = UUID.randomUUID();
        int enabledSeats = body.enabledSeats() != null ? body.enabledSeats() : body.seatCapacity();
        if (enabledSeats > body.seatCapacity()) {
            throw new IllegalArgumentException("enabledSeats no puede superar seatCapacity.");
        }
        jdbc.update(
                """
                INSERT INTO buses (id, company_id, plate, model, seat_capacity, enabled_seats, cargo_capacity_kg, current_terminal_code, status, turnaround_minutes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DISPONIBLE', ?)
                """,
                id, DEMO_COMPANY_ID, body.plate().trim().toUpperCase(), body.model().trim(),
                body.seatCapacity(), enabledSeats, body.cargoCapacityKg(),
                body.homeTerminalCode().trim(), body.turnaroundMinutes()
        );
        audit.record(auth, "fleet", "CREATE_BUS", "BUS", id.toString(), "Bus registrado placa " + body.plate().trim());
        return Map.of(
                "id", id,
                "plate", body.plate().trim().toUpperCase(),
                "seatCapacity", body.seatCapacity(),
                "enabledSeats", enabledSeats,
                "cargoCapacityKg", body.cargoCapacityKg(),
                "currentTerminalCode", body.homeTerminalCode().trim(),
                "status", "DISPONIBLE"
        );
    }

    @GetMapping("/routes")
    public Map<String, Object> listRoutes(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, page) * safeSize;
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM routes", Long.class);
        List<Map<String, Object>> routes = jdbc.query(
                """
                SELECT r.id, r.code, r.name, r.distance_km, r.standard_duration_minutes, r.active, r.road_status_verified,
                       o.code AS origin_code, d.code AS dest_code
                FROM routes r
                JOIN terminals o ON o.id = r.origin_terminal_id
                JOIN terminals d ON d.id = r.destination_terminal_id
                ORDER BY r.code
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> {
                    UUID routeId = JdbcHelper.uuid(rs.getString("id"));
                    List<Map<String, Object>> segments = jdbc.query(
                            """
                            SELECT id, sequence_order, name, checkpoint_name, latitude, longitude, elevation_masl, estimated_offset_minutes
                            FROM route_segments WHERE route_id = ? ORDER BY sequence_order
                            """,
                            (srs, si) -> Map.of(
                                    "id", JdbcHelper.uuid(srs.getString("id")),
                                    "sequenceOrder", srs.getInt("sequence_order"),
                                    "name", srs.getString("name"),
                                    "checkpointName", srs.getString("checkpoint_name"),
                                    "latitude", srs.getBigDecimal("latitude"),
                                    "longitude", srs.getBigDecimal("longitude"),
                                    "elevationMasl", srs.getInt("elevation_masl"),
                                    "estimatedOffsetMinutes", srs.getInt("estimated_offset_minutes")
                            ),
                            routeId
                    );
                    return Map.of(
                            "id", routeId,
                            "code", rs.getString("code"),
                            "name", rs.getString("name"),
                            "originTerminalCode", rs.getString("origin_code"),
                            "destinationTerminalCode", rs.getString("dest_code"),
                            "distanceKm", rs.getInt("distance_km"),
                            "standardDurationMinutes", rs.getInt("standard_duration_minutes"),
                            "active", rs.getBoolean("active"),
                            "roadStatusVerified", rs.getBoolean("road_status_verified"),
                            "segments", segments
                    );
                },
                safeSize, offset
        );
        return JdbcHelper.pageResponse(routes, page, safeSize, total != null ? total : 0L);
    }

    @GetMapping("/trips")
    public Map<String, Object> listTrips(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String routeCode
    ) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, page) * safeSize;
        String filter = routeCode == null || routeCode.isBlank() ? "%" : routeCode.trim();
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trips t JOIN routes r ON r.id = t.route_id WHERE r.code LIKE ?",
                Long.class,
                filter
        );
        List<Map<String, Object>> items = jdbc.query(
                """
                SELECT t.id, t.route_id, r.code AS route_code, r.name AS route_name, r.road_status_verified,
                       t.bus_id, b.plate AS bus_plate, b.seat_capacity, b.enabled_seats, b.cargo_capacity_kg,
                       t.origin_terminal_code, t.destination_terminal_code,
                       t.original_departure_utc, t.original_arrival_utc,
                       t.scheduled_departure_utc, t.scheduled_arrival_utc,
                       t.actual_departure_utc, t.actual_arrival_utc,
                       t.turnaround_minutes, t.base_fare_pen, t.status, t.incident_notes, t.previous_trip_id, t.version,
                       (SELECT COUNT(*) FROM tickets tk WHERE tk.trip_id = t.id AND tk.status = 'EMITIDO') AS sold_tickets,
                       (SELECT COUNT(*) FROM parcels p WHERE p.trip_id = t.id) AS parcel_count,
                       (SELECT COALESCE(SUM(p.weight_kg), 0) FROM parcels p WHERE p.trip_id = t.id) AS parcel_weight_kg
                FROM trips t
                JOIN routes r ON r.id = t.route_id
                JOIN buses b ON b.id = t.bus_id
                WHERE r.code LIKE ?
                ORDER BY t.scheduled_departure_utc ASC
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> {
                    Instant actDep = JdbcHelper.instant(rs, "actual_departure_utc");
                    Instant actArr = JdbcHelper.instant(rs, "actual_arrival_utc");
                    return Map.<String, Object>ofEntries(
                            Map.entry("id", JdbcHelper.uuid(rs.getString("id"))),
                            Map.entry("routeId", JdbcHelper.uuid(rs.getString("route_id"))),
                            Map.entry("routeCode", rs.getString("route_code")),
                            Map.entry("routeName", rs.getString("route_name")),
                            Map.entry("roadStatusVerified", rs.getBoolean("road_status_verified")),
                            Map.entry("busId", JdbcHelper.uuid(rs.getString("bus_id"))),
                            Map.entry("busPlate", rs.getString("bus_plate")),
                            Map.entry("seatCapacity", rs.getInt("seat_capacity")),
                            Map.entry("enabledSeats", rs.getInt("enabled_seats")),
                            Map.entry("cargoCapacityKg", rs.getBigDecimal("cargo_capacity_kg")),
                            Map.entry("originTerminalCode", rs.getString("origin_terminal_code")),
                            Map.entry("destinationTerminalCode", rs.getString("destination_terminal_code")),
                            Map.entry("originalDepartureUtc", JdbcHelper.instant(rs, "original_departure_utc").toString()),
                            Map.entry("originalArrivalUtc", JdbcHelper.instant(rs, "original_arrival_utc").toString()),
                            Map.entry("scheduledDepartureUtc", JdbcHelper.instant(rs, "scheduled_departure_utc").toString()),
                            Map.entry("scheduledArrivalUtc", JdbcHelper.instant(rs, "scheduled_arrival_utc").toString()),
                            Map.entry("actualDepartureUtc", actDep != null ? actDep.toString() : ""),
                            Map.entry("actualArrivalUtc", actArr != null ? actArr.toString() : ""),
                            Map.entry("turnaroundMinutes", rs.getInt("turnaround_minutes")),
                            Map.entry("baseFarePen", rs.getBigDecimal("base_fare_pen")),
                            Map.entry("status", rs.getString("status")),
                            Map.entry("incidentNotes", rs.getString("incident_notes") != null ? rs.getString("incident_notes") : ""),
                            Map.entry("previousTripId", rs.getString("previous_trip_id") != null ? rs.getString("previous_trip_id") : ""),
                            Map.entry("version", rs.getInt("version")),
                            Map.entry("soldTicketsCount", rs.getInt("sold_tickets")),
                            Map.entry("parcelCount", rs.getInt("parcel_count")),
                            Map.entry("totalParcelWeightKg", rs.getBigDecimal("parcel_weight_kg"))
                    );
                },
                filter, safeSize, offset
        );
        return JdbcHelper.pageResponse(items, page, safeSize, total != null ? total : 0L);
    }

    @PostMapping("/trips")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createTrip(@Valid @RequestBody CreateTripRequest body, Authentication auth) {
        List<Map<String, Object>> routeRows = jdbc.queryForList(
                """
                SELECT r.id, o.code AS origin_code, d.code AS dest_code
                FROM routes r
                JOIN terminals o ON o.id = r.origin_terminal_id
                JOIN terminals d ON d.id = r.destination_terminal_id
                WHERE r.code = ?
                """,
                body.routeCode().trim()
        );
        if (routeRows.isEmpty()) {
            throw new NoSuchElementException("Ruta no encontrada: " + body.routeCode());
        }
        Map<String, Object> route = routeRows.get(0);

        List<Map<String, Object>> busRows = jdbc.queryForList(
                "SELECT id, seat_capacity, enabled_seats FROM buses WHERE id = ?",
                body.busId()
        );
        if (busRows.isEmpty()) {
            throw new NoSuchElementException("Bus no encontrado: " + body.busId());
        }
        Map<String, Object> bus = busRows.get(0);
        int seatCap = ((Number) bus.get("seat_capacity")).intValue();
        int enabledSeats = ((Number) bus.get("enabled_seats")).intValue();

        Instant dep = Instant.parse(body.scheduledDepartureUtc());
        Instant arr = Instant.parse(body.scheduledArrivalUtc());
        if (!arr.isAfter(dep)) {
            throw new IllegalArgumentException("La llegada programada debe ser posterior a la salida.");
        }

        UUID tripId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO trips (id, route_id, bus_id, origin_terminal_code, destination_terminal_code,
                                   original_departure_utc, original_arrival_utc, scheduled_departure_utc, scheduled_arrival_utc,
                                   turnaround_minutes, base_fare_pen, status, previous_trip_id, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROGRAMADO', ?, 1)
                """,
                tripId,
                JdbcHelper.uuid(route.get("id")),
                body.busId(),
                route.get("origin_code"),
                route.get("dest_code"),
                JdbcHelper.ts(dep),
                JdbcHelper.ts(arr),
                JdbcHelper.ts(dep),
                JdbcHelper.ts(arr),
                body.turnaroundMinutes(),
                body.baseFarePen(),
                body.previousTripId()
        );

        for (int seat = 1; seat <= seatCap; seat++) {
            boolean enabled = seat <= enabledSeats;
            jdbc.update(
                    "INSERT INTO trip_seats (id, trip_id, seat_number, enabled, status, active_ticket_id) VALUES (?, ?, ?, ?, ?, NULL)",
                    UUID.randomUUID(), tripId, seat, enabled, enabled ? "DISPONIBLE" : "BLOQUEADO"
            );
        }

        audit.record(auth, "trips", "CREATE_TRIP", "TRIP", tripId.toString(),
                "Viaje programado ruta " + body.routeCode() + " con " + enabledSeats + " asientos habilitados");
        return Map.of(
                "id", tripId,
                "routeCode", body.routeCode(),
                "busId", body.busId(),
                "enabledSeats", enabledSeats,
                "status", "PROGRAMADO",
                "version", 1
        );
    }

    @GetMapping("/trips/{id}/seats")
    public Map<String, Object> getTripSeats(@PathVariable UUID id) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM trips WHERE id = ?", Integer.class, id);
        if (exists == null || exists == 0) {
            throw new NoSuchElementException("Viaje no encontrado: " + id);
        }
        List<Map<String, Object>> seats = jdbc.query(
                "SELECT seat_number, enabled, status, active_ticket_id FROM trip_seats WHERE trip_id = ? ORDER BY seat_number",
                (rs, i) -> Map.of(
                        "seatNumber", rs.getInt("seat_number"),
                        "enabled", rs.getBoolean("enabled"),
                        "status", rs.getString("status"),
                        "activeTicketId", rs.getString("active_ticket_id") != null ? rs.getString("active_ticket_id") : ""
                ),
                id
        );
        return Map.of("tripId", id, "seats", seats);
    }

    @PutMapping("/trips/{id}/actual-times")
    @Transactional
    public Map<String, Object> updateActualTimes(
            @PathVariable UUID id,
            @Valid @RequestBody ActualTimesRequest body,
            Authentication auth
    ) {
        Instant dep = Instant.parse(body.actualDepartureUtc());
        Instant arr = Instant.parse(body.actualArrivalUtc());
        if (!arr.isAfter(dep)) {
            throw new IllegalArgumentException("La llegada real debe ser posterior a la salida real.");
        }
        int updated = jdbc.update(
                """
                UPDATE trips
                SET actual_departure_utc = ?, actual_arrival_utc = ?, incident_notes = ?, status = 'COMPLETADO', version = version + 1
                WHERE id = ?
                """,
                JdbcHelper.ts(dep),
                JdbcHelper.ts(arr),
                body.incidentNotes(),
                id
        );
        if (updated == 0) {
            throw new NoSuchElementException("Viaje no encontrado: " + id);
        }
        audit.record(auth, "trips", "RECORD_ACTUAL_TIMES", "TRIP", id.toString(), "Horarios reales registrados");
        return Map.of(
                "tripId", id,
                "actualDepartureUtc", dep.toString(),
                "actualArrivalUtc", arr.toString(),
                "status", "COMPLETADO"
        );
    }

    public record CreatePassengerRequest(
            @Pattern(regexp = "DNI|CE|PASAPORTE") String documentType,
            @NotBlank String documentNumber,
            @NotBlank String fullName
    ) {
    }

    public record CreateBusRequest(
            @NotBlank String plate,
            @NotBlank String model,
            @Min(1) int seatCapacity,
            Integer enabledSeats,
            @NotNull @DecimalMin("0.0") BigDecimal cargoCapacityKg,
            @NotBlank String homeTerminalCode,
            @Min(0) int turnaroundMinutes
    ) {
    }

    public record CreateTripRequest(
            @NotBlank String routeCode,
            @NotNull UUID busId,
            @NotBlank String scheduledDepartureUtc,
            @NotBlank String scheduledArrivalUtc,
            @Min(0) int turnaroundMinutes,
            @NotNull @DecimalMin("1.00") BigDecimal baseFarePen,
            UUID previousTripId
    ) {
    }

    public record ActualTimesRequest(
            @NotBlank String actualDepartureUtc,
            @NotBlank String actualArrivalUtc,
            String incidentNotes
    ) {
    }
}
