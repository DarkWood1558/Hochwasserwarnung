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

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
