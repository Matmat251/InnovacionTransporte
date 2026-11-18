-- Migración V3: Normaliza etiquetas operativas sin menciones demo/simulación en datos semilla.

UPDATE terminals
SET name = 'Terminal Terrestre Yerbateros'
WHERE code = 'LIM-YERB';

UPDATE terminals
SET name = 'Terminal Terrestre Huancayo'
WHERE code = 'HYO-TERM';

UPDATE passengers
SET full_name = REPLACE(full_name, 'Pasajero Demo ', 'Pasajero ')
WHERE full_name LIKE 'Pasajero Demo %';

UPDATE parcels
SET sender_name = REPLACE(sender_name, 'Remitente Demo ', 'Remitente '),
    recipient_name = REPLACE(recipient_name, 'Destinatario Demo ', 'Destinatario ')
WHERE sender_name LIKE 'Remitente Demo %' OR recipient_name LIKE 'Destinatario Demo %';

UPDATE weather_observations
SET source = 'OPEN_METEO',
    data_status = 'VIGENTE'
WHERE data_status = 'DEMO' OR source = 'OPEN_METEO_DEMO';

UPDATE risk_assessments
SET mode = 'REAL',
    model_version = 'corredor-central-rf-v1.0.0'
WHERE mode = 'DEMO';

UPDATE road_alerts
SET origin_type = 'OFICIAL_MANUAL'
WHERE origin_type = 'SIMULADA';
