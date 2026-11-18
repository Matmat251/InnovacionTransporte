import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';

interface UserSummary {
  id: string;
  username: string;
  fullName: string;
  role: 'ADMIN' | 'OPERADOR';
}

interface TripItem {
  id: string;
  routeId: string;
  routeCode: string;
  routeName: string;
  roadStatusVerified: boolean;
  busId: string;
  busPlate: string;
  seatCapacity: number;
  enabledSeats: number;
  cargoCapacityKg: number;
  originTerminalCode: string;
  destinationTerminalCode: string;
  originalDepartureUtc: string;
  scheduledDepartureUtc: string;
  scheduledArrivalUtc: string;
  actualDepartureUtc: string;
  actualArrivalUtc: string;
  turnaroundMinutes: number;
  baseFarePen: number;
  status: string;
  version: number;
  soldTicketsCount: number;
  parcelCount: number;
  totalParcelWeightKg: number;
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="shell">
      <header class="topbar">
        <div>
          <p class="eyebrow">Expreso Corredor Central S.A.C. · Hora Perú (America/Lima) · Moneda PEN (S/)</p>
          <h1>Portal Operativo — Corredor Lima–Huancayo</h1>
        </div>

        <div class="auth-box" *ngIf="!currentUser">
          <form class="inline-form" (ngSubmit)="login()">
            <input type="text" [(ngModel)]="loginForm.username" name="username" placeholder="Usuario" required aria-label="Usuario" />
            <input type="password" [(ngModel)]="loginForm.password" name="password" placeholder="Contraseña" required aria-label="Contraseña" />
            <button type="submit" class="btn-primary" [disabled]="busy">Ingresar</button>
          </form>
          <div class="quick-logins">
            <button type="button" class="btn-ghost" (click)="quickLogin('operador.hyo', 'Demo2026!')">Demo Operador</button>
            <button type="button" class="btn-ghost" (click)="quickLogin('admin', 'Demo2026!')">Demo Admin</button>
          </div>
        </div>

        <div class="auth-box active-user" *ngIf="currentUser">
          <div>
            <strong>{{ currentUser.fullName }}</strong>
            <span class="badge badge-ok">[Rol: {{ currentUser.role }}]</span>
            <span class="badge badge-demo">[Demo]</span>
          </div>
          <button type="button" class="btn-ghost" (click)="logout()">Cerrar sesión</button>
        </div>
      </header>

      <section class="status-banner" *ngIf="infoMessage" role="status">{{ infoMessage }}</section>
      <section class="status-banner error" *ngIf="errorMessage" role="alert">
        <span><strong>{{ errorCode === 409 ? 'Conflicto operativo (409):' : 'Aviso / Error:' }}</strong> {{ errorMessage }}</span>
        <button type="button" class="btn-ghost" (click)="errorMessage = null">Cerrar</button>
      </section>

      <section class="card" *ngIf="!currentUser">
        <h2>Acceso interno requerido</h2>
        <p>Inicia sesión como <strong>OPERADOR</strong> (<code>operador.hyo</code>) o <strong>ADMIN</strong> (<code>admin</code>) con contraseña de prueba <code>Demo2026!</code> para gestionar viajes, pasajes, encomiendas y contingencias.</p>
        <div class="badges">
          <span class="badge badge-demo">[Demo] Datos ficticios</span>
          <span class="badge badge-pending">[Por verificar] Ruta alterna sin confirmar</span>
          <span class="badge badge-insufficient">[Información insuficiente] Degradación segura</span>
        </div>
      </section>

      <ng-container *ngIf="currentUser">
        <nav class="tabs" aria-label="Módulos del portal">
          <button type="button" [class.active]="activeTab === 'viajes'" (click)="activeTab = 'viajes'">Viajes y Contingencias</button>
          <button type="button" [class.active]="activeTab === 'pasajes'" (click)="activeTab = 'pasajes'">Plano de asientos y Pasajes</button>
          <button type="button" [class.active]="activeTab === 'encomiendas'" (click)="activeTab = 'encomiendas'">Historial de encomiendas</button>
          <button type="button" [class.active]="activeTab === 'reportes'" (click)="activeTab = 'reportes'; loadReportsAndCatalogs()">Reportes y Flota</button>
        </nav>

        <!-- TAB 1: VIAJES, CLIMA, ALERTAS, RIESGO Y CONTINGENCIAS -->
        <section *ngIf="activeTab === 'viajes'" class="stack">
          <article class="card">
            <div class="card-head">
              <h2>Viajes programados (Corredor Central)</h2>
              <button type="button" class="btn-ghost" (click)="loadTrips()">Actualizar</button>
            </div>
            <div class="trip-selector" *ngIf="trips.length > 0">
              <button
                type="button"
                *ngFor="let t of trips"
                class="trip-pill"
                [class.selected]="selectedTrip?.id === t.id"
                (click)="selectTrip(t)"
              >
                <strong>{{ t.routeCode }}</strong> · Bus {{ t.busPlate }} · Sale {{ formatPeruTime(t.scheduledDepartureUtc) }} · {{ t.soldTicketsCount }}/{{ t.enabledSeats }} as. · v{{ t.version }}
              </button>
            </div>
            <p class="meta" *ngIf="trips.length === 0">No hay viajes cargados todavía.</p>
          </article>

          <ng-container *ngIf="selectedTrip">
            <article class="card">
              <div class="card-head">
                <h3>Detalle del viaje seleccionado: {{ selectedTrip.routeName }}</h3>
                <span class="badge badge-ok">[Estado: {{ selectedTrip.status }} · Versión {{ selectedTrip.version }}]</span>
              </div>
              <dl class="meta-grid">
                <div><dt>Bus asignado</dt><dd>{{ selectedTrip.busPlate }} ({{ selectedTrip.enabledSeats }} as. / {{ selectedTrip.cargoCapacityKg }} kg)</dd></div>
                <div><dt>Salida programada (Perú)</dt><dd>{{ formatPeruTime(selectedTrip.scheduledDepartureUtc) }}</dd></div>
                <div><dt>Llegada programada (Perú)</dt><dd>{{ formatPeruTime(selectedTrip.scheduledArrivalUtc) }}</dd></div>
                <div><dt>Preparación / Ocupación</dt><dd>{{ selectedTrip.turnaroundMinutes }} min · {{ selectedTrip.soldTicketsCount }} pasajes · {{ selectedTrip.parcelCount }} encom. ({{ selectedTrip.totalParcelWeightKg }} kg)</dd></div>
              </dl>
            </article>

            <div class="blocks-grid">
              <!-- Bloque 1: Pronóstico -->
              <article class="card">
                <div class="card-head">
                  <h3>1. Pronóstico por tramo</h3>
                  <span class="badge" [ngClass]="badgeClass(weatherData?.overallStatus || 'INFORMACION_INSUFICIENTE')">
                    [{{ weatherData?.overallStatus || 'Información insuficiente' }}]
                  </span>
                </div>
                <p class="meta">Fuentes: Open-Meteo horario y consulta preventiva SENAMHI.</p>
                <div class="actions-row">
                  <button type="button" class="btn-primary" (click)="refreshWeather()" [disabled]="busy">Refrescar pronóstico</button>
                </div>
                <ul class="compact-list" *ngIf="weatherData?.observations?.length">
                  <li *ngFor="let obs of weatherData.observations">
                    <strong>{{ obs.checkpointName }}</strong>: {{ obs.dataStatus }}
                    <span *ngIf="obs.temperatureC !== undefined && obs.temperatureC !== null">
                      · {{ obs.temperatureC }} °C, lluvia {{ obs.precipitationMm }} mm ({{ obs.precipitationProbPct }}%), viento {{ obs.windKmh }} km/h
                    </span>
                    <span class="meta"> · Fuente {{ obs.source }} · Válido {{ formatPeruTime(obs.validAtUtc) }}</span>
                  </li>
                </ul>
              </article>

              <!-- Bloque 2: Alertas viales -->
              <article class="card">
                <div class="card-head">
                  <h3>2. Alertas viales (SUTRAN / MTC)</h3>
                  <span class="badge badge-pending">[Por verificar / Manual]</span>
                </div>
                <form class="stack-sm" (ngSubmit)="createRoadAlert()">
                  <select [(ngModel)]="alertForm.segmentId" name="segmentId" required aria-label="Tramo">
                    <option value="">Selecciona tramo...</option>
                    <option *ngFor="let seg of availableSegments" [value]="seg.id">{{ seg.routeCode }} - {{ seg.checkpointName }}</option>
                  </select>
                  <div class="inline-form">
                    <select [(ngModel)]="alertForm.status" name="alertStatus" aria-label="Estado vial">
                      <option value="DESPEJADO">DESPEJADO</option>
                      <option value="TRANSITO_RESTRINGIDO">TRANSITO_RESTRINGIDO</option>
                      <option value="CERRADO">CERRADO</option>
                    </select>
                    <select [(ngModel)]="alertForm.originType" name="originType" aria-label="Origen alerta">
                      <option value="OFICIAL_MANUAL">OFICIAL_MANUAL</option>
                      <option value="SIMULADA">SIMULADA</option>
                    </select>
                  </div>
                  <input type="text" [(ngModel)]="alertForm.description" name="alertDesc" placeholder="Detalle SUTRAN/MTC (ej. cierre por lluvia)" />
                  <button type="submit" class="btn-primary" [disabled]="busy">Registrar alerta vial</button>
                </form>
                <ul class="compact-list" *ngIf="roadAlerts.length">
                  <li *ngFor="let a of roadAlerts">
                    <span class="badge" [ngClass]="a.status === 'CERRADO' ? 'badge-insufficient' : 'badge-pending'">[{{ a.status }}]</span>
                    <strong>{{ a.checkpointName }}</strong> ({{ a.sourceName }} · {{ a.originType }}) — {{ a.description || 'Sin detalle' }}
                  </li>
                </ul>
                <p class="meta" *ngIf="roadAlerts.length === 0">Sin alertas viales activas registradas.</p>
              </article>

              <!-- Bloque 3: Riesgo experimental -->
              <article class="card">
                <div class="card-head">
                  <h3>3. Riesgo experimental (&ge; 30 min)</h3>
                  <span class="badge" [ngClass]="badgeClass(latestRisk?.status === 'OK' ? latestRisk?.mode : 'INFORMACION_INSUFICIENTE')">
                    [{{ latestRisk?.status === 'OK' ? latestRisk?.mode : 'Información insuficiente' }}]
                  </span>
                </div>
                <div *ngIf="latestRisk">
                  <p>
                    Nivel: <strong>{{ latestRisk.riskLevel }}</strong>
                    <span *ngIf="latestRisk.delayProbability !== null && latestRisk.delayProbability !== undefined">
                      · Probabilidad calibrada: <strong>{{ (latestRisk.delayProbability * 100) | number:'1.0-1' }}%</strong>
                    </span>
                  </p>
                  <p class="meta">Modelo {{ latestRisk.modelVersion }} · Umbral {{ latestRisk.thresholdMinutes }} min · Evaluado {{ formatPeruTime(latestRisk.evaluatedAtUtc) }}</p>
                </div>
                <p class="meta" *ngIf="!latestRisk">Sin evaluación registrada aún.</p>
                <button type="button" class="btn-primary" (click)="evaluateRisk()" [disabled]="busy">Evaluar riesgo IA</button>
              </article>

              <!-- Bloque 4: Simulador de contingencias -->
              <article class="card">
                <div class="card-head">
                  <h3>4. Simulador de contingencias (+30 / +60 min)</h3>
                  <span class="badge badge-demo">[Operador confirma]</span>
                </div>
                <button type="button" class="btn-primary" (click)="simulateContingency()" [disabled]="busy">Simular escenarios +30 / +60 min</button>

                <div *ngIf="simulation" class="stack-sm sim-box">
                  <p>
                    Margen base: <strong>{{ simulation.marginMinutes }} min</strong> ·
                    Escenario +30m: listo <strong>{{ formatPeruTime(simulation.delay30ReadyUtc) }}</strong> (pierde <strong>{{ simulation.delay30LostMinutes }} min</strong>) ·
                    Escenario +60m: listo <strong>{{ formatPeruTime(simulation.delay60ReadyUtc) }}</strong> (pierde <strong>{{ simulation.delay60LostMinutes }} min</strong>)
                  </p>
                  <p *ngIf="simulation.hasActiveRoadClosure" class="badge badge-insufficient">
                    [Cierre vial activo] {{ simulation.closedSegmentAlerts.join(', ') }}
                  </p>
                  <div class="option-row" *ngFor="let opt of simulation.options">
                    <div>
                      <span class="badge" [ngClass]="opt.confirmable ? 'badge-ok' : (opt.statusLabel === 'Por verificar' ? 'badge-pending' : 'badge-insufficient')">
                        [{{ opt.statusLabel }}]
                      </span>
                      <strong>{{ opt.label }}</strong>
                      <p class="meta">{{ opt.reason }}</p>
                    </div>
                    <button
                      type="button"
                      class="btn-primary"
                      *ngIf="opt.confirmable"
                      (click)="confirmContingency(opt)"
                      [disabled]="busy"
                    >
                      Confirmar
                    </button>
                  </div>
                </div>
              </article>
            </div>
          </ng-container>
        </section>

        <!-- TAB 2: PLANO DE ASIENTOS Y PASAJES -->
        <section *ngIf="activeTab === 'pasajes'" class="stack">
          <article class="card" *ngIf="selectedTrip">
            <div class="card-head">
              <h2>Plano de asientos — {{ selectedTrip.routeCode }} ({{ selectedTrip.busPlate }})</h2>
              <button type="button" class="btn-ghost" (click)="loadSeats(selectedTrip.id)">Actualizar plano</button>
            </div>

            <div class="seats-grid">
              <button
                type="button"
                *ngFor="let seat of seats"
                class="seat-btn"
                [class.sold]="seat.status === 'VENDIDO'"
                [class.selected]="saleForm.seatNumber === seat.seatNumber"
                (click)="selectSeat(seat)"
              >
                <span>#{{ seat.seatNumber }}</span>
                <small>{{ seat.status === 'VENDIDO' ? 'Ocupado' : 'Libre' }}</small>
              </button>
            </div>

            <form class="inline-form form-box" (ngSubmit)="sellTicket()">
              <label>Asiento <input type="number" [(ngModel)]="saleForm.seatNumber" name="seatNumber" min="1" required /></label>
              <label>Pasajero
                <select [(ngModel)]="saleForm.passengerId" name="passengerId" required>
                  <option *ngFor="let p of passengers" [value]="p.id">{{ p.fullName }} ({{ p.documentNumber }})</option>
                </select>
              </label>
              <label>Monto S/ <input type="number" step="0.01" [(ngModel)]="saleForm.amountPen" name="amountPen" required /></label>
              <label>Clave idempotencia <input type="text" [(ngModel)]="saleForm.idempotencyKey" name="idempotencyKey" required /></label>
              <button type="submit" class="btn-primary" [disabled]="busy">Vender boleto</button>
            </form>

            <div *ngIf="lastTicket" class="card ticket-card">
              <h3>Boleto interno emitido: {{ lastTicket.ticketCode }} ({{ lastTicket.ticketStatus }})</h3>
              <p>Asiento #{{ lastTicket.seatNumber }} · Importe: S/ {{ lastTicket.amountPen | number:'1.2-2' }} · Idempotente: {{ lastTicket.idempotentReplay ? 'Sí (reutilizado)' : 'Nuevo' }}</p>
              <button type="button" class="btn-ghost" *ngIf="lastTicket.saleStatus === 'VIGENTE'" (click)="cancelSale(lastTicket.saleId)">Anular venta</button>
            </div>
          </article>
        </section>

        <!-- TAB 3: ENCOMIENDAS -->
        <section *ngIf="activeTab === 'encomiendas'" class="stack">
          <article class="card">
            <h2>Registrar nueva encomienda</h2>
            <form class="inline-form" (ngSubmit)="createParcel()" *ngIf="selectedTrip">
              <input type="text" [(ngModel)]="parcelForm.senderName" name="senderName" placeholder="Remitente" required />
              <input type="text" [(ngModel)]="parcelForm.recipientName" name="recipientName" placeholder="Destinatario" required />
              <input type="number" step="0.1" [(ngModel)]="parcelForm.weightKg" name="weightKg" placeholder="Peso (kg)" required />
              <input type="number" step="0.01" [(ngModel)]="parcelForm.amountPen" name="parcelAmountPen" placeholder="Monto S/" required />
              <button type="submit" class="btn-primary" [disabled]="busy">Registrar encomienda</button>
            </form>
          </article>

          <article class="card">
            <div class="card-head">
              <h2>Seguimiento de encomiendas (RECIBIDA &rarr; EN_TRANSPORTE &rarr; ENTREGADA)</h2>
              <div class="inline-form">
                <input type="text" [(ngModel)]="parcelFilter" name="parcelFilter" placeholder="Buscar código ENC-..." />
                <button type="button" class="btn-ghost" (click)="loadParcels()">Buscar</button>
              </div>
            </div>
            <table class="data-table" *ngIf="parcels.length">
              <thead>
                <tr><th>Código</th><th>Remitente / Destinatario</th><th>Peso</th><th>Monto</th><th>Estado</th><th>Acción</th></tr>
              </thead>
              <tbody>
                <tr *ngFor="let p of parcels">
                  <td><strong>{{ p.trackingCode }}</strong></td>
                  <td>{{ p.senderName }} &rarr; {{ p.recipientName }}</td>
                  <td>{{ p.weightKg }} kg</td>
                  <td>S/ {{ p.amountPen | number:'1.2-2' }}</td>
                  <td><span class="badge badge-ok">[{{ p.status }}]</span></td>
                  <td>
                    <button type="button" class="btn-ghost" *ngIf="p.status === 'RECIBIDA'" (click)="advanceParcel(p.id, 'EN_TRANSPORTE')">A EN_TRANSPORTE</button>
                    <button type="button" class="btn-primary" *ngIf="p.status === 'EN_TRANSPORTE'" (click)="advanceParcel(p.id, 'ENTREGADA')">Marcar ENTREGADA</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </article>
        </section>

        <!-- TAB 4: REPORTES Y FLOTA -->
        <section *ngIf="activeTab === 'reportes'" class="stack">
          <article class="card" *ngIf="report">
            <h2>Indicadores operativos (Moneda PEN)</h2>
            <dl class="meta-grid">
              <div><dt>Ingresos por pasajes vigentes</dt><dd>S/ {{ report.validTicketRevenuePen | number:'1.2-2' }}</dd></div>
              <div><dt>Ingresos por encomiendas</dt><dd>S/ {{ report.parcelRevenuePen | number:'1.2-2' }}</dd></div>
              <div><dt>Ocupación (vendidos / habilitados)</dt><dd>{{ report.occupancyPct }}% ({{ report.validTicketsCount }}/{{ report.enabledSeatsCount }})</dd></div>
              <div><dt>Viajes con clima vigente / reales</dt><dd>{{ report.tripsWithValidWeather }} / {{ report.tripsWithActualTimes }}</dd></div>
            </dl>
          </article>

          <article class="card">
            <h2>Flota de buses</h2>
            <table class="data-table">
              <thead><tr><th>Placa</th><th>Modelo</th><th>Asientos habilitados</th><th>Carga kg</th><th>Terminal</th><th>Estado</th></tr></thead>
              <tbody>
                <tr *ngFor="let b of buses">
                  <td><strong>{{ b.plate }}</strong></td><td>{{ b.model }}</td><td>{{ b.enabledSeats }}</td><td>{{ b.cargoCapacityKg }}</td><td>{{ b.currentTerminalCode }}</td><td>{{ b.status }}</td>
                </tr>
              </tbody>
            </table>
          </article>
        </section>
      </ng-container>
    </div>
  `,
  styles: [`
    .shell { max-width: 1180px; margin: 0 auto; padding: 1rem; display: grid; gap: 1rem; }
    .topbar { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 0.75rem; background: var(--surface); border: 1px solid var(--border); border-left: 5px solid var(--brand); border-radius: 8px; padding: 0.9rem 1.15rem; }
    .eyebrow { margin: 0 0 0.2rem; font-size: 0.82rem; color: var(--muted); }
    h1 { margin: 0; font-size: 1.25rem; }
    h2, h3 { margin: 0 0 0.5rem; font-size: 1.02rem; }
    .auth-box { display: flex; flex-direction: column; gap: 0.4rem; align-items: flex-end; }
    .active-user { flex-direction: row; align-items: center; gap: 0.75rem; }
    .inline-form { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; }
    .quick-logins, .actions-row, .badges { display: flex; flex-wrap: wrap; gap: 0.4rem; }
    .tabs { display: flex; flex-wrap: wrap; gap: 0.5rem; }
    .tabs button { padding: 0.55rem 0.9rem; border-radius: 6px; border: 1px solid var(--border); background: var(--surface); cursor: pointer; font-weight: 600; }
    .tabs button.active { background: var(--brand); color: #fff; border-color: var(--brand); }
    .stack { display: grid; gap: 1rem; }
    .stack-sm { display: grid; gap: 0.5rem; margin-top: 0.5rem; }
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 1rem; }
    .card-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.5rem; margin-bottom: 0.5rem; }
    .blocks-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 1rem; }
    .meta-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 0.75rem; margin: 0; }
    dt { font-size: 0.78rem; color: var(--muted); }
    dd { margin: 0.1rem 0 0; font-weight: 600; }
    .trip-selector { display: flex; flex-wrap: wrap; gap: 0.5rem; }
    .trip-pill { text-align: left; padding: 0.55rem 0.75rem; border: 1px solid var(--border); border-radius: 6px; background: #f8fafc; cursor: pointer; }
    .trip-pill.selected { border-color: var(--brand); background: #eaf2fb; }
    .badge { display: inline-block; font-size: 0.76rem; font-weight: 600; padding: 0.18rem 0.5rem; border-radius: 4px; }
    .badge-demo { background: var(--demo-bg); color: var(--demo-fg); }
    .badge-ok { background: var(--ok-bg); color: var(--ok-fg); }
    .badge-pending { background: var(--warn-bg); color: var(--warn-fg); }
    .badge-insufficient { background: var(--err-bg); color: var(--err-fg); }
    .btn-primary { background: var(--brand); color: #fff; border: 0; border-radius: 5px; padding: 0.45rem 0.8rem; cursor: pointer; font-weight: 600; }
    .btn-ghost { background: #fff; color: var(--text); border: 1px solid var(--border); border-radius: 5px; padding: 0.4rem 0.75rem; cursor: pointer; }
    input, select { padding: 0.4rem 0.55rem; border: 1px solid var(--border); border-radius: 5px; font: inherit; }
    .compact-list { padding-left: 1.1rem; margin: 0.6rem 0 0; display: grid; gap: 0.35rem; font-size: 0.88rem; }
    .option-row { display: flex; justify-content: space-between; align-items: center; gap: 0.75rem; padding: 0.6rem; border: 1px solid var(--border); border-radius: 6px; }
    .seats-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(76px, 1fr)); gap: 0.45rem; margin-bottom: 1rem; }
    .seat-btn { padding: 0.45rem; border-radius: 6px; border: 1px solid var(--border); background: var(--ok-bg); color: var(--ok-fg); cursor: pointer; display: flex; flex-direction: column; align-items: center; }
    .seat-btn.sold { background: var(--err-bg); color: var(--err-fg); }
    .seat-btn.selected { outline: 2px solid var(--brand); }
    .data-table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
    .data-table th, .data-table td { text-align: left; padding: 0.5rem; border-bottom: 1px solid var(--border); }
    .meta { font-size: 0.83rem; color: var(--muted); margin: 0.25rem 0 0; }
    .status-banner { padding: 0.75rem 1rem; border-radius: 6px; background: var(--ok-bg); color: var(--ok-fg); display: flex; justify-content: space-between; align-items: center; }
    .status-banner.error { background: var(--err-bg); color: var(--err-fg); }
  `],
})
export class AppComponent implements OnInit {
  private readonly http = inject(HttpClient);

  currentUser: UserSummary | null = null;
  activeTab: 'viajes' | 'pasajes' | 'encomiendas' | 'reportes' = 'viajes';
  busy = false;
  infoMessage: string | null = null;
  errorMessage: string | null = null;
  errorCode = 0;

  loginForm = { username: '', password: '' };
  trips: TripItem[] = [];
  selectedTrip: TripItem | null = null;
  weatherData: any = null;
  roadAlerts: any[] = [];
  availableSegments: any[] = [];
  latestRisk: any = null;
  simulation: any = null;

  seats: any[] = [];
  passengers: any[] = [];
  lastTicket: any = null;
  parcels: any[] = [];
  parcelFilter = '';
  buses: any[] = [];
  report: any = null;

  alertForm = {
    segmentId: '',
    sourceName: 'SUTRAN',
    status: 'CERRADO',
    originType: 'OFICIAL_MANUAL',
    description: '',
  };

  saleForm = {
    seatNumber: 25,
    passengerId: '',
    amountPen: 65.0,
    idempotencyKey: 'venta-web-' + Date.now(),
  };

  parcelForm = {
    senderName: '',
    recipientName: '',
    weightKg: 10.0,
    amountPen: 25.0,
  };

  ngOnInit(): void {
    this.http.get('/api/v1/auth/csrf').subscribe({ next: () => {}, error: () => {} });
    this.http.get<UserSummary>('/api/v1/auth/me').subscribe({
      next: (u) => {
        this.currentUser = u;
        this.loadInitialOperationalData();
      },
      error: () => {},
    });
  }

  quickLogin(username: string, password: string): void {
    this.loginForm = { username, password };
    this.login();
  }

  login(): void {
    this.busy = true;
    this.errorMessage = null;
    this.http.post<UserSummary>('/api/v1/auth/login', this.loginForm).subscribe({
      next: (user) => {
        this.currentUser = user;
        this.busy = false;
        this.http.get('/api/v1/auth/csrf').subscribe({ next: () => this.loadInitialOperationalData() });
      },
      error: (err) => this.handleError(err, 'Credenciales inválidas.'),
    });
  }

  logout(): void {
    this.http.post('/api/v1/auth/logout', {}).subscribe({
      next: () => {
        this.currentUser = null;
        this.trips = [];
        this.selectedTrip = null;
      },
      error: () => {
        this.currentUser = null;
      },
    });
  }

  loadInitialOperationalData(): void {
    this.loadTrips();
    this.loadRoutesAndSegments();
    this.loadPassengers();
    this.loadParcels();
  }

  loadTrips(): void {
    this.http.get<{ items: TripItem[] }>('/api/v1/trips').subscribe({
      next: (res) => {
        this.trips = res.items;
        if (!this.selectedTrip && res.items.length > 0) {
          this.selectTrip(res.items[0]);
        } else if (this.selectedTrip) {
          const updated = res.items.find((t) => t.id === this.selectedTrip?.id);
          if (updated) {
            this.selectedTrip = updated;
          }
        }
      },
    });
  }

  selectTrip(trip: TripItem): void {
    this.selectedTrip = trip;
    this.simulation = null;
    this.loadWeather(trip.id);
    this.loadRoadAlerts(trip.routeId);
    this.loadRisk(trip.id);
    this.loadSeats(trip.id);
  }

  loadRoutesAndSegments(): void {
    this.http.get<{ items: any[] }>('/api/v1/routes').subscribe({
      next: (res) => {
        this.availableSegments = res.items.flatMap((r) =>
          (r.segments || []).map((s: any) => ({ ...s, routeCode: r.code }))
        );
        if (!this.alertForm.segmentId && this.availableSegments.length > 0) {
          this.alertForm.segmentId = this.availableSegments[0].id;
        }
      },
    });
  }

  loadWeather(tripId: string): void {
    this.http.get(`/api/v1/trips/${tripId}/weather`).subscribe({ next: (d) => (this.weatherData = d) });
  }

  refreshWeather(): void {
    if (!this.selectedTrip) return;
    this.busy = true;
    this.http.post(`/api/v1/trips/${this.selectedTrip.id}/weather-refreshes?force=true`, {}).subscribe({
      next: (d) => {
        this.weatherData = d;
        this.busy = false;
        this.infoMessage = 'Pronóstico actualizado con marcas de fuente y vigencia.';
      },
      error: (err) => this.handleError(err, 'No se pudo consultar el pronóstico.'),
    });
  }

  loadRoadAlerts(routeId: string): void {
    this.http.get<{ items: any[] }>(`/api/v1/road-alerts?routeId=${routeId}`).subscribe({
      next: (res) => (this.roadAlerts = res.items),
    });
  }

  createRoadAlert(): void {
    if (!this.alertForm.segmentId) return;
    this.busy = true;
    const validUntilUtc = new Date(Date.now() + 6 * 3600 * 1000).toISOString();
    this.http.post('/api/v1/road-alerts', { ...this.alertForm, validUntilUtc }).subscribe({
      next: () => {
        this.busy = false;
        this.infoMessage = 'Alerta vial registrada.';
        if (this.selectedTrip) this.loadRoadAlerts(this.selectedTrip.routeId);
      },
      error: (err) => this.handleError(err, 'Error al registrar alerta vial.'),
    });
  }

  loadRisk(tripId: string): void {
    this.http.get<{ assessments: any[] }>(`/api/v1/trips/${tripId}/risk-assessments`).subscribe({
      next: (res) => (this.latestRisk = res.assessments?.[0] || null),
    });
  }

  evaluateRisk(): void {
    if (!this.selectedTrip) return;
    this.busy = true;
    this.http.post(`/api/v1/trips/${this.selectedTrip.id}/risk-assessments`, {}).subscribe({
      next: (res) => {
        this.latestRisk = res;
        this.busy = false;
        this.infoMessage = 'Evaluación experimental de riesgo registrada.';
      },
      error: (err) => this.handleError(err, 'Error al evaluar riesgo.'),
    });
  }

  simulateContingency(): void {
    if (!this.selectedTrip) return;
    this.busy = true;
    this.http.post(`/api/v1/trips/${this.selectedTrip.id}/contingency-simulations`, { delayScenariosMinutes: [30, 60] }).subscribe({
      next: (res) => {
        this.simulation = res;
        this.busy = false;
      },
      error: (err) => this.handleError(err, 'No se pudo simular contingencia.'),
    });
  }

  confirmContingency(option: any): void {
    if (!this.selectedTrip || !this.simulation) return;
    this.busy = true;
    this.http.post(`/api/v1/trips/${this.selectedTrip.id}/contingency-decisions`, {
      simulationId: this.simulation.simulationId,
      expectedTripVersion: this.simulation.affectedTripVersion,
      actionType: option.actionType,
      reserveBusId: option.reserveBusId || null,
      newDepartureUtc: option.suggestedDepartureUtc || null,
      rationale: 'Confirmación desde panel operativo',
    }).subscribe({
      next: () => {
        this.busy = false;
        this.infoMessage = 'Decisión de contingencia aplicada y auditada en transacción única.';
        this.simulation = null;
        this.loadTrips();
      },
      error: (err) => this.handleError(err, 'Conflicto al confirmar contingencia; actualiza la simulación.'),
    });
  }

  loadSeats(tripId: string): void {
    this.http.get<{ seats: any[] }>(`/api/v1/trips/${tripId}/seats`).subscribe({
      next: (res) => (this.seats = res.seats),
    });
  }

  selectSeat(seat: any): void {
    this.saleForm.seatNumber = seat.seatNumber;
    this.saleForm.idempotencyKey = 'venta-web-' + Date.now();
  }

  loadPassengers(): void {
    this.http.get<{ items: any[] }>('/api/v1/passengers?size=50').subscribe({
      next: (res) => {
        this.passengers = res.items;
        if (!this.saleForm.passengerId && res.items.length > 0) {
          this.saleForm.passengerId = res.items[0].id;
        }
      },
    });
  }

  sellTicket(): void {
    if (!this.selectedTrip) return;
    this.busy = true;
    const headers = new HttpHeaders({ 'Idempotency-Key': this.saleForm.idempotencyKey });
    this.http.post('/api/v1/sales', {
      tripId: this.selectedTrip.id,
      seatNumber: this.saleForm.seatNumber,
      passengerId: this.saleForm.passengerId,
      amountPen: this.saleForm.amountPen,
    }, { headers }).subscribe({
      next: (res) => {
        this.lastTicket = res;
        this.busy = false;
        this.infoMessage = 'Venta procesada y boleto interno emitido.';
        this.loadSeats(this.selectedTrip!.id);
        this.loadTrips();
      },
      error: (err) => this.handleError(err, 'No se pudo completar la venta.'),
    });
  }

  cancelSale(saleId: string): void {
    this.busy = true;
    this.http.post(`/api/v1/sales/${saleId}/cancel`, { reason: 'Anulación solicitada en ventanilla' }).subscribe({
      next: () => {
        this.busy = false;
        this.infoMessage = 'Venta anulada y asiento liberado.';
        this.lastTicket = null;
        if (this.selectedTrip) this.loadSeats(this.selectedTrip.id);
        this.loadTrips();
      },
      error: (err) => this.handleError(err, 'No se pudo anular la venta.'),
    });
  }

  loadParcels(): void {
    const q = this.parcelFilter ? `?trackingCode=${encodeURIComponent(this.parcelFilter)}` : '?size=50';
    this.http.get<{ items: any[] }>(`/api/v1/parcels${q}`).subscribe({
      next: (res) => (this.parcels = res.items),
    });
  }

  createParcel(): void {
    if (!this.selectedTrip) return;
    this.busy = true;
    this.http.post('/api/v1/parcels', { ...this.parcelForm, tripId: this.selectedTrip.id }).subscribe({
      next: () => {
        this.busy = false;
        this.parcelForm.senderName = '';
        this.parcelForm.recipientName = '';
        this.infoMessage = 'Encomienda registrada en estado RECIBIDA.';
        this.loadParcels();
        this.loadTrips();
      },
      error: (err) => this.handleError(err, 'Error al registrar encomienda.'),
    });
  }

  advanceParcel(id: string, targetStatus: string): void {
    this.busy = true;
    this.http.post(`/api/v1/parcels/${id}/status-transitions`, { targetStatus, notes: 'Actualizado en panel' }).subscribe({
      next: () => {
        this.busy = false;
        this.infoMessage = 'Estado de encomienda actualizado a ' + targetStatus;
        this.loadParcels();
      },
      error: (err) => this.handleError(err, 'Transición de encomienda rechazada.'),
    });
  }

  loadReportsAndCatalogs(): void {
    this.http.get('/api/v1/reports/operations').subscribe({ next: (r) => (this.report = r) });
    this.http.get<{ items: any[] }>('/api/v1/buses').subscribe({ next: (b) => (this.buses = b.items) });
  }

  badgeClass(status: string): string {
    if (status === 'VIGENTE' || status === 'OK' || status === 'REAL') return 'badge-ok';
    if (status === 'DEMO') return 'badge-demo';
    if (status === 'POR_VERIFICAR') return 'badge-pending';
    return 'badge-insufficient';
  }

  formatPeruTime(isoUtc: string): string {
    if (!isoUtc) return '-';
    return new Intl.DateTimeFormat('es-PE', {
      timeZone: 'America/Lima',
      dateStyle: 'short',
      timeStyle: 'short',
    }).format(new Date(isoUtc));
  }

  private handleError(err: HttpErrorResponse, fallback: string): void {
    this.busy = false;
    this.errorCode = err.status;
    this.errorMessage = err.error?.detail || fallback;
  }
}
