package de.hochwasser.model;

import de.hochwasser.analysis.FloodPredictor.RiskLevel;
import de.hochwasser.model.DailyProfile;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Lädt und assembliert DailyProfile-Objekte aus der PostgreSQL-Datenbank.
 *
 * Verbindet drei Tabellen zu einem Feature-Vektor pro Tag:
 *   water_levels   → max/avg Pegel, max Anstiegsrate
 *   precipitation  → Gesamtniederschlag
 *   flood_events   → Label (NORMAL / ERHOHT / GEFAHR)
 *
 * Upstream-Pegel (tschechische Stationen) werden separat aggregiert
 * und als fünftes Feature eingebunden.
 *
 * ADBC2-Relevanz:
 *   logQueryPlan() führt EXPLAIN (ANALYZE, BUFFERS) aus – zeigt wie
 *   PostgreSQL die partitionierten Tabellen und Indizes nutzt.
 *   Perfekt für die Präsentation der Query-Optimierung.
 */
public class DailyProfileLoader {

    private final Connection connection;

    public DailyProfileLoader(Connection connection) {
        this.connection = connection;
    }

    // -------------------------------------------------------------------------
    // Öffentliche Lade-Methoden
    // -------------------------------------------------------------------------

    /**
     * Lädt gelabelte Tagesprofile für das Modell-Training.
     *
     * Tage, die in flood_events als Ereignis eingetragen sind, erhalten
     * das entsprechende Label. Alle anderen Tage werden als NORMAL gewertet.
     *
     * @param stationId         ID der Hauptstation (Görlitz = 1)
     * @param upstreamStationId ID der Upstream-Station (Hradek = 3)
     * @param from              Startdatum (inklusiv)
     * @param to                Enddatum (exklusiv)
     * @return Array von gelabelten DailyProfile-Objekten
     */
    public DailyProfile[] loadForTraining(int stationId, int upstreamStationId,
                                          LocalDate from, LocalDate to) throws SQLException {
        List<DailyProfile> profiles = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(MAIN_QUERY)) {
            setQueryParameters(ps, stationId, upstreamStationId, from, to);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    profiles.add(mapRow(rs, stationId, true));
                }
            }
        }

        System.out.printf("[DailyProfileLoader] %d Tagesprofile geladen (%s – %s)%n",
                profiles.size(), from, to);
        printLabelDistribution(profiles);

        return profiles.toArray(DailyProfile[]::new);
    }

    /**
     * Lädt das Tagesprofil für heute (ungelabelt, für Live-Vorhersage).
     *
     * @param stationId         ID der Hauptstation
     * @param upstreamStationId ID der Upstream-Station
     * @return Heutiges DailyProfile (label = null)
     */
    public DailyProfile loadToday(int stationId, int upstreamStationId) throws SQLException {
        return loadForDate(stationId, upstreamStationId, LocalDate.now());
    }

    /**
     * Lädt das Tagesprofil für ein bestimmtes Datum (ungelabelt).
     * Nützlich für Backtesting und manuelle Überprüfung.
     */
    public DailyProfile loadForDate(int stationId, int upstreamStationId,
                                    LocalDate date) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(MAIN_QUERY)) {
            setQueryParameters(ps, stationId, upstreamStationId, date, date.plusDays(1));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs, stationId, false);
                }
            }
        }
        throw new SQLException("Keine Daten für Datum: " + date +
                " (Station " + stationId + ")");
    }

    // -------------------------------------------------------------------------
    // ADBC2: Query-Plan-Analyse
    // -------------------------------------------------------------------------

    /**
     * Führt EXPLAIN (ANALYZE, BUFFERS) auf der Haupt-Query aus und gibt
     * den Ausführungsplan auf der Konsole aus.
     *
     * Relevant für das ADBC2-Modul:
     *   - Zeigt welche Partitionen (water_levels_2024 etc.) gescannt werden
     *   - Zeigt ob Index idx_water_levels_station_time genutzt wird
     *   - Zeigt Kosten und tatsächliche Laufzeit jedes Plan-Knotens
     *   - Zeigt Buffer-Hits vs. Disk-Reads (nach BUFFERS)
     *
     * @param stationId         Stations-ID für die Analyse
     * @param upstreamStationId Upstream-Stations-ID
     * @param from              Zeitraum-Start
     * @param to                Zeitraum-Ende
     */
    public void logQueryPlan(int stationId, int upstreamStationId,
                             LocalDate from, LocalDate to) throws SQLException {
        String explainSql = "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + MAIN_QUERY;

        System.out.println("\n=== EXPLAIN ANALYZE: Trainings-Query ===");
        try (PreparedStatement ps = connection.prepareStatement(explainSql)) {
            setQueryParameters(ps, stationId, upstreamStationId, from, to);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    System.out.println(rs.getString(1));
                }
            }
        }
        System.out.println("========================================\n");
    }

    // -------------------------------------------------------------------------
    // SQL-Query
    // -------------------------------------------------------------------------

    /**
     * Aggregiert Tagesprofile aus water_levels, precipitation und flood_events.
     *
     * Aufbau:
     *   CTE daily_levels – aggregiert Pegel pro Tag für Haupt- UND Upstream-Station
     *   CTE daily_precip – summiert Niederschlag pro Tag
     *   Haupt-SELECT    – joined alles zusammen, hängt flood_events-Label an
     *
     * Parameter (8 Stück):
     *   1  upstreamStationId  (IN-Klausel daily_levels)
     *   2  stationId          (IN-Klausel daily_levels)
     *   3  from               (daily_levels measured_at >=)
     *   4  to                 (daily_levels measured_at <)
     *   5  from               (daily_precip measured_at >=)
     *   6  to                 (daily_precip measured_at <)
     *   7  upstreamStationId  (JOIN daily_levels up ON ... station_id = ?)
     *   8  stationId          (WHERE main.station_id = ?)
     */
    private static final String MAIN_QUERY = """
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
            FROM water_levels
            WHERE station_id IN (?, ?)
              AND measured_at >= ?
              AND measured_at <  ?
            GROUP BY day, station_id
        ),
        daily_precip AS (
            SELECT
                (measured_at AT TIME ZONE 'Europe/Berlin')::date   AS day,
                SUM(rainfall_mm)                                   AS total_precip_mm
            FROM precipitation
            WHERE measured_at >= ?
              AND measured_at <  ?
            GROUP BY day
        )
        SELECT
            main.day,
            main.max_level_cm,
            main.avg_level_cm,
            COALESCE(main.max_rate_cm_h, 0)                        AS max_rate_cm_h,
            COALESCE(dp.total_precip_mm,  0)                       AS total_precip_mm,
            COALESCE(up.max_level_cm,     0)                       AS upstream_max_cm,
            fe.category                                            AS label
        FROM daily_levels main
        LEFT JOIN daily_levels up
            ON  up.day        = main.day
            AND up.station_id = ?
        LEFT JOIN daily_precip dp
            ON  dp.day = main.day
        LEFT JOIN flood_events fe
            ON  main.day BETWEEN fe.start_date
                             AND COALESCE(fe.end_date, fe.start_date)
        WHERE main.station_id = ?
        ORDER BY main.day
        """;

    // -------------------------------------------------------------------------
    // Parameter-Binding (zentralisiert, damit alle Methoden identisch binden)
    // -------------------------------------------------------------------------

    /**
     * Setzt alle 8 Query-Parameter auf dem PreparedStatement.
     * Zentral damit loadForTraining, loadForDate und logQueryPlan
     * niemals auseinanderlaufen.
     */
    private static void setQueryParameters(PreparedStatement ps,
                                           int stationId, int upstreamStationId,
                                           LocalDate from, LocalDate to) throws SQLException {
        ps.setInt( 1, upstreamStationId);    // IN-Klausel daily_levels
        ps.setInt( 2, stationId);
        ps.setDate(3, Date.valueOf(from));    // Zeitraum daily_levels
        ps.setDate(4, Date.valueOf(to));
        ps.setDate(5, Date.valueOf(from));    // Zeitraum daily_precip
        ps.setDate(6, Date.valueOf(to));
        ps.setInt( 7, upstreamStationId);    // JOIN upstream
        ps.setInt( 8, stationId);            // WHERE main
    }

    // -------------------------------------------------------------------------
    // ResultSet → DailyProfile
    // -------------------------------------------------------------------------

    /**
     * Mappt eine Zeile des ResultSet auf ein DailyProfile.
     *
     * @param labeled true  → Label aus flood_events übernehmen (Training)
     *                false → label = null (Live-Vorhersage)
     */
    private static DailyProfile mapRow(ResultSet rs, int stationId,
                                       boolean labeled) throws SQLException {
        LocalDate date       = rs.getDate("day").toLocalDate();
        double maxLevelCm    = rs.getDouble("max_level_cm");
        double avgLevelCm    = rs.getDouble("avg_level_cm");
        double maxRateCmH    = rs.getDouble("max_rate_cm_h");
        double totalPrecipMm = rs.getDouble("total_precip_mm");
        double upstreamMaxCm = rs.getDouble("upstream_max_cm");
        String categoryStr   = rs.getString("label");

        RiskLevel label = labeled ? parseLabel(categoryStr) : null;

        return new DailyProfile(
                date, stationId,
                maxLevelCm, avgLevelCm, maxRateCmH,
                totalPrecipMm, upstreamMaxCm,
                label
        );
    }

    /**
     * Übersetzt den category-String aus flood_events in ein RiskLevel.
     * Unbekannte oder fehlende Werte → NORMAL (sicherer Default).
     */
    private static RiskLevel parseLabel(String category) {
        if (category == null) return RiskLevel.NORMAL;
        return switch (category.toLowerCase().trim()) {
            case "erhoht", "erhöht"    -> RiskLevel.ERHOHT;
            case "kritisch", "gefahr"  -> RiskLevel.GEFAHR;
            default                    -> RiskLevel.NORMAL;
        };
    }

    // -------------------------------------------------------------------------
    // Diagnose
    // -------------------------------------------------------------------------

    private static void printLabelDistribution(List<DailyProfile> profiles) {
        long normal = profiles.stream().filter(p -> p.label() == RiskLevel.NORMAL).count();
        long erhoht = profiles.stream().filter(p -> p.label() == RiskLevel.ERHOHT).count();
        long gefahr = profiles.stream().filter(p -> p.label() == RiskLevel.GEFAHR).count();
        System.out.printf("  Label-Verteilung: NORMAL=%d  ERHOHT=%d  GEFAHR=%d%n",
                normal, erhoht, gefahr);
    }
}