package de.hochwasser.model;

import de.hochwasser.analysis.FloodPredictor.RiskLevel;

import java.time.LocalDate;

/**
 * Aggregiertes Tagesprofil einer Pegelstation.
 *
 * Dieses Objekt ist der Feature-Vektor für das Machine-Learning-Modell.
 * Ein DailyProfile fasst alle Messwerte eines Tages zu 5 Kennzahlen zusammen,
 * die als Input für K-Means-Clustering und Naive-Bayes-Klassifikation dienen.
 *
 * Features:
 *   [0] maxLevelCm          – Tagesmaximum des Pegels in cm
 *   [1] avgLevelCm          – Tagesdurchschnitt des Pegels in cm
 *   [2] maxRateOfChangeCmH  – Maximale stündliche Anstiegsrate in cm/h
 *   [3] totalPrecipMm       – Gesamtniederschlag des Tages in mm
 *   [4] upstreamMaxLevelCm  – Tagesmaximum des Upstream-Pegels (Tschechien) in cm
 */
public record DailyProfile(
    LocalDate date,
    int stationId,
    double maxLevelCm,
    double avgLevelCm,
    double maxRateOfChangeCmH,
    double totalPrecipMm,
    double upstreamMaxLevelCm,
    RiskLevel label           // null = unbekannt (für ungelabelte Daten)
) {

    public static final int FEATURE_COUNT = 5;

    /**
     * Gibt den Feature-Vektor als double-Array zurück.
     * Reihenfolge muss mit FEATURE_COUNT und der Dokumentation übereinstimmen.
     */
    public double[] toFeatureVector() {
        return new double[]{
            maxLevelCm,
            avgLevelCm,
            maxRateOfChangeCmH,
            totalPrecipMm,
            upstreamMaxLevelCm
        };
    }

    /** Hilfsmethode: Profil ohne Label (für Vorhersage auf neuen Daten). */
    public static DailyProfile unlabeled(LocalDate date, int stationId,
                                         double maxLevelCm, double avgLevelCm,
                                         double maxRateOfChangeCmH, double totalPrecipMm,
                                         double upstreamMaxLevelCm) {
        return new DailyProfile(date, stationId, maxLevelCm, avgLevelCm,
            maxRateOfChangeCmH, totalPrecipMm, upstreamMaxLevelCm, null);
    }
}
