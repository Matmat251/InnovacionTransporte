package pe.edu.utp.innovaciontransporte.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.time.Instant;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ProblemDetailsExceptionHandler {

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class, MissingRequestHeaderException.class})
    public ProblemDetail handleValidation(Exception ex, HttpServletRequest request) {
        String detail = ex instanceof MethodArgumentNotValidException
                ? "La solicitud contiene campos inválidos o incompletos."
                : ex.getMessage();
        return build(HttpStatus.BAD_REQUEST, "Error de validación", "validation-error", detail, request);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoSuchElementException.class})
    public ProblemDetail handleNotFound(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Recurso no encontrado", "not-found", ex.getMessage(), request);
    }

    @ExceptionHandler({IllegalStateException.class, DataIntegrityViolationException.class})
    public ProblemDetail handleConflict(Exception ex, HttpServletRequest request) {
        String detail = ex instanceof DataIntegrityViolationException
                ? "Conflicto de integridad o concurrencia sobre el recurso solicitado."
                : ex.getMessage();
        return build(HttpStatus.CONFLICT, "Conflicto operativo", "state-conflict", detail, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleForbidden(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "Acceso denegado", "forbidden", "No cuentas con el rol requerido para esta operación.", request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        HttpStatus resolved = status != null ? status : HttpStatus.BAD_REQUEST;
        return build(resolved, resolved.getReasonPhrase(), "http-status", ex.getReason(), request);
    }

    private ProblemDetail build(HttpStatus status, String title, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("urn:innovacion-transporte:problem:" + code));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        problem.setProperty("timestampUtc", Instant.now().toString());
        return problem;
    }
}
