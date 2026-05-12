package de.hochwasser;

import de.hochwasser.db.DatabaseManager;
import de.hochwasser.ingest.ChmiClient;
import de.hochwasser.ingest.DwdClient;
import de.hochwasser.ingest.HwndSachsenClient;
import de.hochwasser.model.WaterLevel;

import java.util.List;

public class Main {

    public static void main(String[] args) {
        try (var db = new DatabaseManager(
                "jdbc:postgresql://localhost:5432/hochwasser",
                "postgres", "password")) {

            // 1. HWND Sachsen (Pegel Görlitz, ID 1)
            try {
                var hwndClient = new HwndSachsenClient(1);
                List<WaterLevel> levels = hwndClient.fetchCurrentLevels();
                System.out.printf(" [HWND] %d Messwerte abgerufen%n", levels.size());
                db.insertWaterLevels(levels);
            } catch (Exception e) {
                System.err.println(" [HWND] ⚠️ Fehler beim Abrufen der Sachsen-Daten: " + e.getMessage());
            }

            // 2. CHMI Tschechien (Pegel Hrádek nad Nisou, ID 3)
            try {
                var chmiClient = new ChmiClient(3);
                List<WaterLevel> chmiLevels = chmiClient.fetchCurrentLevels();
                System.out.printf(" [CHMI] %d Messwerte abgerufen%n", chmiLevels.size());
                db.insertWaterLevels(chmiLevels);
            } catch (Exception e) {
                System.err.println(" [CHMI] ⚠️ Fehler beim Abrufen der CHMI-Daten: " + e.getMessage());
                System.err.println("        Hinweis: Die CHMI-Webseite könnte ihre URL geändert haben.");
            }

            // 3. DWD Niederschlag (Station Görlitz, ID 1)
            try {
                var dwdClient = new DwdClient(1684);
                List<WaterLevel> rainfall = dwdClient.fetchPrecipitation(1);
                System.out.printf(" [DWD] %d Niederschlagswerte abgerufen%n", rainfall.size());
                db.insertPrecipitation(rainfall);
            } catch (Exception e) {
                System.err.println(" [DWD] ⚠️ Fehler beim Abrufen der DWD-Daten: " + e.getMessage());
            }

            System.out.println(" Ingest-Prozess abgeschlossen.");

        } catch (Exception e) {
            System.err.println("❌ Fehler: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
