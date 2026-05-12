package de.hochwasser;

import de.hochwasser.analysis.FloodPredictor;
import de.hochwasser.analysis.WaterLevelRegressor.RegressionSample;
import de.hochwasser.db.DailyProfileLoader;
import de.hochwasser.db.DatabaseManager;
import de.hochwasser.model.DailyProfile;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class PredictorTrainingRunner {

    public static void main(String[] args) {
        String url = "jdbc:postgresql://localhost:5432/hochwasser";
        String user = "postgres";
        String password = "password";

        try (var db = new DatabaseManager(url, user, password)) {
            // 1. Seed Historical Data
            seedHistoricalData(db);

            // 2. Load Profiles for Training
            var loader = new DailyProfileLoader(db.getConnection());
            System.out.println("Lade historische Daten für das Training...");
            
            // Wir laden Daten von 2002 bis heute für Görlitz (ID 1) mit Hrádek (ID 3)
            DailyProfile[] profiles = loader.loadForTraining(1, 3, 
                LocalDate.of(2002, 1, 1), LocalDate.now());
            
            if (profiles.length == 0) {
                System.err.println("Keine Trainingsdaten gefunden! Stellen Sie sicher, dass water_levels Daten vorhanden sind.");
                return;
            }
            System.out.printf("%d Tagesprofile geladen.%n", profiles.length);

            // 3. Train FloodPredictor
            var predictor = new FloodPredictor();
            System.out.println("Starte Training der Profil-Modelle (Bayesian Network, Naive Bayes, K-Means)...");
            var result = predictor.trainProfileModels(profiles);
            
            System.out.println("\n--- Kreuzvalidierungsergebnisse ---");
            System.out.println(result.toString());

            // 4. Train Travel Time & Regressor (optional mit Mock-Daten falls Zeitreihen fehlen)
            // In einer echten Umgebung würden hier die rohen Zeitreihen geladen werden.
            System.out.println("\nTraining abgeschlossen.");

        } catch (Exception e) {
            System.err.println("❌ Fehler während des Trainings: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void seedHistoricalData(DatabaseManager db) {
        String sqlFile = "sql/seed_historical_data.sql";
        try (Statement stmt = db.getConnection().createStatement()) {
            System.out.println("Führe Seed-Skript aus: " + sqlFile);
            String content = new String(Files.readAllBytes(Paths.get(sqlFile)));
            stmt.execute(content);
            db.getConnection().commit();
            System.out.println("✅ Historische Ereignisse erfolgreich in die DB geladen.");
        } catch (Exception e) {
            System.err.println("⚠️ Warnung beim Seeding: " + e.getMessage());
        }
    }
}
