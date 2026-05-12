package de.hochwasser;

import de.hochwasser.db.DatabaseManager;
import de.hochwasser.ingest.HwndSachsenClient;
import de.hochwasser.model.WaterLevel;

import java.util.List;

public class Main {

    public static void main(String[] args) {
        try (var db = new DatabaseManager(
                "jdbc:postgresql://localhost:5432/hochwasser",
                "postgres", "password")) {

            // Pegel Goerlitz abrufen (Station-ID 1 = Goerlitz in unserer DB)
            var hwndClient = new HwndSachsenClient(1);
            List<WaterLevel> levels = hwndClient.fetchCurrentLevels();

            System.out.printf("✅ %d Messwerte abgerufen%n", levels.size());

            // In DB speichern
            db.insertWaterLevels(levels);
            System.out.println("✅ Daten in PostgreSQL gespeichert");

        } catch (Exception e) {
            System.err.println("❌ Fehler: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
