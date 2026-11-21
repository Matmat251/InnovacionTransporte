import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';

const webRoot = path.resolve(import.meta.dirname, '..');

test('portal aplica diseño Stitch AndesRoute Central con etiquetas y 4 bloques operativos', () => {
  const html = fs.readFileSync(path.join(webRoot, 'src/app/app.component.html'), 'utf8');
  const ts = fs.readFileSync(path.join(webRoot, 'src/app/app.component.ts'), 'utf8');
  assert.match(html, /AndesRoute/);
  assert.match(html, /PE-22/);
  assert.doesNotMatch(html, /\bDemo\b|Simulador|Simular|Simulación|SIMULADA|Demo2026!/);
  assert.match(html, /Información insuficiente/);
  assert.match(html, /Por verificar/);
  assert.match(ts, /America\/Lima/);
  assert.match(html, /1\. Pronóstico por tramo/);
  assert.match(html, /2\. Alertas viales/);
  assert.match(html, /Sincronizar SENAMHI WFS/);
  assert.match(html, /3\. Riesgo experimental/);
  assert.match(html, /Copiloto Groq IA/);
  assert.match(html, /Imprimir comprobante/);
  assert.match(html, /Métricas MLOps/);
  assert.match(html, /Bitácora de Auditoría/);
  assert.match(html, /Exportar CSV/);
  assert.match(html, /4\. Control de contingencias/);
  assert.match(html, /Distribución de Cabina/);
  assert.match(html, /ANDESROUTE CARGO/);
  assert.match(html, /RECIBIDA/);
  assert.match(html, /EN_TRANSPORTE/);
  assert.match(html, /ENTREGADA/);
  assert.match(html, /Idempotency-Key/);
});

test('proxy y nginx reenvían /api/v1 hacia el backend del mismo origen', () => {
  const proxyConf = JSON.parse(fs.readFileSync(path.join(webRoot, 'proxy.conf.json'), 'utf8'));
  assert.ok(proxyConf['/api/v1']);
  const nginxConf = fs.readFileSync(path.join(webRoot, 'nginx.conf'), 'utf8');
  assert.match(nginxConf, /location \/api\/v1\//);
  assert.match(nginxConf, /location = \/healthz/);
});

test('compilación Angular genera dist/web/browser/index.html con tokens Tailwind de Stitch', () => {
  const indexPath = path.join(webRoot, 'dist/web/browser/index.html');
  assert.equal(fs.existsSync(indexPath), true, 'Ejecuta npm run build antes de npm test');
  const html = fs.readFileSync(indexPath, 'utf8');
  assert.match(html, /lang="es"/);
  assert.match(html, /AndesRoute Central/);
  assert.match(html, /tailwind\.config/);
});
