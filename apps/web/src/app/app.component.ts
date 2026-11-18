import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject } from '@angular/core';

interface SystemInfo {
  corridor: string;
  company: string;
  timezone: string;
  currency: string;
  delayThresholdMinutes: number;
  roles: string[];
  modules: string[];
  schemaInitialized: boolean;
  seededRouteSegments: number;
  serverTimeUtc: string;
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="shell">
      <header class="topbar">
        <div>
          <p class="eyebrow">Portal interno operativo · Hora Perú (America/Lima) · Moneda PEN (S/)</p>
          <h1>Expreso Corredor Central — {{ systemInfo?.corridor || 'Lima–Huancayo' }}</h1>
        </div>
        <div class="badges" aria-label="Estados del sistema">
          <span class="badge badge-demo">[Demo] Base inicial PR 1</span>
          <span class="badge badge-ok">[Vigente] Contrato /api/v1</span>
        </div>
      </header>

      <section class="status-banner" *ngIf="loading" role="status">
        Cargando estado de la API (/api/v1/system/info)...
      </section>

      <section class="status-banner error" *ngIf="errorMessage" role="alert">
        <strong>Error de conexión o conflicto:</strong> {{ errorMessage }}
        <span class="badge badge-insufficient">[Información insuficiente] Sin respuesta de API</span>
      </section>

      <section class="card summary" *ngIf="systemInfo">
        <h2>Estado del Monolito Modular y Esquema PostgreSQL</h2>
        <dl class="meta-grid">
          <div><dt>Empresa demo</dt><dd>{{ systemInfo.company }}</dd></div>
          <div><dt>Corredor activo</dt><dd>{{ systemInfo.corridor }}</dd></div>
          <div><dt>Tramos semilla (Flyway V1)</dt><dd>{{ systemInfo.seededRouteSegments }} tramos</dd></div>
          <div><dt>Umbral de retraso IA</dt><dd>&ge; {{ systemInfo.delayThresholdMinutes }} minutos</dd></div>
          <div><dt>Roles habilitados</dt><dd>{{ systemInfo.roles.join(' / ') }}</dd></div>
          <div><dt>Hora consulta (Lima)</dt><dd>{{ formatPeruTime(systemInfo.serverTimeUtc) }}</dd></div>
        </dl>
      </section>

      <section class="blocks-grid" aria-label="Bloques del detalle de viaje">
        <article class="card">
          <div class="card-head">
            <h3>1. Pronóstico por tramo</h3>
            <span class="badge badge-pending">[Por verificar] PR 6</span>
          </div>
          <p>Fuente prevista: Open-Meteo horario (Lima, Matucana, Ticlio, La Oroya, Jauja, Huancayo) y consulta preventiva SENAMHI.</p>
          <p class="meta">Estado inicial: Información insuficiente hasta activar refresco horario.</p>
        </article>

        <article class="card">
          <div class="card-head">
            <h3>2. Alertas viales</h3>
            <span class="badge badge-pending">[Por verificar] PR 6</span>
          </div>
          <p>Registro manual de cierres o tránsito restringido verificado en SUTRAN / MTC con enlace, vigencia y origen oficial o simulado.</p>
          <p class="meta">Estado inicial: Sin alertas activas cargadas en base.</p>
        </article>

        <article class="card">
          <div class="card-head">
            <h3>3. Riesgo experimental (&ge; 30 min)</h3>
            <span class="badge badge-demo">[Demo] PR 7</span>
          </div>
          <p>Estimación previa a la salida vía FastAPI + scikit-learn. Si faltan datos climáticos o servicio, degrada a «Información insuficiente».</p>
          <p class="meta">Fuente: Servicio interno /internal/v1/predictions.</p>
        </article>

        <article class="card">
          <div class="card-head">
            <h3>4. Contingencias de flota (+30 / +60 min)</h3>
            <span class="badge badge-pending">[Por verificar] PR 8</span>
          </div>
          <p>Evalúa impacto en el siguiente viaje del bus, verifica capacidad de asientos/carga, cierres viales y confirma con control de versión (409).</p>
          <p class="meta">Decisión final siempre confirmada por el operador.</p>
        </article>
      </section>

      <section class="blocks-grid two-col" aria-label="Vistas operativas previstas">
        <article class="card">
          <div class="card-head">
            <h3>Plano de asientos y ventas</h3>
            <span class="badge badge-insufficient">[Información insuficiente] PR 3–4</span>
          </div>
          <p>Inventario generado al programar viajes, bloqueo transaccional en PostgreSQL e idempotencia para boletos internos en S/.</p>
        </article>

        <article class="card">
          <div class="card-head">
            <h3>Historial de encomiendas</h3>
            <span class="badge badge-insufficient">[Información insuficiente] PR 5</span>
          </div>
          <p>Seguimiento administrativo con transición validada: RECIBIDA &rarr; EN_TRANSPORTE &rarr; ENTREGADA y control de peso en kg.</p>
        </article>
      </section>
    </div>
  `,
  styles: [`
    .shell { max-width: 1120px; margin: 0 auto; padding: 1.25rem; display: grid; gap: 1rem; }
    .topbar { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: flex-start; gap: 0.75rem; background: var(--surface); border: 1px solid var(--border); border-left: 5px solid var(--brand); border-radius: 8px; padding: 1rem 1.25rem; }
    .eyebrow { margin: 0 0 0.25rem; font-size: 0.85rem; color: var(--muted); }
    h1 { margin: 0; font-size: 1.35rem; }
    h2, h3 { margin: 0 0 0.5rem; font-size: 1.05rem; }
    .badges { display: flex; flex-wrap: wrap; gap: 0.5rem; }
    .badge { display: inline-block; font-size: 0.78rem; font-weight: 600; padding: 0.2rem 0.55rem; border-radius: 4px; border: 1px solid transparent; }
    .badge-demo { background: var(--demo-bg); color: var(--demo-fg); border-color: #c7d2fe; }
    .badge-ok { background: var(--ok-bg); color: var(--ok-fg); border-color: #b7e4c7; }
    .badge-pending { background: var(--warn-bg); color: var(--warn-fg); border-color: #ffd8a8; }
    .badge-insufficient { background: var(--err-bg); color: var(--err-fg); border-color: #f5c2c0; }
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 1rem 1.15rem; }
    .card-head { display: flex; justify-content: space-between; align-items: center; gap: 0.5rem; flex-wrap: wrap; }
    .meta-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 0.75rem; margin: 0.5rem 0 0; }
    dt { font-size: 0.78rem; color: var(--muted); }
    dd { margin: 0.1rem 0 0; font-weight: 600; }
    .blocks-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 1rem; }
    .two-col { grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); }
    .meta { font-size: 0.85rem; color: var(--muted); margin-bottom: 0; }
    .status-banner { padding: 0.85rem 1rem; border-radius: 8px; background: var(--surface); border: 1px solid var(--border); }
    .status-banner.error { background: var(--err-bg); color: var(--err-fg); border-color: #f5c2c0; display: flex; flex-wrap: wrap; justify-content: space-between; gap: 0.5rem; align-items: center; }
  `],
})
export class AppComponent implements OnInit {
  private readonly http = inject(HttpClient);

  loading = true;
  errorMessage: string | null = null;
  systemInfo: SystemInfo | null = null;

  ngOnInit(): void {
    this.http.get<SystemInfo>('/api/v1/system/info').subscribe({
      next: (info) => {
        this.systemInfo = info;
        this.loading = false;
      },
      error: () => {
        this.errorMessage = 'No se pudo consultar /api/v1/system/info. Verifica que la API esté activa.';
        this.loading = false;
      },
    });
  }

  formatPeruTime(isoUtc: string): string {
    return new Intl.DateTimeFormat('es-PE', {
      timeZone: 'America/Lima',
      dateStyle: 'short',
      timeStyle: 'medium',
    }).format(new Date(isoUtc));
  }
}
