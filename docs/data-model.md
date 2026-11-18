# Modelo de Datos (Propietario único: Spring Boot + Flyway)

## Migración Base Implementada (`V1__init_baseline.sql` — PR 1)

| Tabla | Rol en el dominio |
| --- | --- |
| `companies` | Empresa operadora (`Expreso Corredor Central S.A.C.`), moneda `PEN`, zona `America/Lima`. |
| `app_users` | Usuarios internos con rol `ADMIN` o `OPERADOR` y `password_hash`. |
| `terminals` | Terminales operativos (`LIM-YERB`, `HYO-TERM`) con coordenadas y altitud. |
| `routes` | Rutas activas (`LIM-HYO-01`), duración estándar (480 min) y verificación vial. |
| `route_segments` | Secuencia de 6 tramos con puntos meteorológicos (Chosica, Matucana, Ticlio, La Oroya, Jauja, Huancayo) y tiempo estimado de paso. |
| `audit_events` | Bitácora transversal con `correlation_id`, actor, módulo, acción y marca UTC (`occurred_at`). |

## Extensiones Planificadas por PR (Contratos ya alineados en `contracts/`)

- **PR 3 (`flota-rutas-viajes`)**: `buses` (capacidad de asientos, carga en kg, terminal base), `passengers`, `trips` (con `version` para bloqueo optimista, horario original y vigente, tiempos reales) y `trip_seats`.
- **PR 4 (`pasajes-ventas`)**: `sales`, `tickets` (restricción única parcial que impide dos boletos vigentes para el mismo `(trip_id, seat_number)`), `idempotency_records` (clave, hash del payload y respuesta almacenada -> `409` si cambia el contenido).
- **PR 5 (`encomiendas-reportes`)**: `parcels` (remitente, destinatario, peso kg, `tracking_code`, estado `RECIBIDA | EN_TRANSPORTE | ENTREGADA`) y `parcel_status_history`.
- **PR 6 (`clima-alertas`)**: `weather_forecasts` (coordenadas, `valid_at_utc`, `fetched_at_utc`, `expires_at_utc`, unidades y fuente) y `road_alerts` (tramo, fuente SUTRAN/MTC, enlace, vigencia, estado y origen `OFICIAL_MANUAL | SIMULADA`).
- **PR 7–8 (`riesgo` y `contingencias`)**: `risk_assessments` (umbral configurado, versión del modelo, modo `DEMO|REAL`, estado o `INFORMACION_INSUFICIENTE`, latencia) y `contingency_simulations` / `contingency_decisions` (reasignación atómica de bus, boletos y encomiendas).
