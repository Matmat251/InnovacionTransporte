package pe.edu.utp.innovaciontransporte.contingencies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
import org.springframework.web.client.RestClient;
import pe.edu.utp.innovaciontransporte.common.AuditService;
import pe.edu.utp.innovaciontransporte.common.CorrelationIdFilter;
import pe.edu.utp.innovaciontransporte.common.JdbcHelper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class WeatherRiskContingencyController {

    private static final String SENAMHI_REF_URL =
            "https://idesep.senamhi.gob.pe/portalidesep/idesep_componente_catalogo_geoservicios_wfs.jsp";

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final String riskServiceUrl;
    private final String openMeteoBaseUrl;
    private final String senamhiWfsUrl;
    private final String mtcConcessionsUrl;
    private final String mtcAltRoutesUrl;
    private final String sutranAlertsUrl;
    private final int delayThresholdMinutes;
    private final int cacheTtlMinutes;
    private final RestClient weatherClient;
    private final RestClient officialGeoClient;
    private final RestClient riskClient;

    public WeatherRiskContingencyController(
            JdbcTemplate jdbc,
            AuditService audit,
            ObjectMapper objectMapper,
            @Value("${app.risk-service-url}") String riskServiceUrl,
            @Value("${app.open-meteo-base-url}") String openMeteoBaseUrl,
            @Value("${app.senamhi-wfs-url:https://idesep.senamhi.gob.pe/geoserver/wfs?service=WFS&version=1.0.0&request=GetFeature&typeName=g_04_02:04_02_005_03_002_512_0000_00_00&BBOX=-77.2,-12.3,-75.0,-11.3&outputFormat=application/json}") String senamhiWfsUrl,
            @Value("${app.mtc-concessions-geojson-url:https://d11asvs58xfdql.cloudfront.net/road-concessions.geojson}") String mtcConcessionsUrl,
            @Value("${app.mtc-alt-routes-geojson-url:https://dg6oz2i1iyvxw.cloudfront.net/alt-routes-peru.geojson}") String mtcAltRoutesUrl,
            @Value("${app.sutran-alerts-url:https://api.sigea.dev.sutran.gob.pe/public/alerts}") String sutranAlertsUrl,
            @Value("${app.open-meteo-timeout-ms:3500}") int openMeteoTimeoutMs,
            @Value("${app.open-meteo-cache-ttl-minutes:30}") int cacheTtlMinutes,
            @Value("${app.delay-threshold-minutes:30}") int delayThresholdMinutes
    ) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.riskServiceUrl = riskServiceUrl;
        this.openMeteoBaseUrl = openMeteoBaseUrl;
        this.senamhiWfsUrl = senamhiWfsUrl;
        this.mtcConcessionsUrl = mtcConcessionsUrl;
        this.mtcAltRoutesUrl = mtcAltRoutesUrl;
        this.sutranAlertsUrl = sutranAlertsUrl;
        this.delayThresholdMinutes = delayThresholdMinutes;
        this.cacheTtlMinutes = cacheTtlMinutes;

        var weatherFactory = new SimpleClientHttpRequestFactory();
        weatherFactory.setConnectTimeout(openMeteoTimeoutMs);
        weatherFactory.setReadTimeout(openMeteoTimeoutMs);
        this.weatherClient = RestClient.builder().requestFactory(weatherFactory).build();

        var geoFactory = new SimpleClientHttpRequestFactory();
        int geoTimeoutMs = Math.max(openMeteoTimeoutMs, 7500);
        geoFactory.setConnectTimeout(geoTimeoutMs);
        geoFactory.setReadTimeout(geoTimeoutMs);
        this.officialGeoClient = RestClient.builder().requestFactory(geoFactory).build();

        var riskFactory = new SimpleClientHttpRequestFactory();
        riskFactory.setConnectTimeout(3000);
        riskFactory.setReadTimeout(3000);
        this.riskClient = RestClient.builder().requestFactory(riskFactory).build();
    }

    @GetMapping("/trips/{id}/weather")
    public Map<String, Object> getTripWeather(@PathVariable UUID id) {
        Map<String, Object> trip = requireTrip(id);
        List<Map<String, Object>> segments = loadLatestWeather(id);
        String overallStatus = resolveWeatherOverallStatus(segments);
        return Map.of(
                "tripId", id,
                "routeId", JdbcHelper.uuid(trip.get("route_id")),
                "overallStatus", overallStatus,
                "senamhiPreventiveUrl", SENAMHI_REF_URL,
                "fromCache", true,
                "observations", segments
        );
    }

    @PostMapping("/trips/{id}/weather-refreshes")
    @Transactional
    public Map<String, Object> refreshTripWeather(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean force,
            Authentication auth
    ) {
        Map<String, Object> trip = requireTrip(id);
        if (!force) {
            List<Map<String, Object>> cached = loadFreshWeather(id);
            if (!cached.isEmpty()) {
                return Map.of(
                        "tripId", id,
                        "overallStatus", resolveWeatherOverallStatus(cached),
                        "senamhiPreventiveUrl", SENAMHI_REF_URL,
                        "fromCache", true,
                        "observations", cached
                );
            }
        }

        UUID routeId = JdbcHelper.uuid(trip.get("route_id"));
        Instant depUtc = JdbcHelper.instant(trip.get("scheduled_departure_utc"));
        List<Map<String, Object>> segments = jdbc.queryForList(
                "SELECT id, sequence_order, name, checkpoint_name, latitude, longitude, estimated_offset_minutes FROM route_segments WHERE route_id = ? ORDER BY sequence_order",
                routeId
        );

        Instant now = Instant.now();
        Instant expires = now.plus(cacheTtlMinutes, ChronoUnit.MINUTES);
        boolean anyMissing = false;

        for (Map<String, Object> seg : segments) {
            UUID segmentId = JdbcHelper.uuid(seg.get("id"));
            BigDecimal lat = JdbcHelper.decimal(seg.get("latitude"));
            BigDecimal lon = JdbcHelper.decimal(seg.get("longitude"));
            int offsetMin = ((Number) seg.get("estimated_offset_minutes")).intValue();
            Instant validAt = depUtc.plus(offsetMin, ChronoUnit.MINUTES);

            try {
                String url = String.format(
                        "%s?latitude=%s&longitude=%s&hourly=temperature_2m,precipitation,precipitation_probability,wind_speed_10m,visibility,weather_code&forecast_days=3&timezone=UTC",
                        openMeteoBaseUrl, lat.toPlainString(), lon.toPlainString()
                );
                String body = weatherClient.get().uri(url).retrieve().body(String.class);
                JsonNode root = objectMapper.readTree(body);
                JsonNode hourly = root.path("hourly");
                int idx = findNearestHourIndex(hourly.path("time"), validAt);
                if (idx < 0) {
                    anyMissing = true;
                    insertMissingWeather(id, segmentId, lat, lon, validAt, now, expires);
                } else {
                    jdbc.update(
                            """
                            INSERT INTO weather_observations
                            (id, trip_id, segment_id, source, latitude, longitude, valid_at_utc, fetched_at_utc, expires_at_utc,
                             precipitation_mm, precipitation_prob_pct, temperature_c, wind_kmh, visibility_m, weather_code, units, data_status)
                            VALUES (?, ?, ?, 'OPEN_METEO', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'mm, %, C, km/h, m', 'VIGENTE')
                            """,
                            UUID.randomUUID(), id, segmentId, lat, lon,
                            JdbcHelper.ts(validAt), JdbcHelper.ts(now), JdbcHelper.ts(expires),
                            hourly.path("precipitation").path(idx).asDouble(0.0),
                            hourly.path("precipitation_probability").path(idx).asInt(0),
                            hourly.path("temperature_2m").path(idx).asDouble(10.0),
                            hourly.path("wind_speed_10m").path(idx).asDouble(10.0),
                            hourly.path("visibility").path(idx).asInt(10000),
                            hourly.path("weather_code").path(idx).asInt(0)
                    );
                }
            } catch (Exception ex) {
                anyMissing = true;
                insertMissingWeather(id, segmentId, lat, lon, validAt, now, expires);
            }
        }

        audit.record(auth, "weather", "REFRESH_WEATHER", "TRIP", id.toString(),
                anyMissing ? "Refresco climático parcial o no disponible (INFORMACION_INSUFICIENTE)" : "Pronóstico Open-Meteo actualizado");

        List<Map<String, Object>> latest = loadLatestWeather(id);
        return Map.of(
                "tripId", id,
                "overallStatus", resolveWeatherOverallStatus(latest),
                "senamhiPreventiveUrl", SENAMHI_REF_URL,
                "fromCache", false,
                "observations", latest
        );
    }

    @GetMapping("/road-alerts")
    public Map<String, Object> listRoadAlerts(@RequestParam(required = false) UUID routeId) {
        String routeFilter = routeId != null ? routeId.toString() : "";
        List<Map<String, Object>> items = jdbc.query(
                """
                SELECT ra.id, ra.segment_id, rs.route_id, r.code AS route_code, rs.name AS segment_name, rs.checkpoint_name,
                       ra.source_name, ra.reference_url, ra.status, ra.origin_type, ra.description,
                       ra.checked_at_utc, ra.valid_until_utc, ra.active, ra.created_by
                FROM road_alerts ra
                JOIN route_segments rs ON rs.id = ra.segment_id
                JOIN routes r ON r.id = rs.route_id
                WHERE (? = '' OR CAST(rs.route_id AS VARCHAR) = ?)
                ORDER BY ra.checked_at_utc DESC
                """,
                (rs, i) -> Map.ofEntries(
                        Map.entry("id", JdbcHelper.uuid(rs.getString("id"))),
                        Map.entry("segmentId", JdbcHelper.uuid(rs.getString("segment_id"))),
                        Map.entry("routeId", JdbcHelper.uuid(rs.getString("route_id"))),
                        Map.entry("routeCode", rs.getString("route_code")),
                        Map.entry("segmentName", rs.getString("segment_name")),
                        Map.entry("checkpointName", rs.getString("checkpoint_name")),
                        Map.entry("sourceName", rs.getString("source_name")),
                        Map.entry("referenceUrl", rs.getString("reference_url") != null ? rs.getString("reference_url") : ""),
                        Map.entry("status", rs.getString("status")),
                        Map.entry("originType", rs.getString("origin_type")),
                        Map.entry("description", rs.getString("description") != null ? rs.getString("description") : ""),
                        Map.entry("checkedAtUtc", JdbcHelper.instant(rs, "checked_at_utc").toString()),
                        Map.entry("validUntilUtc", JdbcHelper.instant(rs, "valid_until_utc").toString()),
                        Map.entry("active", rs.getBoolean("active")),
                        Map.entry("createdBy", rs.getString("created_by"))
                ),
                routeFilter,
                routeFilter
        );
        return Map.of("items", items);
    }

    @PostMapping("/road-alerts")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createRoadAlert(@Valid @RequestBody CreateRoadAlertRequest body, Authentication auth) {
        Integer segCount = jdbc.queryForObject("SELECT COUNT(*) FROM route_segments WHERE id = ?", Integer.class, body.segmentId());
        if (segCount == null || segCount == 0) {
            throw new NoSuchElementException("Tramo no encontrado: " + body.segmentId());
        }
        UUID id = UUID.randomUUID();
        Instant validUntil = Instant.parse(body.validUntilUtc());
        String actor = auth != null ? auth.getName() : "sistema";
        jdbc.update(
                """
                INSERT INTO road_alerts (id, segment_id, source_name, reference_url, status, origin_type, description, valid_until_utc, active, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                """,
                id, body.segmentId(), body.sourceName().trim(), body.referenceUrl(),
                body.status(), body.originType(), body.description(), JdbcHelper.ts(validUntil), actor
        );
        audit.record(auth, "weather", "CREATE_ROAD_ALERT", "ROAD_ALERT", id.toString(),
                "Alerta vial " + body.status() + " (" + body.originType() + ") fuente " + body.sourceName());
        return Map.of(
                "id", id,
                "segmentId", body.segmentId(),
                "sourceName", body.sourceName().trim(),
                "status", body.status(),
                "originType", body.originType(),
                "validUntilUtc", validUntil.toString()
        );
    }

    @PostMapping("/trips/{id}/official-sources-sync")
    @Transactional
    public Map<String, Object> syncOfficialSources(@PathVariable UUID id, Authentication auth) {
        Map<String, Object> trip = requireTrip(id);
        UUID routeId = JdbcHelper.uuid(trip.get("route_id"));
        List<Map<String, Object>> segments = jdbc.queryForList(
                "SELECT id, checkpoint_name, latitude, longitude FROM route_segments WHERE route_id = ? ORDER BY sequence_order",
                routeId
        );

        String actor = auth != null ? auth.getName() : "sistema";
        Instant now = Instant.now();
        Instant validUntil = now.plus(12, ChronoUnit.HOURS);
        int generatedAlertsCount = 0;

        String senamhiStatus = "INFORMACION_INSUFICIENTE";
        int senamhiTotalStations = 0;
        List<Map<String, Object>> senamhiMatched = new ArrayList<>();
        try {
            String wfsBody = officialGeoClient.get().uri(senamhiWfsUrl).retrieve().body(String.class);
            JsonNode wfsJson = objectMapper.readTree(wfsBody);
            JsonNode features = wfsJson.path("features");
            if (features.isArray() && !features.isEmpty()) {
                senamhiTotalStations = features.size();
                senamhiStatus = "CONECTADO";
                for (Map<String, Object> seg : segments) {
                    UUID segId = JdbcHelper.uuid(seg.get("id"));
                    double segLat = JdbcHelper.decimal(seg.get("latitude")).doubleValue();
                    double segLon = JdbcHelper.decimal(seg.get("longitude")).doubleValue();
                    JsonNode bestFeature = null;
                    double bestDistKm = Double.MAX_VALUE;
                    for (JsonNode feat : features) {
                        JsonNode props = feat.path("properties");
                        JsonNode coords = feat.path("geometry").path("coordinates");
                        double stLat = props.hasNonNull("latitud") ? props.path("latitud").asDouble() : coords.path(1).asDouble(0.0);
                        double stLon = props.hasNonNull("longitud") ? props.path("longitud").asDouble() : coords.path(0).asDouble(0.0);
                        if (stLat == 0.0 && stLon == 0.0) {
                            continue;
                        }
                        double d = haversineKm(segLat, segLon, stLat, stLon);
                        if (d < bestDistKm) {
                            bestDistKm = d;
                            bestFeature = feat;
                        }
                    }
                    if (bestFeature != null) {
                        JsonNode p = bestFeature.path("properties");
                        String stCode = p.path("codigo").asText("");
                        String stName = p.path("estacion").asText("");
                        String dept = p.path("departamento").asText("");
                        String prov = p.path("provincia").asText("");
                        int alt = p.path("altitud").asInt(0);
                        double precip = p.path("precipitacion").asDouble(0.0);
                        double normal = p.path("normal").asDouble(0.0);
                        double anomaly = p.path("anomalia").asDouble(0.0);

                        Map<String, Object> match = new LinkedHashMap<>();
                        match.put("segmentId", segId);
                        match.put("checkpointName", String.valueOf(seg.get("checkpoint_name")));
                        match.put("stationCode", stCode);
                        match.put("stationName", stName);
                        match.put("department", dept);
                        match.put("province", prov);
                        match.put("altitudeMeters", alt);
                        match.put("distanceKm", BigDecimal.valueOf(bestDistKm).setScale(1, RoundingMode.HALF_UP).doubleValue());
                        match.put("precipitationMm", precip);
                        match.put("normalMm", normal);
                        match.put("anomalyPct", anomaly);
                        senamhiMatched.add(match);

                        if (anomaly >= 50.0 || precip >= 15.0) {
                            String desc = String.format(Locale.US,
                                    "Estación SENAMHI %s (%s, %d msnm, a %.1f km): precipitación %.1f mm vs normal %.1f mm (anomalía %+.1f%%)",
                                    stName, stCode, alt, bestDistKm, precip, normal, anomaly);
                            generatedAlertsCount += insertOfficialAlertIfAbsent(
                                    segId, "SENAMHI_WFS", SENAMHI_REF_URL, "TRANSITO_RESTRINGIDO", desc, validUntil, actor
                            );
                        }
                    }
                }
            }
        } catch (Exception ex) {
            senamhiStatus = "NO_DISPONIBLE";
        }

        String mtcStatus = "INFORMACION_INSUFICIENTE";
        String mtcActiveConcession = "";
        int mtcVerifiedAltSegmentsCount = 0;
        try {
            String concBody = officialGeoClient.get().uri(mtcConcessionsUrl).retrieve().body(String.class);
            JsonNode concJson = objectMapper.readTree(concBody);
            for (JsonNode feat : concJson.path("features")) {
                String fid = feat.path("properties").path("id").asText("");
                if (fid.contains("iirsa-centro") || fid.contains("la-oroya-huancayo")) {
                    mtcActiveConcession = fid;
                    break;
                }
            }
            String altBody = officialGeoClient.get().uri(mtcAltRoutesUrl).retrieve().body(String.class);
            JsonNode altJson = objectMapper.readTree(altBody);
            for (JsonNode feat : altJson.path("features")) {
                String aid = feat.path("properties").path("id").asText("");
                if (aid.startsWith("carretera-central-") || aid.contains("pe-24")) {
                    mtcVerifiedAltSegmentsCount++;
                }
            }
            if (!mtcActiveConcession.isBlank() || mtcVerifiedAltSegmentsCount > 0) {
                mtcStatus = "CONECTADO";
            }
        } catch (Exception ex) {
            mtcStatus = "NO_DISPONIBLE";
        }

        String sutranApiStatus = "NO_DISPONIBLE";
        try {
            ResponseEntity<String> sutranRes = weatherClient.get()
                    .uri(sutranAlertsUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Origin", "https://alertasviales.dev.sutran.gob.pe")
                    .header("Referer", "https://alertasviales.dev.sutran.gob.pe/")
                    .retrieve()
                    .toEntity(String.class);
            int code = sutranRes.getStatusCode().value();
            String sBody = sutranRes.getBody();
            if (code == 202) {
                sutranApiStatus = "PROTEGIDO_WAF_202";
            } else if (code == 200 && sBody != null && !sBody.isBlank()) {
                JsonNode root = objectMapper.readTree(sBody);
                JsonNode alerts = root.isArray() ? root : root.path("data");
                if (alerts.isArray()) {
                    sutranApiStatus = "CONECTADO";
                    for (JsonNode alert : alerts) {
                        if (alert.path("vencida").asBoolean(false) || alert.path("aunNoVigente").asBoolean(false)) {
                            continue;
                        }
                        double aLat = alert.path("lat").asDouble(0.0);
                        double aLng = alert.path("lng").asDouble(0.0);
                        if (aLat == 0.0 && aLng == 0.0) {
                            continue;
                        }
                        for (Map<String, Object> seg : segments) {
                            double segLat = JdbcHelper.decimal(seg.get("latitude")).doubleValue();
                            double segLon = JdbcHelper.decimal(seg.get("longitude")).doubleValue();
                            if (haversineKm(segLat, segLon, aLat, aLng) <= 15.0) {
                                String estadoVia = alert.path("estadoVia").asText("").toUpperCase(Locale.ROOT);
                                String mappedStatus = estadoVia.contains("INTERRUMP") ? "CERRADO"
                                        : (estadoVia.contains("RESTRING") ? "TRANSITO_RESTRINGIDO" : null);
                                if (mappedStatus != null) {
                                    UUID segId = JdbcHelper.uuid(seg.get("id"));
                                    String codigo = alert.path("codigo").asText("");
                                    String titulo = alert.path("titulo").asText(alert.path("motivo").asText("Evento vial SUTRAN"));
                                    String via = alert.path("viaCodigo").asText("PE-22");
                                    if (via.isBlank()) {
                                        via = "PE-22";
                                    }
                                    String msg = (codigo.isBlank() ? "" : "[" + codigo + "] ") + via + " — " + titulo;
                                    generatedAlertsCount += insertOfficialAlertIfAbsent(
                                            segId,
                                            "SUTRAN_API",
                                            "https://alertasviales.dev.sutran.gob.pe/",
                                            mappedStatus,
                                            msg,
                                            validUntil,
                                            actor
                                    );
                                }
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            sutranApiStatus = "NO_DISPONIBLE";
        }

        audit.record(auth, "weather", "SYNC_OFFICIAL_SOURCES", "TRIP", id.toString(),
                "SENAMHI=" + senamhiStatus + " (" + senamhiTotalStations + "), MTC=" + mtcStatus
                        + ", SUTRAN=" + sutranApiStatus + ", alertas=" + generatedAlertsCount);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tripId", id);
        response.put("syncedAtUtc", now.toString());
        response.put("senamhiStatus", senamhiStatus);
        response.put("senamhiTotalCorridorStations", senamhiTotalStations);
        response.put("senamhiMatchedStations", senamhiMatched);
        response.put("mtcStatus", mtcStatus);
        response.put("mtcActiveConcession", mtcActiveConcession);
        response.put("mtcVerifiedAltSegmentsCount", mtcVerifiedAltSegmentsCount);
        response.put("sutranApiStatus", sutranApiStatus);
        response.put("generatedAlertsCount", generatedAlertsCount);
        return response;
    }

    @GetMapping("/trips/{id}/risk-assessments")
    public Map<String, Object> listRiskAssessments(@PathVariable UUID id) {
        requireTrip(id);
        List<Map<String, Object>> items = jdbc.query(
                """
                SELECT id, threshold_minutes, model_version, mode, status, delay_probability, calibrated, risk_level, missing_features, latency_ms, evaluated_at_utc
                FROM risk_assessments WHERE trip_id = ? ORDER BY evaluated_at_utc DESC
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", JdbcHelper.uuid(rs.getString("id")));
                    row.put("thresholdMinutes", rs.getInt("threshold_minutes"));
                    row.put("modelVersion", rs.getString("model_version"));
                    row.put("mode", rs.getString("mode"));
                    row.put("status", rs.getString("status"));
                    row.put("delayProbability", rs.getObject("delay_probability"));
                    row.put("calibrated", rs.getBoolean("calibrated"));
                    row.put("riskLevel", rs.getString("risk_level"));
                    row.put("missingFeatures", rs.getString("missing_features") != null ? rs.getString("missing_features") : "");
                    row.put("latencyMs", rs.getBigDecimal("latency_ms"));
                    row.put("evaluatedAtUtc", JdbcHelper.instant(rs, "evaluated_at_utc").toString());
                    return row;
                },
                id
        );
        return Map.of("tripId", id, "assessments", items);
    }

    @PostMapping("/trips/{id}/risk-assessments")
    @Transactional
    public Map<String, Object> evaluateRisk(@PathVariable UUID id, Authentication auth) {
        Map<String, Object> trip = requireTrip(id);
        UUID routeId = JdbcHelper.uuid(trip.get("route_id"));
        String routeCode = jdbc.queryForObject("SELECT code FROM routes WHERE id = ?", String.class, routeId);
        Instant depUtc = JdbcHelper.instant(trip.get("scheduled_departure_utc"));
        Instant arrUtc = JdbcHelper.instant(trip.get("scheduled_arrival_utc"));
        long plannedMinutes = Math.max(30, Duration.between(depUtc, arrUtc).toMinutes());

        List<Map<String, Object>> weather = loadLatestWeather(id);
        Map<String, Object> summary = summarizeWeather(weather);

        UUID assessmentId = UUID.randomUUID();
        String corrId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (corrId == null) {
            corrId = UUID.randomUUID().toString();
        }

        String status = "INFORMACION_INSUFICIENTE";
        String mode = "REAL";
        String modelVersion = "unavailable";
        BigDecimal probability = null;
        boolean calibrated = false;
        String riskLevel = "INFORMACION_INSUFICIENTE";
        String missingFeatures = summary == null ? "weatherSummary" : "";
        BigDecimal latencyMs = BigDecimal.ZERO;

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("routeCode", routeCode);
            payload.put("scheduledDepartureUtc", depUtc.toString());
            payload.put("plannedDurationMinutes", plannedMinutes);
            payload.put("delayThresholdMinutes", delayThresholdMinutes);
            payload.put("weatherSummary", summary);

            String respStr = riskClient.post()
                    .uri(riskServiceUrl + "/internal/v1/predictions")
                    .header(CorrelationIdFilter.CORRELATION_HEADER, corrId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            JsonNode resp = objectMapper.readTree(respStr);
            status = resp.path("status").asText("INFORMACION_INSUFICIENTE");
            mode = resp.path("mode").asText("REAL");
            modelVersion = resp.path("modelVersion").asText("unknown");
            calibrated = resp.path("calibrated").asBoolean(false);
            riskLevel = resp.path("riskLevel").asText("INFORMACION_INSUFICIENTE");
            latencyMs = BigDecimal.valueOf(resp.path("latencyMs").asDouble(0.0)).setScale(2, RoundingMode.HALF_UP);
            if (!resp.path("delayProbability").isNull() && resp.path("delayProbability").isNumber()) {
                probability = BigDecimal.valueOf(resp.path("delayProbability").asDouble()).setScale(4, RoundingMode.HALF_UP);
            }
            List<String> missingList = new ArrayList<>();
            resp.path("missingFeatures").forEach(n -> missingList.add(n.asText()));
            missingFeatures = String.join(",", missingList);
        } catch (Exception ex) {
            status = "INFORMACION_INSUFICIENTE";
            riskLevel = "INFORMACION_INSUFICIENTE";
            missingFeatures = "risk-service-unavailable";
        }

        jdbc.update(
                """
                INSERT INTO risk_assessments
                (id, trip_id, threshold_minutes, model_version, mode, status, delay_probability, calibrated, risk_level, missing_features, latency_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                assessmentId, id, delayThresholdMinutes, modelVersion, mode, status,
                probability, calibrated, riskLevel, missingFeatures, latencyMs
        );
        audit.record(auth, "risk", "EVALUATE_RISK", "TRIP", id.toString(),
                "Evaluación riesgo=" + riskLevel + " modo=" + mode + " versión=" + modelVersion);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", assessmentId);
        result.put("tripId", id);
        result.put("thresholdMinutes", delayThresholdMinutes);
        result.put("mode", mode);
        result.put("modelVersion", modelVersion);
        result.put("status", status);
        result.put("delayProbability", probability);
        result.put("calibrated", calibrated);
        result.put("riskLevel", riskLevel);
        result.put("missingFeatures", missingFeatures);
        result.put("latencyMs", latencyMs);
        return result;
    }

    @PostMapping("/trips/{id}/contingency-simulations")
    @Transactional
    public Map<String, Object> simulateContingency(
            @PathVariable UUID id,
            @RequestBody(required = false) SimulationRequest body,
            Authentication auth
    ) {
        Map<String, Object> initialTrip = requireTrip(id);
        UUID busId = JdbcHelper.uuid(initialTrip.get("bus_id"));
        Instant initialArrivalUtc = JdbcHelper.instant(initialTrip.get("scheduled_arrival_utc"));
        int turnaround = ((Number) initialTrip.get("turnaround_minutes")).intValue();

        List<Map<String, Object>> nextTrips = jdbc.queryForList(
                """
                SELECT t.*, r.code AS route_code, r.name AS route_name, r.road_status_verified
                FROM trips t
                JOIN routes r ON r.id = t.route_id
                WHERE (t.previous_trip_id = ? OR (t.bus_id = ? AND t.id <> ? AND t.scheduled_departure_utc >= ?))
                  AND t.status IN ('PROGRAMADO', 'REPROGRAMADO')
                ORDER BY t.scheduled_departure_utc ASC
                LIMIT 1
                """,
                id, busId, id, JdbcHelper.ts(initialArrivalUtc)
        );

        Map<String, Object> affectedTrip = nextTrips.isEmpty() ? initialTrip : nextTrips.get(0);
        UUID affectedTripId = JdbcHelper.uuid(affectedTrip.get("id"));
        UUID targetRouteId = JdbcHelper.uuid(affectedTrip.get("route_id"));
        UUID initialRouteId = JdbcHelper.uuid(initialTrip.get("route_id"));
        int tripVersion = ((Number) affectedTrip.get("version")).intValue();
        Instant affectedDepUtc = JdbcHelper.instant(affectedTrip.get("scheduled_departure_utc"));
        Instant affectedArrUtc = JdbcHelper.instant(affectedTrip.get("scheduled_arrival_utc"));
        String affectedOrigin = String.valueOf(affectedTrip.get("origin_terminal_code"));
        String affectedDest = String.valueOf(affectedTrip.get("destination_terminal_code"));

        Instant readyBaselineUtc = initialArrivalUtc.plus(turnaround, ChronoUnit.MINUTES);
        long marginMinutes = Duration.between(readyBaselineUtc, affectedDepUtc).toMinutes();
        Instant delay30ReadyUtc = readyBaselineUtc.plus(30, ChronoUnit.MINUTES);
        long delay30Lost = Math.max(0L, Duration.between(affectedDepUtc, delay30ReadyUtc).toMinutes());
        Instant delay60ReadyUtc = readyBaselineUtc.plus(60, ChronoUnit.MINUTES);
        long delay60Lost = Math.max(0L, Duration.between(affectedDepUtc, delay60ReadyUtc).toMinutes());

        int soldTickets = countActiveTickets(affectedTripId);
        int parcelCount = countTripParcels(affectedTripId);
        BigDecimal parcelWeightKg = sumTripParcelWeight(affectedTripId);

        List<String> closedAlerts = findActiveClosureDescriptions(initialRouteId, targetRouteId);
        boolean hasClosure = !closedAlerts.isEmpty();

        String latestRiskLevel = findLatestRiskLevel(id);
        List<Map<String, Object>> options = new ArrayList<>();

        Map<String, Object> keepOption = new LinkedHashMap<>();
        keepOption.put("optionCode", "MANTENER_PLAN");
        keepOption.put("actionType", "MANTENER_PLAN");
        keepOption.put("label", "Mantener plan y bus actual");
        keepOption.put("viable", !hasClosure);
        keepOption.put("confirmable", !hasClosure);
        keepOption.put("statusLabel", hasClosure ? "Descartado (cierre vial)" : (delay30Lost > 0 ? "Riesgo de demora +" + delay30Lost + " min" : "Vigente"));
        keepOption.put("reason", hasClosure
                ? "Se detectó cierre vial vigente (" + String.join("; ", closedAlerts) + "); itinerario descartado incluso con riesgo bajo."
                : "Bajo escenario +30 min, el viaje posterior saldría con " + delay30Lost + " min de retraso.");
        options.add(keepOption);

        List<Map<String, Object>> candidateBuses = jdbc.queryForList(
                "SELECT id, plate, model, enabled_seats, cargo_capacity_kg, current_terminal_code, status FROM buses WHERE id <> ? ORDER BY plate",
                busId
        );

        for (Map<String, Object> bus : candidateBuses) {
            UUID candId = JdbcHelper.uuid(bus.get("id"));
            String plate = String.valueOf(bus.get("plate"));
            int enabledSeats = ((Number) bus.get("enabled_seats")).intValue();
            BigDecimal cargoCap = JdbcHelper.decimal(bus.get("cargo_capacity_kg"));
            String term = String.valueOf(bus.get("current_terminal_code"));
            String busStatus = String.valueOf(bus.get("status"));

            List<String> rejections = new ArrayList<>();
            if (hasClosure) {
                rejections.add("Tramo de ruta cerrado actualmente");
            }
            if (!"DISPONIBLE".equals(busStatus)) {
                rejections.add("Estado actual del bus: " + busStatus);
            }
            if (!affectedOrigin.equals(term)) {
                rejections.add("Terminal distinto (" + term + " != " + affectedOrigin + ")");
            }
            if (hasBusScheduleOverlap(candId, affectedTripId, affectedDepUtc, affectedArrUtc)) {
                rejections.add("Bus comprometido en otro viaje en ese horario");
            }
            if (enabledSeats < soldTickets) {
                rejections.add("Asientos habilitados insuficientes (" + enabledSeats + " < " + soldTickets + " pasajes)");
            }
            if (cargoCap.compareTo(parcelWeightKg) < 0) {
                rejections.add("Capacidad de carga insuficiente (" + cargoCap + " kg < " + parcelWeightKg + " kg)");
            }

            boolean viable = rejections.isEmpty();
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("optionCode", "BUS_" + plate);
            opt.put("actionType", "ASIGNAR_BUS_RESERVA");
            opt.put("reserveBusId", candId);
            opt.put("busPlate", plate);
            opt.put("label", "Asignar bus de reserva " + plate + " (" + enabledSeats + " asientos / " + cargoCap + " kg)");
            opt.put("viable", viable);
            opt.put("confirmable", viable);
            opt.put("statusLabel", viable ? "Viable" : "Rechazado");
            opt.put("reassignedTickets", soldTickets);
            opt.put("reassignedParcels", parcelCount);
            opt.put("totalParcelWeightKg", parcelWeightKg);
            opt.put("reason", viable
                    ? "Cumple terminal " + affectedOrigin + ", disponibilidad, " + enabledSeats + " asientos >= " + soldTickets + " y carga " + cargoCap + " kg >= " + parcelWeightKg + " kg."
                    : String.join(". ", rejections));
            options.add(opt);
        }

        List<Map<String, Object>> altRoutes = jdbc.queryForList(
                """
                SELECT r.code, r.name, r.road_status_verified
                FROM routes r
                JOIN terminals o ON o.id = r.origin_terminal_id
                JOIN terminals d ON d.id = r.destination_terminal_id
                WHERE o.code = ? AND d.code = ? AND r.id <> ? AND r.active = TRUE
                """,
                affectedOrigin, affectedDest, targetRouteId
        );
        for (Map<String, Object> alt : altRoutes) {
            boolean verified = Boolean.TRUE.equals(alt.get("road_status_verified"));
            Map<String, Object> altOpt = new LinkedHashMap<>();
            altOpt.put("optionCode", "ALT_" + alt.get("code"));
            altOpt.put("actionType", "RUTA_ALTERNA");
            altOpt.put("label", "Ruta alterna " + alt.get("code") + " — " + alt.get("name"));
            altOpt.put("viable", verified);
            altOpt.put("confirmable", false);
            altOpt.put("statusLabel", verified ? "Verificada" : "Por verificar");
            altOpt.put("reason", verified
                    ? "Ruta verificada disponible para análisis manual."
                    : "Pendiente: falta verificación de estado vial y aprobación previa antes de poder confirmarse.");
            options.add(altOpt);
        }

        Map<String, Object> reschedOpt = new LinkedHashMap<>();
        reschedOpt.put("optionCode", "REPROGRAMAR_30M");
        reschedOpt.put("actionType", "REPROGRAMAR");
        reschedOpt.put("label", "Reprogramar viaje posterior (+30 min escenario)");
        reschedOpt.put("suggestedDepartureUtc", delay30ReadyUtc.toString());
        reschedOpt.put("viable", !hasClosure);
        reschedOpt.put("confirmable", !hasClosure);
        reschedOpt.put("statusLabel", hasClosure ? "Descartado (cierre vial)" : "Reprogramación lista (" + delay30Lost + " min ajuste)");
        reschedOpt.put("reason", hasClosure
                ? "La ruta tiene un cierre vial vigente; la reprogramación sobre el mismo tramo no puede confirmarse."
                : "Permite mantener el bus original absorbiendo el tiempo de preparación de " + turnaround + " min.");
        options.add(reschedOpt);

        UUID simulationId = UUID.randomUUID();
        String optionsJson;
        try {
            optionsJson = objectMapper.writeValueAsString(options);
        } catch (Exception ex) {
            optionsJson = "[]";
        }
        if (optionsJson.length() > 3950) {
            optionsJson = optionsJson.substring(0, 3950);
        }

        jdbc.update(
                """
                INSERT INTO contingency_simulations
                (id, initial_trip_id, affected_trip_id, trip_version, risk_level, margin_minutes,
                 delay_30_ready_utc, delay_30_lost_minutes, delay_60_ready_utc, delay_60_lost_minutes, options_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                simulationId, id, affectedTripId, tripVersion, latestRiskLevel, marginMinutes,
                JdbcHelper.ts(delay30ReadyUtc), delay30Lost, JdbcHelper.ts(delay60ReadyUtc), delay60Lost, optionsJson
        );
        audit.record(auth, "contingencies", "SIMULATE_CONTINGENCY", "TRIP", affectedTripId.toString(),
                "Simulación +30/60 min generada con versión=" + tripVersion + " cierre=" + hasClosure);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("simulationId", simulationId);
        response.put("initialTripId", id);
        response.put("affectedTripId", affectedTripId);
        response.put("affectedTripVersion", tripVersion);
        response.put("riskLevel", latestRiskLevel);
        response.put("turnaroundMinutes", turnaround);
        response.put("readyBaselineUtc", readyBaselineUtc.toString());
        response.put("affectedScheduledDepartureUtc", affectedDepUtc.toString());
        response.put("marginMinutes", marginMinutes);
        response.put("delay30ReadyUtc", delay30ReadyUtc.toString());
        response.put("delay30LostMinutes", delay30Lost);
        response.put("delay60ReadyUtc", delay60ReadyUtc.toString());
        response.put("delay60LostMinutes", delay60Lost);
        response.put("soldTicketsCount", soldTickets);
        response.put("parcelCount", parcelCount);
        response.put("totalParcelWeightKg", parcelWeightKg);
        response.put("hasActiveRoadClosure", hasClosure);
        response.put("closedSegmentAlerts", closedAlerts);
        response.put("options", options);
        return response;
    }

    @PostMapping("/trips/{id}/contingency-decisions")
    @Transactional
    public Map<String, Object> confirmDecision(
            @PathVariable UUID id,
            @Valid @RequestBody DecisionRequest body,
            Authentication auth
    ) {
        List<Map<String, Object>> simRows = jdbc.queryForList(
                "SELECT id, initial_trip_id, affected_trip_id, delay_30_ready_utc FROM contingency_simulations WHERE id = ?",
                body.simulationId()
        );
        if (simRows.isEmpty()) {
            throw new NoSuchElementException("Simulación no encontrada: " + body.simulationId());
        }
        Map<String, Object> sim = simRows.get(0);
        UUID targetTripId = JdbcHelper.uuid(sim.get("affected_trip_id"));

        List<Map<String, Object>> lockedTrips = jdbc.queryForList(
                "SELECT id, route_id, bus_id, origin_terminal_code, scheduled_departure_utc, scheduled_arrival_utc, version FROM trips WHERE id = ? FOR UPDATE",
                targetTripId
        );
        if (lockedTrips.isEmpty()) {
            throw new NoSuchElementException("Viaje afectado no encontrado.");
        }
        Map<String, Object> targetTrip = lockedTrips.get(0);
        int currentVersion = ((Number) targetTrip.get("version")).intValue();
        if (currentVersion != body.expectedTripVersion()) {
            throw new IllegalStateException(
                    "El estado del viaje cambió (versión actual " + currentVersion + ", esperada " + body.expectedTripVersion()
                            + "). Actualiza la simulación antes de confirmar."
            );
        }

        UUID initialRouteId = JdbcHelper.uuid(requireTrip(id).get("route_id"));
        UUID targetRouteId = JdbcHelper.uuid(targetTrip.get("route_id"));
        List<String> closedAlerts = findActiveClosureDescriptions(initialRouteId, targetRouteId);
        if (!closedAlerts.isEmpty()) {
            throw new IllegalStateException("No se puede confirmar sobre una ruta con cierre vial vigente: " + String.join("; ", closedAlerts));
        }

        UUID previousBusId = JdbcHelper.uuid(targetTrip.get("bus_id"));
        UUID assignedBusId = previousBusId;
        Instant depUtc = JdbcHelper.instant(targetTrip.get("scheduled_departure_utc"));
        Instant arrUtc = JdbcHelper.instant(targetTrip.get("scheduled_arrival_utc"));
        String originTerm = String.valueOf(targetTrip.get("origin_terminal_code"));

        int soldTickets = countActiveTickets(targetTripId);
        int parcelCount = countTripParcels(targetTripId);
        BigDecimal parcelWeightKg = sumTripParcelWeight(targetTripId);
        Instant newDeparture = null;

        if ("ASIGNAR_BUS_RESERVA".equals(body.actionType())) {
            if (body.reserveBusId() == null) {
                throw new IllegalArgumentException("reserveBusId es obligatorio para ASIGNAR_BUS_RESERVA.");
            }
            List<Map<String, Object>> busRows = jdbc.queryForList(
                    "SELECT id, plate, enabled_seats, cargo_capacity_kg, current_terminal_code, status FROM buses WHERE id = ? FOR UPDATE",
                    body.reserveBusId()
            );
            if (busRows.isEmpty()) {
                throw new NoSuchElementException("Bus de reserva no encontrado.");
            }
            Map<String, Object> bus = busRows.get(0);
            int enabledSeats = ((Number) bus.get("enabled_seats")).intValue();
            BigDecimal cargoCap = JdbcHelper.decimal(bus.get("cargo_capacity_kg"));
            String term = String.valueOf(bus.get("current_terminal_code"));
            String busStatus = String.valueOf(bus.get("status"));

            if (!"DISPONIBLE".equals(busStatus) || !originTerm.equals(term)) {
                throw new IllegalStateException("El bus de reserva ya no está disponible en el terminal " + originTerm + ".");
            }
            if (enabledSeats < soldTickets) {
                throw new IllegalStateException("El bus seleccionado tiene asientos insuficientes (" + enabledSeats + " < " + soldTickets + ").");
            }
            if (cargoCap.compareTo(parcelWeightKg) < 0) {
                throw new IllegalStateException("El bus seleccionado no soporta el peso total de encomiendas.");
            }
            if (hasBusScheduleOverlap(body.reserveBusId(), targetTripId, depUtc, arrUtc)) {
                throw new IllegalStateException("El bus de reserva tiene otra asignación incompatible en el mismo intervalo.");
            }

            assignedBusId = body.reserveBusId();
            jdbc.update("UPDATE trips SET bus_id = ?, version = version + 1 WHERE id = ?", assignedBusId, targetTripId);
            jdbc.update("UPDATE buses SET status = 'EN_SERVICIO' WHERE id = ?", assignedBusId);
        } else if ("REPROGRAMAR".equals(body.actionType())) {
            newDeparture = body.newDepartureUtc() != null && !body.newDepartureUtc().isBlank()
                    ? Instant.parse(body.newDepartureUtc())
                    : JdbcHelper.instant(sim.get("delay_30_ready_utc"));
            long durationMin = Duration.between(depUtc, arrUtc).toMinutes();
            Instant newArrival = newDeparture.plus(durationMin, ChronoUnit.MINUTES);
            jdbc.update(
                    "UPDATE trips SET scheduled_departure_utc = ?, scheduled_arrival_utc = ?, status = 'REPROGRAMADO', version = version + 1 WHERE id = ?",
                    JdbcHelper.ts(newDeparture), JdbcHelper.ts(newArrival), targetTripId
            );
        } else {
            jdbc.update("UPDATE trips SET version = version + 1 WHERE id = ?", targetTripId);
        }

        UUID decisionId = UUID.randomUUID();
        String actor = auth != null ? auth.getName() : "sistema";
        String rationale = body.rationale() != null && !body.rationale().isBlank()
                ? body.rationale().trim()
                : "Decisión confirmada por operador";

        jdbc.update(
                """
                INSERT INTO contingency_decisions
                (id, simulation_id, initial_trip_id, affected_trip_id, action_type, previous_bus_id, assigned_bus_id,
                 new_departure_utc, reassigned_tickets, reassigned_parcels, rationale, decided_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                decisionId, body.simulationId(), id, targetTripId, body.actionType(),
                previousBusId, assignedBusId, JdbcHelper.ts(newDeparture),
                soldTickets, parcelCount, rationale, actor
        );
        audit.record(auth, "contingencies", "CONFIRM_DECISION", "TRIP", targetTripId.toString(),
                "Contingencia " + body.actionType() + " aplicada; boletos=" + soldTickets + " encomiendas=" + parcelCount);

        return Map.ofEntries(
                Map.entry("decisionId", decisionId),
                Map.entry("affectedTripId", targetTripId),
                Map.entry("actionType", body.actionType()),
                Map.entry("assignedBusId", assignedBusId),
                Map.entry("newDepartureUtc", newDeparture != null ? newDeparture.toString() : depUtc.toString()),
                Map.entry("reassignedTickets", soldTickets),
                Map.entry("reassignedParcels", parcelCount),
                Map.entry("newTripVersion", currentVersion + 1)
        );
    }

    private Map<String, Object> requireTrip(UUID tripId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM trips WHERE id = ?", tripId);
        if (rows.isEmpty()) {
            throw new NoSuchElementException("Viaje no encontrado: " + tripId);
        }
        return rows.get(0);
    }

    private List<Map<String, Object>> loadLatestWeather(UUID tripId) {
        return jdbc.query(
                """
                SELECT rs.id AS segment_id, rs.sequence_order, rs.name AS segment_name, rs.checkpoint_name,
                       wo.source, wo.latitude, wo.longitude, wo.valid_at_utc, wo.fetched_at_utc, wo.expires_at_utc,
                       wo.precipitation_mm, wo.precipitation_prob_pct, wo.temperature_c, wo.wind_kmh, wo.visibility_m, wo.weather_code, wo.units, wo.data_status
                FROM route_segments rs
                LEFT JOIN weather_observations wo ON wo.segment_id = rs.id
                   AND wo.trip_id = ?
                   AND wo.fetched_at_utc = (
                       SELECT MAX(w2.fetched_at_utc) FROM weather_observations w2 WHERE w2.trip_id = ? AND w2.segment_id = rs.id
                   )
                WHERE rs.route_id = (SELECT route_id FROM trips WHERE id = ?)
                ORDER BY rs.sequence_order
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("segmentId", JdbcHelper.uuid(rs.getString("segment_id")));
                    m.put("sequenceOrder", rs.getInt("sequence_order"));
                    m.put("segmentName", rs.getString("segment_name"));
                    m.put("checkpointName", rs.getString("checkpoint_name"));
                    String dataStatus = rs.getString("data_status");
                    if (dataStatus == null) {
                        m.put("dataStatus", "INFORMACION_INSUFICIENTE");
                        m.put("source", "SIN_CONSULTA");
                        m.put("units", "mm, %, C, km/h, m");
                    } else {
                        Instant expires = JdbcHelper.instant(rs, "expires_at_utc");
                        if (expires != null && expires.isBefore(Instant.now())) {
                            m.put("dataStatus", "VENCIDO");
                        } else {
                            m.put("dataStatus", dataStatus);
                        }
                        m.put("source", rs.getString("source"));
                        m.put("latitude", rs.getBigDecimal("latitude"));
                        m.put("longitude", rs.getBigDecimal("longitude"));
                        m.put("validAtUtc", JdbcHelper.instant(rs, "valid_at_utc").toString());
                        m.put("fetchedAtUtc", JdbcHelper.instant(rs, "fetched_at_utc").toString());
                        m.put("expiresAtUtc", expires != null ? expires.toString() : "");
                        m.put("precipitationMm", rs.getObject("precipitation_mm"));
                        m.put("precipitationProbPct", rs.getObject("precipitation_prob_pct"));
                        m.put("temperatureC", rs.getObject("temperature_c"));
                        m.put("windKmh", rs.getObject("wind_kmh"));
                        m.put("visibilityMeters", rs.getObject("visibility_m"));
                        m.put("weatherCode", rs.getObject("weather_code"));
                        m.put("units", rs.getString("units"));
                    }
                    return m;
                },
                tripId, tripId, tripId
        );
    }

    private List<Map<String, Object>> loadFreshWeather(UUID tripId) {
        List<Map<String, Object>> latest = loadLatestWeather(tripId);
        boolean allFresh = !latest.isEmpty() && latest.stream()
                .allMatch(row -> "VIGENTE".equals(row.get("dataStatus")) || "DEMO".equals(row.get("dataStatus")));
        return allFresh ? latest : List.of();
    }

    private static String resolveWeatherOverallStatus(List<Map<String, Object>> segments) {
        if (segments.isEmpty()) {
            return "INFORMACION_INSUFICIENTE";
        }
        boolean hasDemo = false;
        for (Map<String, Object> seg : segments) {
            String st = String.valueOf(seg.get("dataStatus"));
            if ("INFORMACION_INSUFICIENTE".equals(st) || "VENCIDO".equals(st)) {
                return st;
            }
            if ("DEMO".equals(st)) {
                hasDemo = true;
            }
        }
        return hasDemo ? "DEMO" : "VIGENTE";
    }

    private void insertMissingWeather(UUID tripId, UUID segmentId, BigDecimal lat, BigDecimal lon, Instant validAt, Instant now, Instant expires) {
        jdbc.update(
                """
                INSERT INTO weather_observations
                (id, trip_id, segment_id, source, latitude, longitude, valid_at_utc, fetched_at_utc, expires_at_utc, units, data_status)
                VALUES (?, ?, ?, 'OPEN_METEO_FALLBACK', ?, ?, ?, ?, ?, 'mm, %, C, km/h, m', 'INFORMACION_INSUFICIENTE')
                """,
                UUID.randomUUID(), tripId, segmentId, lat, lon,
                JdbcHelper.ts(validAt), JdbcHelper.ts(now), JdbcHelper.ts(expires)
        );
    }

    private static int findNearestHourIndex(JsonNode timesNode, Instant target) {
        if (!timesNode.isArray() || timesNode.isEmpty()) {
            return -1;
        }
        int bestIdx = 0;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < timesNode.size(); i++) {
            String raw = timesNode.get(i).asText();
            Instant t = raw.endsWith("Z") ? Instant.parse(raw) : Instant.parse(raw + ":00Z");
            long diff = Math.abs(Duration.between(t, target).toMinutes());
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private Map<String, Object> summarizeWeather(List<Map<String, Object>> segments) {
        List<Map<String, Object>> valid = segments.stream()
                .filter(s -> "VIGENTE".equals(s.get("dataStatus")) || "DEMO".equals(s.get("dataStatus")))
                .filter(s -> s.get("precipitationMm") != null)
                .toList();
        if (valid.isEmpty() || segments.isEmpty()) {
            return null;
        }
        double maxPrecip = valid.stream().mapToDouble(s -> ((Number) s.get("precipitationMm")).doubleValue()).max().orElse(0.0);
        double maxProb = valid.stream().mapToDouble(s -> ((Number) s.get("precipitationProbPct")).doubleValue()).max().orElse(0.0);
        double minTemp = valid.stream().mapToDouble(s -> ((Number) s.get("temperatureC")).doubleValue()).min().orElse(10.0);
        double maxWind = valid.stream().mapToDouble(s -> ((Number) s.get("windKmh")).doubleValue()).max().orElse(10.0);
        double minVis = valid.stream().mapToDouble(s -> ((Number) s.get("visibilityMeters")).doubleValue()).min().orElse(10000.0);
        int worstCode = valid.stream().mapToInt(s -> ((Number) s.get("weatherCode")).intValue()).max().orElse(0);
        double coverage = BigDecimal.valueOf(valid.size() / (double) segments.size()).setScale(2, RoundingMode.HALF_UP).doubleValue();

        return Map.of(
                "maxPrecipitationMm", maxPrecip,
                "maxPrecipitationProbabilityPct", maxProb,
                "minTemperatureC", minTemp,
                "maxWindKmh", maxWind,
                "minVisibilityMeters", minVis,
                "worstWeatherCode", worstCode,
                "coverageRatio", coverage
        );
    }

    private int countActiveTickets(UUID tripId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE trip_id = ? AND status = 'EMITIDO'", Integer.class, tripId);
        return n != null ? n : 0;
    }

    private int countTripParcels(UUID tripId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM parcels WHERE trip_id = ?", Integer.class, tripId);
        return n != null ? n : 0;
    }

    private BigDecimal sumTripParcelWeight(UUID tripId) {
        return JdbcHelper.decimal(jdbc.queryForObject("SELECT COALESCE(SUM(weight_kg), 0) FROM parcels WHERE trip_id = ?", BigDecimal.class, tripId));
    }

    private List<String> findActiveClosureDescriptions(UUID routeA, UUID routeB) {
        return jdbc.queryForList(
                """
                SELECT rs.checkpoint_name || ' (' || ra.source_name || ')'
                FROM road_alerts ra
                JOIN route_segments rs ON rs.id = ra.segment_id
                WHERE ra.active = TRUE
                  AND ra.status = 'CERRADO'
                  AND ra.valid_until_utc > CURRENT_TIMESTAMP
                  AND (rs.route_id = ? OR rs.route_id = ?)
                """,
                String.class,
                routeA, routeB
        );
    }

    private String findLatestRiskLevel(UUID tripId) {
        List<String> list = jdbc.queryForList(
                "SELECT risk_level FROM risk_assessments WHERE trip_id = ? ORDER BY evaluated_at_utc DESC LIMIT 1",
                String.class,
                tripId
        );
        return list.isEmpty() ? "INFORMACION_INSUFICIENTE" : list.get(0);
    }

    private boolean hasBusScheduleOverlap(UUID busId, UUID excludingTripId, Instant depUtc, Instant arrUtc) {
        Instant winStart = depUtc.minus(60, ChronoUnit.MINUTES);
        Instant winEnd = arrUtc.plus(60, ChronoUnit.MINUTES);
        Integer conflicts = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM trips
                WHERE bus_id = ? AND id <> ? AND status IN ('PROGRAMADO', 'EN_CURSO', 'REPROGRAMADO')
                  AND scheduled_departure_utc < ? AND scheduled_arrival_utc > ?
                """,
                Integer.class,
                busId, excludingTripId, JdbcHelper.ts(winEnd), JdbcHelper.ts(winStart)
        );
        return conflicts != null && conflicts > 0;
    }

    private int insertOfficialAlertIfAbsent(
            UUID segmentId,
            String sourceName,
            String referenceUrl,
            String status,
            String description,
            Instant validUntil,
            String actor
    ) {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM road_alerts WHERE segment_id = ? AND source_name = ? AND active = TRUE AND valid_until_utc > CURRENT_TIMESTAMP",
                Integer.class,
                segmentId, sourceName
        );
        if (existing != null && existing > 0) {
            return 0;
        }
        String safeDesc = description != null && description.length() > 395 ? description.substring(0, 395) : description;
        jdbc.update(
                """
                INSERT INTO road_alerts (id, segment_id, source_name, reference_url, status, origin_type, description, valid_until_utc, active, created_by)
                VALUES (?, ?, ?, ?, ?, 'OFICIAL_MANUAL', ?, ?, TRUE, ?)
                """,
                UUID.randomUUID(), segmentId, sourceName, referenceUrl, status, safeDesc, JdbcHelper.ts(validUntil), actor
        );
        return 1;
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public record CreateRoadAlertRequest(
            @NotNull UUID segmentId,
            @NotBlank String sourceName,
            String referenceUrl,
            @Pattern(regexp = "DESPEJADO|TRANSITO_RESTRINGIDO|CERRADO") String status,
            @Pattern(regexp = "OFICIAL_MANUAL|SIMULADA") String originType,
            String description,
            @NotBlank String validUntilUtc
    ) {
    }

    public record SimulationRequest(List<Integer> delayScenariosMinutes) {
    }

    public record DecisionRequest(
            @NotNull UUID simulationId,
            int expectedTripVersion,
            @Pattern(regexp = "MANTENER_PLAN|ASIGNAR_BUS_RESERVA|REPROGRAMAR") String actionType,
            UUID reserveBusId,
            String newDepartureUtc,
            String rationale
    ) {
    }
}
