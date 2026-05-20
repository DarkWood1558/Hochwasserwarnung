package de.hochwasser.db;

import de.hochwasser.model.WaterLevel;

import java.sql.*;
import java.util.List;

public class DatabaseManager implements AutoCloseable {

    private final Connection connection;

    public DatabaseManager(String url, String user, String password) throws SQLException {
        this.connection = DriverManager.getConnection(url, user, password);
        this.connection.setAutoCommit(false);
    }

    public void insertWaterLevels(List<WaterLevel> levels) throws SQLException {
        String sql = """
            INSERT INTO water_levels (station_id, measured_at, level_cm, source)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (station_id, measured_at) DO UPDATE SET level_cm = EXCLUDED.level_cm
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (WaterLevel wl : levels) {
                ps.setInt(1, wl.stationId());
                ps.setTimestamp(2, Timestamp.from(wl.measuredAt()));
                ps.setDouble(3, wl.levelCm());
                ps.setString(4, wl.source());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        }
    }

    public void insertPrecipitation(List<WaterLevel> rainfall) throws SQLException {
        String sql = """
            INSERT INTO precipitation (station_id, measured_at, rainfall_mm)
            VALUES (?, ?, ?)
            ON CONFLICT (station_id, measured_at) DO UPDATE SET rainfall_mm = EXCLUDED.rainfall_mm
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (WaterLevel wl : rainfall) {
                ps.setInt(1, wl.stationId());
                ps.setTimestamp(2, Timestamp.from(wl.measuredAt()));
                ps.setDouble(3, wl.levelCm()); // rainfall_mm is stored in levelCm in the record
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        }
    }

    public void insertPrediction(int stationId, de.hochwasser.analysis.FloodPredictor.ComprehensiveResult res) throws SQLException {
        String sql = """
            INSERT INTO predictions (
                station_id, for_date, risk_level, 
                p_normal, p_erhoht, p_gefahr, 
                level_6h_cm, level_12h_cm, level_24h_cm,
                travel_hours, is_anomaly, high_confidence
            ) VALUES (?, CURRENT_DATE, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, stationId);
            ps.setString(2, res.ensembleRisk().name());
            ps.setDouble(3, res.nbProbabilities()[0]);
            ps.setDouble(4, res.nbProbabilities()[1]);
            ps.setDouble(5, res.nbProbabilities()[2]);
            
            if (res.levelForecast() != null) {
                ps.setDouble(6, res.levelForecast().level6h());
                ps.setDouble(7, res.levelForecast().level12h());
                ps.setDouble(8, res.levelForecast().level24h());
            } else {
                ps.setNull(6, java.sql.Types.DOUBLE);
                ps.setNull(7, java.sql.Types.DOUBLE);
                ps.setNull(8, java.sql.Types.DOUBLE);
            }
            
            if (res.travelTimeHours() >= 0) {
                ps.setDouble(9, res.travelTimeHours());
            } else {
                ps.setNull(9, java.sql.Types.DOUBLE);
            }
            
            ps.setBoolean(10, res.isAnomaly());
            ps.setBoolean(11, res.highConfidence());
            ps.executeUpdate();
            connection.commit();
        }
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
