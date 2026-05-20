-- ============================================================
-- Historische Hochwasserereignisse – Neiße / Görlitz
-- Quelle: BfG Gewässerkundliches Jahrbuch, HWND Sachsen
-- ============================================================

-- Sicherstellen, dass die Tabelle existiert (falls schema.sql nicht neu eingelesen wurde)
CREATE TABLE IF NOT EXISTS predictions (
    prediction_id   SERIAL PRIMARY KEY,
    station_id      INT REFERENCES stations(station_id),
    for_date        DATE        NOT NULL,
    predicted_at    TIMESTAMPTZ DEFAULT NOW(),
    risk_level      VARCHAR(20),
    p_normal        DOUBLE PRECISION,
    p_erhoht        DOUBLE PRECISION,
    p_gefahr        DOUBLE PRECISION,
    level_6h_cm     DOUBLE PRECISION,
    level_12h_cm    DOUBLE PRECISION,
    level_24h_cm    DOUBLE PRECISION,
    travel_hours    DOUBLE PRECISION,
    is_anomaly      BOOLEAN DEFAULT FALSE,
    dbscan_cluster  INT,
    high_confidence BOOLEAN DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_predictions_date ON predictions (for_date DESC, station_id);

ALTER TABLE predictions
    ADD COLUMN IF NOT EXISTS dbscan_cluster INT;

-- Daten neu laden (idempotent)
DELETE FROM flood_events;

INSERT INTO flood_events (start_date, end_date, peak_level_cm, category, description) VALUES
    ('2024-09-13', '2024-09-18', 471, 'kritisch',
     'Hochwasser durch Tief Boris – zweitgrößtes Ereignis der Messgeschichte'),
    ('2013-06-04', '2013-06-10', 637, 'kritisch',
     'Pfingsthochwasser 2013 – höchster gemessener Pegel in Görlitz'),
    ('2013-01-19', '2013-01-23', 234, 'erhoht',
     'Winterhochwasser durch Schneeschmelze'),
    ('2010-08-07', '2010-08-13', 547, 'kritisch',
     'Augusthochwasser 2010 – drittgrößtes Ereignis'),
    ('2010-05-16', '2010-05-21', 403, 'kritisch',
     'Frühjahrshochwasser Mai 2010'),
    ('2006-04-04', '2006-04-08', 347, 'kritisch',
     'Frühjahrsereignis durch Schneeschmelze und Niederschlag'),
    ('2006-03-28', '2006-03-30', 218, 'erhoht',
     'Kleine Schneeschmelze-Welle'),
    ('2005-08-25', '2005-08-28', 289, 'erhoht',
     'Augustniederschläge Riesengebirge'),
    ('2002-08-12', '2002-08-17', 621, 'kritisch',
     'Jahrhunderthochwasser August 2002'),
    ('1981-07-25', '1981-07-28', 598, 'kritisch',
     'Sommerhochwasser 1981 – historisches Vergleichsereignis');
