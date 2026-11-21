package pe.edu.utp.innovaciontransporte.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pe.edu.utp.innovaciontransporte.common.AuditService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1")
public class AuthAndUsersController {

    private static final UUID DEMO_COMPANY_ID = UUID.fromString("11111111-1111-1111-1111-111111111101");
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_MINUTES = 5;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final SecurityContextRepository securityContextRepository;
    private final AuditService audit;
    private final ConcurrentHashMap<String, FailedLoginState> failedLogins = new ConcurrentHashMap<>();

    public AuthAndUsersController(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            SecurityContextRepository securityContextRepository,
            AuditService audit
    ) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.securityContextRepository = securityContextRepository;
        this.audit = audit;
    }

    @GetMapping("/auth/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/auth/login")
    public UserSummary login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response,
            CsrfToken csrfToken
    ) {
        String key = body.username().trim().toLowerCase(Locale.ROOT);
        FailedLoginState lockState = failedLogins.get(key);
        if (lockState != null && lockState.lockedUntil() != null && lockState.lockedUntil().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiados intentos fallidos. Espera 5 minutos antes de reintentar.");
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, password_hash, full_name, role, active FROM app_users WHERE username = ?",
                body.username().trim()
        );
        if (rows.isEmpty()) {
            recordFailedLogin(key);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas.");
        }
        Map<String, Object> row = rows.get(0);
        boolean active = Boolean.TRUE.equals(row.get("active"));
        String hash = String.valueOf(row.get("password_hash"));
        if (!active || !passwordEncoder.matches(body.password(), hash)) {
            recordFailedLogin(key);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas.");
        }
        failedLogins.remove(key);

        String username = String.valueOf(row.get("username"));
        String role = String.valueOf(row.get("role"));
        var auth = new UsernamePasswordAuthenticationToken(
                username,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        audit.record(auth, "identity", "LOGIN", "USER", String.valueOf(row.get("id")), "Inicio de sesión correcto");

        return new UserSummary(
                UUID.fromString(String.valueOf(row.get("id"))),
                username,
                String.valueOf(row.get("full_name")),
                role
        );
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, Authentication auth) {
        if (auth != null) {
            audit.record(auth, "identity", "LOGOUT", "USER", auth.getName(), "Cierre de sesión");
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/auth/me")
    public UserSummary me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no iniciada.");
        }
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, username, full_name, role FROM app_users WHERE username = ?",
                auth.getName()
        );
        return new UserSummary(
                UUID.fromString(String.valueOf(row.get("id"))),
                String.valueOf(row.get("username")),
                String.valueOf(row.get("full_name")),
                String.valueOf(row.get("role"))
        );
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, page) * safeSize;
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM app_users", Integer.class);
        List<UserSummary> items = jdbc.query(
                "SELECT id, username, full_name, role FROM app_users ORDER BY username LIMIT ? OFFSET ?",
                (rs, i) -> new UserSummary(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("username"),
                        rs.getString("full_name"),
                        rs.getString("role")
                ),
                safeSize, offset
        );
        int totalCount = total != null ? total : 0;
        return Map.of(
                "items", items,
                "page", page,
                "size", safeSize,
                "totalElements", totalCount,
                "totalPages", (int) Math.ceil(totalCount / (double) safeSize)
        );
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public UserSummary createUser(@Valid @RequestBody CreateUserRequest body, Authentication auth) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO app_users (id, company_id, username, password_hash, full_name, role, active)
                VALUES (?, ?, ?, ?, ?, ?, TRUE)
                """,
                id,
                DEMO_COMPANY_ID,
                body.username().trim(),
                passwordEncoder.encode(body.password()),
                body.fullName().trim(),
                body.role()
        );
        audit.record(auth, "identity", "CREATE_USER", "USER", id.toString(), "Usuario interno creado con rol " + body.role());
        return new UserSummary(id, body.username().trim(), body.fullName().trim(), body.role());
    }

    private void recordFailedLogin(String key) {
        failedLogins.compute(key, (k, existing) -> {
            int nextCount = (existing == null || (existing.lockedUntil() != null && !existing.lockedUntil().isAfter(Instant.now())))
                    ? 1
                    : existing.attempts() + 1;
            Instant until = nextCount >= MAX_FAILED_ATTEMPTS ? Instant.now().plus(LOCK_MINUTES, ChronoUnit.MINUTES) : null;
            return new FailedLoginState(nextCount, until);
        });
    }

    private record FailedLoginState(int attempts, Instant lockedUntil) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record CreateUserRequest(
            @NotBlank String username,
            @NotBlank String fullName,
            @Pattern(regexp = "ADMIN|OPERADOR") String role,
            @NotBlank String password
    ) {
    }

    public record UserSummary(UUID id, String username, String fullName, String role) {
    }
}
