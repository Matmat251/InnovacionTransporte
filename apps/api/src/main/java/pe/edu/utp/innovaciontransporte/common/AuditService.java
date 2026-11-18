package pe.edu.utp.innovaciontransporte.common;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditService {

    private final JdbcTemplate jdbcTemplate;

    public AuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(
            Authentication auth,
            String module,
            String action,
            String resourceType,
            String resourceId,
            String summary
    ) {
        String actor = auth != null ? auth.getName() : "sistema";
        String role = auth != null && !auth.getAuthorities().isEmpty()
                ? auth.getAuthorities().iterator().next().getAuthority().replace("ROLE_", "")
                : "SISTEMA";
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        jdbcTemplate.update(
                """
                INSERT INTO audit_events (id, correlation_id, actor_username, actor_role, module, action, resource_type, resource_id, summary)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                correlationId,
                actor,
                role,
                module,
                action,
                resourceType,
                resourceId,
                summary.length() > 500 ? summary.substring(0, 500) : summary
        );
    }
}
