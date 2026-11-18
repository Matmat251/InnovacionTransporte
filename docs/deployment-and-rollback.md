# Despliegue Reproducible y Estrategia de Rollback

## 1. Entorno Local y Demo (`compose.yaml`)
1. Copiar `.env.example` a `.env` (no commitear `.env`).
2. Validar configuración: `docker compose -f compose.yaml --env-file .env.example config -q`.
3. Construir y levantar con healthchecks: `docker compose up -d --build --wait`.
4. Verificar salud:
   - `http://localhost:8080/healthz` -> `ok`
   - `http://localhost:8080/actuator/health` -> `{"status":"UP"}`
   - `http://localhost:8080/api/v1/system/info` -> `corridor: "Lima-Huancayo"`, `seededRouteSegments: 6`
5. `postgres` y `risk` permanecen en la red `internal_net` sin puertos expuestos al host.

## 2. Publicación de Artefactos (`release.yml`)
- Cada commit integrado en `main` o tag `v*` ejecuta validación y construye las imágenes de `web`, `api` y `risk`.
- Cuando el repositorio tiene paquetes habilitados en GHCR, publica las imágenes y emite `release-manifest.json` con:
  - Commit SHA
  - Digests inmutables (`sha256:...`) de `web`, `api` y `risk`
  - Versión del modelo (`demo-logreg-rf-v0.1.0`)
  - Última migración Flyway (`V1__init_baseline.sql`)

## 3. Promoción por `deploy.yml`
1. Seleccionar `release_tag` y `target_environment` (`local-compose`, `staging`, `production`).
2. **Pre-requisito obligatorio en entornos remotos**:
   - Verificar HTTPS terminado en reverse proxy y `APP_COOKIE_SECURE=true`.
   - Tomar respaldo verificable de PostgreSQL (`pg_dump -Fc`) antes de aplicar nuevas migraciones Flyway.
   - Revisar compatibilidad hacia atrás de migraciones pendientes.
3. Desplegar primero en `staging`, validar healthchecks + flujo funcional completo y promover a `production` usando exactamente los mismos digests verificados.

## 4. Rollback
- **Caso A — Esquema compatible hacia atrás (solo expansión)**:
  1. Reemplazar los digests en Compose/servidor por los del `release-manifest.json` anterior.
  2. Ejecutar `docker compose up -d --no-build --wait`.
  3. Confirmar `/actuator/health` y flujo operativo básico.
- **Caso B — Migración incompatible**:
  1. Detener `api` (`docker compose stop api`) para congelar escrituras.
  2. Restaurar el respaldo tomado previo al despliegue (`pg_restore --clean --if-exists -d $POSTGRES_DB backup.dump`).
  3. Levantar los digests previos verificados y validar integridad de ventas, encomiendas y contingencias.
