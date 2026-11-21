-- Migración V5: Persistencia de dictámenes Copiloto IA y factores explicativos de riesgo.

ALTER TABLE risk_assessments ADD COLUMN top_factors VARCHAR(500);

CREATE TABLE copilot_advisories (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trips(id),
    status VARCHAR(32) NOT NULL,
    provider VARCHAR(48) NOT NULL,
    model VARCHAR(80) NOT NULL,
    dictamen_ejecutivo VARCHAR(1000) NOT NULL,
    accion_recomendada VARCHAR(1000) NOT NULL,
    justificacion_auditoria VARCHAR(1000) NOT NULL,
    comunicado_operativo VARCHAR(1000) NOT NULL,
    latency_ms NUMERIC(10, 2) NOT NULL DEFAULT 0,
    created_by VARCHAR(64) NOT NULL,
    created_at_utc TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_copilot_advisories_trip ON copilot_advisories(trip_id, created_at_utc DESC);
