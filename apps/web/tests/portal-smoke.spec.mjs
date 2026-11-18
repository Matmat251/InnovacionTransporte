import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';

const webRoot = path.resolve(import.meta.dirname, '..');

test('portal incluye etiquetas requeridas en español y los 4 bloques operativos completos', () => {
  const source = fs.readFileSync(path.join(webRoot, 'src/app/app.component.ts'), 'utf8');
  assert.match(source, /Demo/);
  assert.match(source, /Información insuficiente/);
  assert.match(source, /Por verificar/);
  assert.match(source, /America\/Lima/);
  assert.match(source, /1\. Pronóstico por tramo/);
  assert.match(source, /2\. Alertas viales/);
  assert.match(source, /3\. Riesgo experimental/);
  assert.match(source, /4\. Simulador de contingencias/);
  assert.match(source, /Plano de asientos/);
  assert.match(source, /Historial de encomiendas/);
  assert.match(source, /Idempotency-Key/);
  assert.match(source, /RECIBIDA/);
  assert.match(source, /EN_TRANSPORTE/);
  assert.match(source, /ENTREGADA/);
});

test('proxy y nginx reenvían /api/v1 hacia el backend del mismo origen', () => {
  const proxyConf = JSON.parse(fs.readFileSync(path.join(webRoot, 'proxy.conf.json'), 'utf8'));
  assert.ok(proxyConf['/api/v1']);
  const nginxConf = fs.readFileSync(path.join(webRoot, 'nginx.conf'), 'utf8');
  assert.match(nginxConf, /location \/api\/v1\//);
  assert.match(nginxConf, /location = \/healthz/);
});

test('compilación Angular genera dist/web/browser/index.html', () => {
  const indexPath = path.join(webRoot, 'dist/web/browser/index.html');
  assert.equal(fs.existsSync(indexPath), true, 'Ejecuta npm run build antes de npm test');
  const html = fs.readFileSync(indexPath, 'utf8');
  assert.match(html, /lang="es"/);
});
