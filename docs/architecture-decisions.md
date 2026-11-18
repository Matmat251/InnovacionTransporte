# Decisiones de Arquitectura (ADR)

## ADR-001: Supuesto documental y ampliación de Contingencias de Flota
- **Contexto**: `apf2.docx` define el alcance académico para una empresa del corredor central (Lima–Huancayo), roles `ADMIN` y `OPERADOR`, pronósticos Open-Meteo, consulta preventiva SENAMHI, alertas manuales SUTRAN/MTC y modelo experimental de retrasos ≥ 30 min. No se versiona el `.docx` dentro del repositorio.
- **Decisión**: Tomar el alcance de `apf2.docx` como base e incorporar el **Simulador de Contingencias de Flota** para evaluar el impacto de retrasos sobre viajes encadenados del mismo vehículo y confirmar reasignaciones de forma transaccional.

## ADR-002: Monolito modular Spring Boot + Servicio interno FastAPI
- **Decisión**: Concentrar reglas de negocio, seguridad, auditoría y propiedad exclusiva del esquema PostgreSQL (vía Flyway) en `apps/api` (Spring Boot 3.4, Java 21). Aislar la inferencia y entrenamiento scikit-learn en `services/risk` (FastAPI, Python 3.12) dentro de `internal_net`.
- **Motivo**: Evita fragmentar transacciones críticas (venta de asientos y reasignación de buses) en microservicios distribuidos, mientras mantiene Python dedicado a ML reproducible.
- **Degradación**: Si `services/risk` u Open-Meteo fallan o superan el timeout, ventas, encomiendas y simulación manual siguen operando y el panel reporta `Información insuficiente`.

## ADR-003: Mismo origen web, cookies HttpOnly, CSRF y privacidad de logs
- **Decisión**: `apps/web` (Angular) accede a la API bajo `/api/v1` en el mismo origen (Nginx en Compose/producción, `proxy.conf.json` en desarrollo local). Las sesiones usan cookies `HttpOnly` (`SameSite=Lax`, `Secure` en HTTPS) con protección CSRF por token (`X-XSRF-TOKEN`).
- **Trazabilidad y PII**: Todas las peticiones propagan `X-Correlation-Id` en cabeceras y SLF4J `MDC`. Queda prohibido registrar números de documento, teléfonos, credenciales o cuerpos con datos personales en logs.

## ADR-004: Manejo de tiempo y moneda
- **Decisión**: Almacenar y transmitir fechas como instantes UTC (`TIMESTAMP WITH TIME ZONE` en PostgreSQL, ISO-8601 UTC en JSON) y formatear en la interfaz con zona horaria `America/Lima`. Todo importe monetario se maneja en `PEN` con precisión decimal exacta (`NUMERIC(10, 2)` / `BigDecimal`).

## ADR-005: Reglas de ML experimental (DEMO vs. REAL)
- **Decisión**: El modelo solo consume variables disponibles antes de la salida (ruta, hora/día programados, duración prevista y resumen meteorológico previo). Está prohibido usar horarios reales de llegada como entrada.
- **Evaluación**: Separación temporal estricta (entrenamiento en periodo anterior, evaluación en periodo posterior), comparación frente a línea base mayoritaria y Regresión Logística vs. Random Forest calibrado.
