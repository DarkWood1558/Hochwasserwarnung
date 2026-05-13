-- Hochwasser-Fruehwarnung Neisse Goerlitz - DB-Schema

-- Pegelstationen (deutsch + tschechisch)
CREATE TABLE stations (
    station_id     SERIAL PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    country        VARCHAR(2)   NOT NULL,
    river          VARCHAR(100) NOT NULL,
    latitude       DOUBLE PRECISION,
    longitude      DOUBLE PRECISION,
    km_to_goerlitz DOUBLE PRECISION
);

-- ── Pegelstände (Zeitreihe, partitioniert nach Jahr) ─────────────────────────
CREATE TABLE water_levels (
    station_id  INT              NOT NULL REFERENCES stations(station_id),
    measured_at TIMESTAMPTZ      NOT NULL,
    level_cm    DOUBLE PRECISION NOT NULL,
    source      VARCHAR(20),
    PRIMARY KEY (station_id, measured_at)
) PARTITION BY RANGE (measured_at);

-- Historische Partitionen (für Trainingsdaten ab 2002)
CREATE TABLE water_levels_hist PARTITION OF water_levels
    FOR VALUES FROM ('2000-01-01') TO ('2024-01-01');

-- Aktuelle Partitionen
CREATE TABLE water_levels_2024 PARTITION OF water_levels
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE water_levels_2025 PARTITION OF water_levels
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE water_levels_2026 PARTITION OF water_levels
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');

-- Default-Partition fängt alles ab 2027 auf (kein "no partition found"-Fehler)
CREATE TABLE water_levels_future PARTITION OF water_levels
    DEFAULT;

-- ── Niederschlagsdaten (DWD) ──────────────────────────────────────────────────
CREATE TABLE precipitation (
    station_id  INT              NOT NULL,
    measured_at TIMESTAMPTZ      NOT NULL,
    rainfall_mm DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (station_id, measured_at)
) PARTITION BY RANGE (measured_at);

CREATE TABLE precipitation_hist PARTITION OF precipitation
    FOR VALUES FROM ('2000-01-01') TO ('2024-01-01');
CREATE TABLE precipitation_2024 PARTITION OF precipitation
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE precipitation_2025 PARTITION OF precipitation
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE precipitation_2026 PARTITION OF precipitation
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE precipitation_future PARTITION OF precipitation
    DEFAULT;

-- ── Historische Hochwasserereignisse ─────────────────────────────────────────
CREATE TABLE flood_events (
    event_id      SERIAL PRIMARY KEY,
    start_date    DATE NOT NULL,
    end_date      DATE,
    peak_level_cm DOUBLE PRECISION,
    category      VARCHAR(20),
    description   TEXT
);

-- ── Vorhersage-Tabelle (Streamlit-Dashboard liest hieraus) ───────────────────
CREATE TABLE predictions (
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

-- ── Indizes ───────────────────────────────────────────────────────────────────
CREATE INDEX idx_water_levels_time         ON water_levels (measured_at DESC);
CREATE INDEX idx_water_levels_station_time ON water_levels (station_id, measured_at DESC);
CREATE INDEX idx_predictions_date          ON predictions  (for_date DESC, station_id);

-- ── View: gleitender Mittelwert + Anstiegsrate ────────────────────────────────
CREATE VIEW water_level_analysis AS
SELECT
    station_id,
    measured_at,
    level_cm,
    AVG(level_cm) OVER (
        PARTITION BY station_id
        ORDER BY measured_at
        ROWS BETWEEN 6 PRECEDING AND CURRENT ROW
    ) AS avg_7h,
    level_cm - LAG(level_cm, 1) OVER (
        PARTITION BY station_id ORDER BY measured_at
    ) AS change_per_hour
FROM water_levels;

-- ── Stationsdaten ─────────────────────────────────────────────────────────────
INSERT INTO stations (name, country, river, latitude, longitude, km_to_goerlitz) VALUES
    ('Goerlitz',          'DE', 'Neisse', 51.1528, 14.9872,  0.0),
    ('Zittau',            'DE', 'Neisse', 50.8965, 14.8076, 35.0),
    ('Hradek nad Nisou',  'CZ', 'Nisa',  50.8600, 14.8400, 40.0),
    ('Liberec',           'CZ', 'Nisa',  50.7671, 15.0562, 80.0);
