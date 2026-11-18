import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';

type PortalTab =
  | 'riesgo-telemetria'
  | 'venta-pasajes'
  | 'encomiendas-carga'
  | 'programacion-flota'
  | 'reportes-auditoria';

interface UserSummary {
  id: string;
  username: string;
  fullName: string;
  role: 'ADMIN' | 'OPERADOR';
}

interface SystemInfo {
  corridor: string;
  company: string;
  timezone: string;
  currency: string;
  delayThresholdMinutes: number;
  seededRouteSegments: number;
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
  scheduledDepartureUtc: string;
  scheduledArrivalUtc: string;
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
  templateUrl: './app.component.html',
})
export class AppComponent implements OnInit {
  private readonly http = inject(HttpClient);

  currentUser: UserSummary | null = null;
  systemInfo: SystemInfo | null = null;
  activeTab: PortalTab = 'riesgo-telemetria';
  loginModule: PortalTab = 'riesgo-telemetria';
  loginTerminal = 'LIM-YERB';
  showPassword = false;

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

  actualTimesForm = {
    actualDepartureUtc: '2026-11-20T12:45:00Z',
    actualArrivalUtc: '2026-11-20T21:10:00Z',
    incidentNotes: '',
  };

  ngOnInit(): void {
    this.http.get<SystemInfo>('/api/v1/system/info').subscribe({
      next: (info) => (this.systemInfo = info),
      error: () => {},
    });
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
        this.activeTab = this.loginModule;
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

  switchTab(tab: PortalTab): void {
    this.activeTab = tab;
    if (tab === 'programacion-flota' || tab === 'reportes-auditoria') {
      this.loadReportsAndCatalogs();
    }
  }

  loadInitialOperationalData(): void {
    this.loadTrips();
    this.loadRoutesAndSegments();
    this.loadPassengers();
    this.loadParcels();
    this.loadReportsAndCatalogs();
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

  onSelectTripId(tripId: string): void {
    const found = this.trips.find((t) => t.id === tripId);
    if (found) {
      this.selectTrip(found);
    }
  }

  selectTrip(trip: TripItem): void {
    this.selectedTrip = trip;
    this.simulation = null;
    this.saleForm.amountPen = trip.baseFarePen;
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
      rationale: 'Confirmación desde panel AndesRoute Central',
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
    this.regenerateIdempotencyKey();
  }

  regenerateIdempotencyKey(): void {
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
    this.http.post(`/api/v1/parcels/${id}/status-transitions`, { targetStatus, notes: 'Actualizado en panel AndesRoute' }).subscribe({
      next: () => {
        this.busy = false;
        this.infoMessage = 'Estado de encomienda actualizado a ' + targetStatus;
        this.loadParcels();
      },
      error: (err) => this.handleError(err, 'Transición de encomienda rechazada.'),
    });
  }

  saveActualTimes(): void {
    if (!this.selectedTrip) return;
    this.busy = true;
    this.http.put(`/api/v1/trips/${this.selectedTrip.id}/actual-times`, this.actualTimesForm).subscribe({
      next: () => {
        this.busy = false;
        this.infoMessage = 'Horarios reales registrados para historial y reportes.';
        this.loadTrips();
      },
      error: (err) => this.handleError(err, 'No se pudieron guardar los horarios reales.'),
    });
  }

  loadReportsAndCatalogs(): void {
    this.http.get('/api/v1/reports/operations').subscribe({ next: (r) => (this.report = r) });
    this.http.get<{ items: any[] }>('/api/v1/buses').subscribe({ next: (b) => (this.buses = b.items) });
  }

  badgeClass(status: string): string {
    if (status === 'VIGENTE' || status === 'OK' || status === 'REAL') {
      return 'bg-emerald-100 text-emerald-900';
    }
    if (status === 'DEMO') {
      return 'bg-indigo-50 text-indigo-800';
    }
    if (status === 'POR_VERIFICAR') {
      return 'bg-amber-100 text-amber-900';
    }
    return 'bg-rose-100 text-rose-900';
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
