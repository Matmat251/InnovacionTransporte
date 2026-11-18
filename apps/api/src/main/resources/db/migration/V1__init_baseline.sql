-- Migración base del monolito modular: empresa demostrativa, roles, terminales, corredor Lima-Huancayo y auditoría.

CREATE TABLE companies (
    id UUID PRIMARY KEY,
    ruc VARCHAR(11) NOT NULL UNIQUE,
    legal_name VARCHAR(150) NOT NULL,
    trade_name VARCHAR(150) NOT NULL,
    default_currency VARCHAR(3) NOT NULL DEFAULT 'PEN',
    timezone VARCHAR(64) NOT NULL DEFAULT 'America/Lima',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(150) NOT NULL,
    role VARCHAR(24) NOT NULL CHECK (role IN ('ADMIN', 'OPERADOR')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE terminals (
    id UUID PRIMARY KEY,
    code VARCHAR(24) NOT NULL UNIQUE,
    name VARCHAR(150) NOT NULL,
    city VARCHAR(80) NOT NULL,
    department VARCHAR(80) NOT NULL,
    latitude NUMERIC(9, 6) NOT NULL,
    longitude NUMERIC(9, 6) NOT NULL,
    elevation_masl INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE routes (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(180) NOT NULL,
    origin_terminal_id UUID NOT NULL REFERENCES terminals(id),
    destination_terminal_id UUID NOT NULL REFERENCES terminals(id),
    distance_km INTEGER NOT NULL,
    standard_duration_minutes INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    road_status_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE route_segments (
    id UUID PRIMARY KEY,
    route_id UUID NOT NULL REFERENCES routes(id),
    sequence_order INTEGER NOT NULL,
    name VARCHAR(120) NOT NULL,
    checkpoint_name VARCHAR(120) NOT NULL,
    latitude NUMERIC(9, 6) NOT NULL,
    longitude NUMERIC(9, 6) NOT NULL,
    elevation_masl INTEGER NOT NULL,
    estimated_offset_minutes INTEGER NOT NULL,
    UNIQUE (route_id, sequence_order)
);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    correlation_id VARCHAR(64) NOT NULL,
    actor_username VARCHAR(64) NOT NULL,
    actor_role VARCHAR(24) NOT NULL,
    module VARCHAR(48) NOT NULL,
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_events_occurred_at ON audit_events(occurred_at DESC);
CREATE INDEX idx_route_segments_route ON route_segments(route_id, sequence_order);

-- Datos semilla ficticios del corredor demostrativo Lima-Huancayo
INSERT INTO companies (id, ruc, legal_name, trade_name, default_currency, timezone)
VALUES (
    '11111111-1111-1111-1111-111111111101',
    '20600000001',
    'Expreso Corredor Central S.A.C.',
    'Expreso Corredor Central',
    'PEN',
    'America/Lima'
);

INSERT INTO terminals (id, code, name, city, department, latitude, longitude, elevation_masl)
VALUES
    ('22222222-2222-2222-2222-222222222201', 'LIM-YERB', 'Terminal Terrestre Yerbateros (Demo)', 'Lima', 'Lima', -12.067200, -76.994900, 210),
    ('22222222-2222-2222-2222-222222222202', 'HYO-TERM', 'Terminal Terrestre Huancayo (Demo)', 'Huancayo', 'Junín', -12.054500, -75.222900, 3259);

INSERT INTO routes (id, company_id, code, name, origin_terminal_id, destination_terminal_id, distance_km, standard_duration_minutes, active, road_status_verified)
VALUES (
    '33333333-3333-3333-3333-333333333301',
    '11111111-1111-1111-1111-111111111101',
    'LIM-HYO-01',
    'Lima - Huancayo (Carretera Central)',
    '22222222-2222-2222-2222-222222222201',
    '22222222-2222-2222-2222-222222222202',
    305,
    480,
    TRUE,
    TRUE
);

INSERT INTO route_segments (id, route_id, sequence_order, name, checkpoint_name, latitude, longitude, elevation_masl, estimated_offset_minutes)
VALUES
    ('44444444-4444-4444-4444-444444444401', '33333333-3333-3333-3333-333333333301', 1, 'Tramo 1: Salida Lima - Chosica', 'Chosica', -11.943100, -76.709400, 860, 65),
    ('44444444-4444-4444-4444-444444444402', '33333333-3333-3333-3333-333333333301', 2, 'Tramo 2: Ascenso Matucana', 'Matucana', -11.844200, -76.386100, 2378, 130),
    ('44444444-4444-4444-4444-444444444403', '33333333-3333-3333-3333-333333333301', 3, 'Tramo 3: Paso de Altura Ticlio', 'Ticlio (Anticona)', -11.598900, -76.188600, 4818, 220),
    ('44444444-4444-4444-4444-444444444404', '33333333-3333-3333-3333-333333333301', 4, 'Tramo 4: Empalme La Oroya', 'La Oroya', -11.522200, -75.899400, 3745, 290),
    ('44444444-4444-4444-4444-444444444405', '33333333-3333-3333-3333-333333333301', 5, 'Tramo 5: Valle del Mantaro - Jauja', 'Jauja', -11.775800, -75.496600, 3390, 405),
    ('44444444-4444-4444-4444-444444444406', '33333333-3333-3333-3333-333333333301', 6, 'Tramo 6: Arribo Terminal Huancayo', 'Huancayo', -12.054500, -75.222900, 3259, 480);
