package pe.edu.utp.innovaciontransporte.contingencies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import pe.edu.utp.innovaciontransporte.common.CorrelationIdFilter;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente HTTP dedicado para integraciones externas fuera de transacciones de base de datos.
 */
@Service
public class ExternalTelemetryService {

    public static final String SENAMHI_REF_URL =
            "https://idesep.senamhi.gob.pe/portalidesep/idesep_componente_catalogo_geoservicios_wfs.jsp";

    private static final Pattern PLATE_PATTERN = Pattern.compile("\\b([A-Z0-9]{2,4}-\\d{2,4})\\b");

    private final ObjectMapper objectMapper;
    private final String riskServiceUrl;
    private final String openMeteoBaseUrl;
    private final String senamhiWfsUrl;
    private final String mtcConcessionsUrl;
    private final String mtcAltRoutesUrl;
    private final String sutranAlertsUrl;
    private final String groqApiKey;
    private final String groqBaseUrl;
    private final String groqModel;
    private final RestClient weatherClient;
    private final RestClient officialGeoClient;
    private final RestClient riskClient;

    public ExternalTelemetryService(
            ObjectMapper objectMapper,
            @Value("${app.risk-service-url}") String riskServiceUrl,
            @Value("${app.open-meteo-base-url}") String openMeteoBaseUrl,
            @Value("${app.senamhi-wfs-url:https://idesep.senamhi.gob.pe/geoserver/wfs?service=WFS&version=1.0.0&request=GetFeature&typeName=g_04_02:04_02_005_03_002_512_0000_00_00&BBOX=-77.5,-13.2,-74.8,-10.8&outputFormat=application/json}") String senamhiWfsUrl,
            @Value("${app.mtc-concessions-geojson-url:https://d11asvs58xfdql.cloudfront.net/road-concessions.geojson}") String mtcConcessionsUrl,
            @Value("${app.mtc-alt-routes-geojson-url:https://dg6oz2i1iyvxw.cloudfront.net/alt-routes-peru.geojson}") String mtcAltRoutesUrl,
            @Value("${app.sutran-alerts-url:https://api.sigea.dev.sutran.gob.pe/public/alerts}") String sutranAlertsUrl,
            @Value("${app.groq-api-key:}") String groqApiKey,
            @Value("${app.groq-base-url:https://api.groq.com/openai/v1/chat/completions}") String groqBaseUrl,
            @Value("${app.groq-model:openai/gpt-oss-20b}") String groqModel,
            @Value("${app.open-meteo-timeout-ms:3500}") int openMeteoTimeoutMs
    ) {
        this.objectMapper = objectMapper;
        this.riskServiceUrl = riskServiceUrl;
        this.openMeteoBaseUrl = openMeteoBaseUrl;
        this.senamhiWfsUrl = senamhiWfsUrl;
        this.mtcConcessionsUrl = mtcConcessionsUrl;
        this.mtcAltRoutesUrl = mtcAltRoutesUrl;
        this.sutranAlertsUrl = sutranAlertsUrl;
        this.groqApiKey = groqApiKey != null ? groqApiKey.trim() : "";
        this.groqBaseUrl = groqBaseUrl;
        this.groqModel = groqModel;

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

    public JsonNode fetchOpenMeteo(BigDecimal lat, BigDecimal lon) throws Exception {
        String url = String.format(
                "%s?latitude=%s&longitude=%s&hourly=temperature_2m,precipitation,precipitation_probability,wind_speed_10m,visibility,weather_code&forecast_days=3&timezone=UTC",
                openMeteoBaseUrl, lat.toPlainString(), lon.toPlainString()
        );
        String body = weatherClient.get().uri(url).retrieve().body(String.class);
        return objectMapper.readTree(body);
    }

    public JsonNode fetchSenamhiWfs() throws Exception {
        String body = officialGeoClient.get().uri(senamhiWfsUrl).retrieve().body(String.class);
        return objectMapper.readTree(body);
    }

    public JsonNode fetchMtcConcessions() throws Exception {
        String body = officialGeoClient.get().uri(mtcConcessionsUrl).retrieve().body(String.class);
        return objectMapper.readTree(body);
    }

    public JsonNode fetchMtcAltRoutes() throws Exception {
        String body = officialGeoClient.get().uri(mtcAltRoutesUrl).retrieve().body(String.class);
        return objectMapper.readTree(body);
    }

    public ResponseEntity<String> fetchSutranAlertsRaw() {
        return weatherClient.get()
                .uri(sutranAlertsUrl)
                .header("User-Agent", "Mozilla/5.0")
                .header("Origin", "https://alertasviales.dev.sutran.gob.pe")
                .header("Referer", "https://alertasviales.dev.sutran.gob.pe/")
                .retrieve()
                .toEntity(String.class);
    }

    public JsonNode postRiskPrediction(Map<String, Object> payload, String correlationId) throws Exception {
        String respStr = riskClient.post()
                .uri(riskServiceUrl + "/internal/v1/predictions")
                .header(CorrelationIdFilter.CORRELATION_HEADER, correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(respStr);
    }

    public JsonNode getRiskModelMetadata(String correlationId) throws Exception {
        String respStr = riskClient.get()
                .uri(riskServiceUrl + "/internal/v1/model-metadata")
                .header(CorrelationIdFilter.CORRELATION_HEADER, correlationId)
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(respStr);
    }

    public boolean isGroqConfigured() {
        return !groqApiKey.isBlank();
    }

    public String getGroqModel() {
        return groqModel;
    }

    public JsonNode callGroqChat(String systemPrompt, String userPrompt) throws Exception {
        Map<String, Object> reqBody = new LinkedHashMap<>();
        reqBody.put("model", groqModel);
        reqBody.put("temperature", 0.2);
        reqBody.put("max_tokens", 850);
        reqBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));

        String groqResp = officialGeoClient.post()
                .uri(groqBaseUrl)
                .header("Authorization", "Bearer " + groqApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(reqBody)
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(groqResp);
    }

    public JsonNode parseJsonObjectLoose(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim();
        int firstBrace = cleaned.indexOf('{');
        int lastBrace = cleaned.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            cleaned = cleaned.substring(firstBrace, lastBrace + 1);
        }
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Limpia texto externo antes de insertarlo en un prompt para mitigar inyección de instrucciones. */
    public static String sanitizeExternalText(String raw, int maxLen) {
        if (raw == null) {
            return "";
        }
        String normalized = raw
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("[`<>]", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        return normalized.length() > maxLen ? normalized.substring(0, maxLen) : normalized;
    }

    /** Verifica que el texto generado no mencione placas de bus inexistentes en la flota autorizada. */
    public static boolean containsUnknownPlate(String text, Set<String> allowedPlates) {
        if (text == null || text.isBlank()) {
            return false;
        }
        Matcher matcher = PLATE_PATTERN.matcher(text.toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token.startsWith("PE-") || token.startsWith("SIGEA-") || token.startsWith("LIM-") || token.startsWith("HYO-") || token.startsWith("LMC-")) {
                continue;
            }
            if (!allowedPlates.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
