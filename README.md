# InnovacionTransporte — Portal Operativo Corredor Central (Lima–Huancayo)

Sistema web interno para la gestión de viajes, pasajes, boletos internos, encomiendas, pronóstico meteorológico por tramo, alertas viales, estimación experimental de riesgo de retraso (≥ 30 min) y simulación de contingencias de flota en el corredor demostrativo **Lima–Huancayo**.

## Equipo del Proyecto (UTP — Innovación y Transformación Digital)

- Mijael Ernesto Jayo Huamani (`U23312235`)
- Alexis Junior Belito Sotacuro (`U21228890`)
- Jhon Mijahel Carrasco Campos (`U2123046`)
- Mathew Steve Tenorio Aguilar (`U20216412`)
- Sunder Joaquin Sanchez Julcamoro (`U20202735`)

## Alcance y Supuesto Registrado

- **Base documental (`apf2.docx`)**: El proyecto parte del alcance académico del APF2 (una empresa demostrativa *Expreso Corredor Central S.A.C.*, corredor Lima–Huancayo, roles `ADMIN` y `OPERADOR`, datos ficticios, Open-Meteo horario por puntos del trayecto, consulta preventiva SENAMHI IDESEP WFS, alertas manuales SUTRAN/MTC y modelo experimental scikit-learn comparando Regresión Logística y Random Forest).
- **Exclusión de documentos fuente**: Por higiene de repositorio y privacidad, las copias de `apf2.docx`, archivos `.env` y datos reales (`data/real/`) están excluidos en `.gitignore`.
- **Ampliación acordada**: Se incorpora el **Simulador de Contingencias de Flota** (impacto de retrasos +30/+60 min sobre viajes encadenados, verificación de bus de reserva por terminal/asientos/carga, descarte de tramos cerrados y confirmación transaccional con control de versión).
- **Fuera de alcance del MVP**: Despacho automático sin confirmación humana, GPS en tiempo real, pasarela de pagos bancarios y facturación electrónica fiscal.

## Estructura del Monorepo

| Ruta | Propósito |
| --- | --- |
| `apps/web/` | Angular 19 + TypeScript. Proxy `/api/v1` en mismo origen y compilación web servida por Nginx. |
| `apps/api/` | Spring Boot 3.4 (Java 21), Maven Wrapper (`mvnw`), Spring Security, JPA y Flyway (único dueño del esquema PostgreSQL). |
| `services/risk/` | FastAPI + scikit-learn (Python 3.12), inferencia interna `/internal/v1/predictions` y entrenamiento reproducible (`DEMO`/`REAL`). |
| `contracts/` | Contratos OpenAPI 3.1 (`public-api-openapi.yaml` y `risk-internal-openapi.yaml`) con ejemplos. |
| `docs/` | Decisiones de arquitectura, modelo de datos, despliegue/rollback y guion de demo de aceptación. |
| `.github/workflows/` | `ci.yml`, `release.yml`, `deploy.yml` y `train-model.yml`. |
| `compose.yaml`, `.env.example` | Entorno local aislado (`edge_net` + `internal_net`), healthchecks y volumen persistente PostgreSQL. |

## Plan de Integración por PRs

| Orden | Rama | Alcance |
| --- | --- | --- |
| PR 1 | `chore/1-base-ci-compose` | Monorepo ejecutable, Compose, migración Flyway `V1`, healthchecks, contratos, docs y CI de PR. |
| PR 2 | `feat/2-sesion-roles` | Login/logout con cookie `HttpOnly`, CSRF y RBAC `ADMIN` / `OPERADOR` en API y UI. |
| PR 3 | `feat/3-flota-rutas-viajes` | Catálogos de buses/rutas, programación de viajes, terminales y plano de asientos. |
| PR 4 | `feat/4-pasajes-ventas` | Venta, boleto interno, anulación, concurrencia e idempotencia en PostgreSQL (`v0.1.0`). |
| PR 5 | `feat/5-encomiendas-reportes` | Seguimiento `RECIBIDA -> EN_TRANSPORTE -> ENTREGADA`, tiempos reales y reportes en PEN. |
| PR 6 | `feat/6-clima-alertas` | Pronóstico Open-Meteo por tramo con caché/timeout, alertas SUTRAN/MTC y estados ausentes/vencidos. |
| PR 7 | `feat/7-modelo-riesgo` | Integración Spring <-> FastAPI, versionado de inferencia y degradación controlada (`v0.2.0`). |
| PR 8 | `feat/8-contingencias-flota` | Simular +30/+60 min, comparar opciones, confirmar en transacción única y auditoría (`409`). |
| PR 9 | `chore/9-release-demo` | Publicación GHCR, despliegue reproducible, smoke tests, rollback y demo integrada (`v1.0.0`). |

## Gobernanza en GitHub (Repositorio Privado)

1. **Controles técnicos en GitHub**:
   - Flujo obligatorio vía Pull Request hacia `main`, checks automáticos de `.github/workflows/ci.yml` (`contracts-and-compose`, `api-test` contra PostgreSQL 16 real, `risk-test`, `web-test`, `compose-smoke`) y permisos mínimos (`contents: read` en PRs).
   - En planes gratuitos con repositorio privado sin reglas de protección avanzadas de rama, GitHub puede no bloquear el merge forzoso desde UI.
2. **Acuerdo explícito del equipo (5 integrantes)**:
   - Solo el mantenedor fusiona en `main` mediante *squash merge* tras verificar al menos 1 revisión de compañero y todos los checks en verde.
   - Cada rama nace de `main` actualizado (`chore/<issue>-<tema>`, `feat/<issue>-<tema>`, `fix/<issue>-<tema>`) y se elimina tras integrarse.
   - Nunca se suben archivos `.env`, `apf2.docx`, datos reales ni credenciales.

## Comandos Rápidos de Desarrollo y Verificación

```bash
# 1. Validar Docker Compose
docker compose -f compose.yaml --env-file .env.example config -q

# 2. Levantar entorno completo local (requiere Docker activo)
cp .env.example .env
docker compose up -d --build --wait
# Portal web y proxy API disponibles en http://localhost:8080

# 3. Probar servicio de riesgo FastAPI (local sin Docker)
cd services/risk
python -m pytest -v
python -m app.train_demo

# 4. Probar API Spring Boot + Flyway (local con H2 en modo PostgreSQL)
cd apps/api
./mvnw -B test

# 5. Compilar y probar portal web Angular
cd apps/web
npm install
npm run lint
npm run build
npm test
```

## Usuarios de Prueba y Escenario Semilla (`V2`)

- **Operador**: usuario `operador.hyo` / contraseña `Demo2026!` (rol `OPERADOR`)
- **Administrador**: usuario `admin` / contraseña `Demo2026!` (rol `ADMIN`)
- **Escenario semilla**: Viaje `LIM-HYO-01` llega a Huancayo a las `15:40` (preparación `20 min`) y encadena con `HYO-LIM-01` a las `16:15` (24 pasajes vendidos y 8 encomiendas de `18.5 kg`); flota incluye `C4R-902` (36 asientos, viable) y `C4R-903` (20 asientos, rechazado por capacidad).
