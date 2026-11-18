# Guion de Demo de Aceptación (Corredor Lima–Huancayo)

> Nota: Este guion cubre el recorrido de extremo a extremo proyectado hasta el PR 8/9; en el **PR 1** se verifica la base ejecutable (`/healthz`, `/actuator/health`, `/api/v1/system/info`, migración Flyway `V1` con 6 tramos y endpoint interno `/internal/v1/predictions`).

## Escenario Principal del Simulador de Contingencias (Aceptación Final)
1. **Contexto inicial**:
   - Bus asignado llega a **Huancayo (`HYO-TERM`)** a las **15:40** (hora Perú).
   - Tiempo mínimo de preparación en terminal: **20 minutos**.
   - Siguiente viaje programado del mismo bus sale a las **16:15** con **24 pasajes** vendidos y **8 encomiendas** registradas.
   - Margen original disponible: `16:15 - (15:40 + 20 min) = +15 minutos`.
2. **Evaluación de riesgo + Simulación (+30 y +60 min)**:
   - Se consulta el pronóstico por tramo (Lima, Matucana, Ticlio, La Oroya, Jauja, Huancayo) y se invoca el servicio experimental de riesgo (`≥ 30 min`).
   - En el escenario simulado de **+30 minutos** de retraso:
     - Llegada estimada: **16:10**.
     - Fin de preparación (+20 min): **16:30**.
     - Impacto en el siguiente viaje (programado 16:15): **pérdida de 15 minutos** (salida estimada 16:30).
   - En el escenario simulado de **+60 minutos**: fin de preparación **17:00**, demora proyectada de **45 minutos**.
3. **Comparación y validación de restricciones**:
   - **Bus de reserva viable**: ubicado en `HYO-TERM`, disponible en la ventana, con capacidad ≥ 24 asientos y carga suficiente para las 8 encomiendas -> permitido confirmar.
   - **Bus de reserva insuficiente**: bus con menos de 24 asientos o sin capacidad de carga -> rechazado automáticamente con motivo explícito.
   - **Tramo cerrado (SUTRAN/MTC)**: si se registra un cierre vigente en el tramo, cualquier itinerario que lo atraviese se excluye incluso si el puntaje experimental de retraso es bajo; rutas alternas no verificadas aparecen como **«Por verificar»** sin permitir confirmación.
4. **Confirmación transaccional y conflicto (`409 Conflict`)**:
   - Al confirmar la reasignación, Spring Boot revalida en una sola transacción versión del viaje, disponibilidad del bus de reserva y bloqueos de venta. Si ocurrió una venta o cambio concurrente, responde **409 Conflict** solicitando actualizar la simulación.
