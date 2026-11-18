package pe.edu.utp.innovaciontransporte.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/system")
public class SystemInfoController {

    private static final List<String> MODULES = List.of(
            "identity", "fleet", "routes", "trips", "sales",
            "parcels", "weather", "risk", "contingencies", "reports"
    );

    private final JdbcTemplate jdbcTemplate;
    private final String corridor;
    private final String company;
    private final String timezone;
    private final String currency;
    private final int delayThresholdMinutes;

    public SystemInfoController(
            JdbcTemplate jdbcTemplate,
            @Value("${app.corridor}") String corridor,
            @Value("${app.company}") String company,
            @Value("${app.timezone}") String timezone,
            @Value("${app.currency}") String currency,
            @Value("${app.delay-threshold-minutes}") int delayThresholdMinutes
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.corridor = corridor;
        this.company = company;
        this.timezone = timezone;
        this.currency = currency;
        this.delayThresholdMinutes = delayThresholdMinutes;
    }

    @GetMapping("/info")
    public SystemInfoResponse info() {
        Integer segmentCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM route_segments", Integer.class);
        Integer routeCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM routes WHERE active = TRUE", Integer.class);
        boolean initialized = routeCount != null && routeCount > 0 && segmentCount != null && segmentCount > 0;

        return new SystemInfoResponse(
                corridor,
                company,
                timezone,
                currency,
                delayThresholdMinutes,
                List.of("ADMIN", "OPERADOR"),
                MODULES,
                initialized,
                segmentCount != null ? segmentCount : 0,
                Instant.now().toString()
        );
    }

    public record SystemInfoResponse(
            String corridor,
            String company,
            String timezone,
            String currency,
            int delayThresholdMinutes,
            List<String> roles,
            List<String> modules,
            boolean schemaInitialized,
            int seededRouteSegments,
            String serverTimeUtc
    ) {
    }
}
