package pe.edu.utp.innovaciontransporte.common;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class JdbcHelper {

    private JdbcHelper() {
    }

    public static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(String.valueOf(value));
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts != null ? ts.toInstant() : null;
    }

    public static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof Instant inst) {
            return inst;
        }
        return OffsetDateTime.parse(String.valueOf(value)).toInstant();
    }

    public static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(value));
    }

    public static Map<String, Object> pageResponse(List<?> items, int page, int size, long total) {
        int safeSize = Math.max(1, size);
        return Map.of(
                "items", items,
                "page", page,
                "size", safeSize,
                "totalElements", total,
                "totalPages", (int) Math.ceil(total / (double) safeSize)
        );
    }
}
