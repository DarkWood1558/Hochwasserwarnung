-- Reproduktionsskript für den SQL-Fehler
-- Erwarteter Fehler: aggregate function calls cannot contain window function calls

WITH daily_levels AS (
    SELECT
        (measured_at AT TIME ZONE 'Europe/Berlin')::date   AS day,
        station_id,
        MAX(level_cm)                                      AS max_level_cm,
        AVG(level_cm)                                      AS avg_level_cm,
        MAX(ABS(
            level_cm - LAG(level_cm) OVER (
                PARTITION BY station_id
                ORDER BY measured_at
            )
        ))                                                 AS max_rate_cm_h
    FROM (
        -- Dummy Daten zum Testen
        SELECT '2024-05-13 10:00:00'::timestamp as measured_at, 1 as station_id, 100 as level_cm
        UNION ALL
        SELECT '2024-05-13 11:00:00'::timestamp, 1, 110
    ) sub
    WHERE station_id IN (1, 3)
      AND measured_at >= '2024-01-01'
      AND measured_at <  '2025-01-01'
    GROUP BY day, station_id
)
SELECT * FROM daily_levels;
