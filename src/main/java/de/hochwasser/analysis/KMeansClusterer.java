package de.hochwasser.analysis;

import de.hochwasser.analysis.FloodPredictor.RiskLevel;
import de.hochwasser.model.DailyProfile;

import java.util.Arrays;
import java.util.Random;

/**
 * K-Means-Clustering für Tagespegel-Profile.
 *
 * Zweck im Projekt (Data Mining 1):
 * Anstatt Risikostufen manuell mit Schwellwerten zu definieren, lernt K-Means
 * automatisch aus historischen Daten, welche Feature-Kombinationen typisch für
 * normale Tage, erhöhte Pegel oder Gefahrensituationen sind.
 *
 * Algorithmus:
 *   1. Initialisierung: k Zentroiden per K-Means++ (bessere Startpositionen)
 *   2. Zuweisung:       Jeden Datenpunkt dem nächsten Zentroid zuordnen
 *   3. Update:          Zentroiden als Mittelwert der zugewiesenen Punkte neu berechnen
 *   4. Wiederholen bis Konvergenz (keine Änderungen) oder maxIterations erreicht
 *
 * Nach dem Training werden die Cluster automatisch nach dem max. Pegel-Feature
 * sortiert und als NORMAL, ERHOHT, GEFAHR gelabelt.
 */
public class KMeansClusterer {

    private final int k;
    private final int maxIterations;
    private final Random random;

    private double[][] centroids;    // [k][FEATURE_COUNT] – aktuelle Zentroiden
    private double[] featureMeans;   // für Z-Score-Normalisierung
    private double[] featureStdDevs;
    private RiskLevel[] clusterLabels; // Welches Cluster entspricht welchem Risikolevel?

    /**
     * @param k             Anzahl Cluster (typisch 3: NORMAL, ERHOHT, GEFAHR)
     * @param maxIterations Maximale Iterationsanzahl (Abbruchbedingung)
     * @param seed          Zufalls-Seed für Reproduzierbarkeit
     */
    public KMeansClusterer(int k, int maxIterations, long seed) {
        this.k = k;
        this.maxIterations = maxIterations;
        this.random = new Random(seed);
    }

    // -------------------------------------------------------------------------
    // Training
    // -------------------------------------------------------------------------

    /**
     * Trainiert das Modell auf den übergebenen Tagesprofilen.
     *
     * @param profiles Historische Tagesprofile (mindestens k Einträge)
     */
    public void fit(DailyProfile[] profiles) {
        double[][] data = toMatrix(profiles);
        normalizeInPlace(data); // Z-Score über alle Features

        centroids = initializePlusPlus(data);

        int[] assignments = new int[data.length];

        for (int iter = 0; iter < maxIterations; iter++) {
            int[] newAssignments = assignClusters(data);

            if (Arrays.equals(assignments, newAssignments)) {
                System.out.printf("[K-Means] Konvergenz nach %d Iterationen%n", iter + 1);
                break;
            }
            assignments = newAssignments;
            updateCentroids(data, assignments);
        }

        labelClusters();
    }

    /**
     * Gibt das Risikolevel für ein neues Tagesprofil zurück.
     *
     * @param profile Tagesprofil (ungelabelt)
     * @return Vorhergesagte Risikostufe
     */
    public RiskLevel predict(DailyProfile profile) {
        double[] features = normalize(profile.toFeatureVector());
        int cluster = nearestCentroid(features);
        return clusterLabels[cluster];
    }

    /**
     * Gibt alle Cluster-Zentroiden zurück (für Analyse und Visualisierung).
     * Werte sind im normalisierten Raum.
     */
    public double[][] getCentroids() {
        return centroids;
    }

    // -------------------------------------------------------------------------
    // K-Means++ Initialisierung
    // -------------------------------------------------------------------------

    /**
     * K-Means++ wählt Startpositionen, die möglichst weit auseinanderliegen.
     * Das verhindert das "schlechte Startpositionen"-Problem des klassischen K-Means.
     */
    private double[][] initializePlusPlus(double[][] data) {
        double[][] cents = new double[k][];

        // Erster Zentroid: zufällig
        cents[0] = data[random.nextInt(data.length)].clone();

        for (int c = 1; c < k; c++) {
            // Für jeden Punkt: Distanz zum nächsten bereits gewählten Zentroid
            double[] distances = new double[data.length];
            double totalDist = 0;
            for (int i = 0; i < data.length; i++) {
                distances[i] = minDistanceToCentroids(data[i], cents, c);
                totalDist += distances[i];
            }

            // Nächsten Zentroid proportional zu Distanz² wählen (Roulette-Wheel)
            double threshold = random.nextDouble() * totalDist;
            double cumulative = 0;
            for (int i = 0; i < data.length; i++) {
                cumulative += distances[i];
                if (cumulative >= threshold) {
                    cents[c] = data[i].clone();
                    break;
                }
            }
            if (cents[c] == null) cents[c] = data[data.length - 1].clone();
        }
        return cents;
    }

    // -------------------------------------------------------------------------
    // Hilfsmethoden
    // -------------------------------------------------------------------------

    private int[] assignClusters(double[][] data) {
        int[] assignments = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            assignments[i] = nearestCentroid(data[i]);
        }
        return assignments;
    }

    private int nearestCentroid(double[] point) {
        int nearest = 0;
        double minDist = euclideanDistance(point, centroids[0]);
        for (int c = 1; c < k; c++) {
            double d = euclideanDistance(point, centroids[c]);
            if (d < minDist) {
                minDist = d;
                nearest = c;
            }
        }
        return nearest;
    }

    private void updateCentroids(double[][] data, int[] assignments) {
        double[][] newCentroids = new double[k][DailyProfile.FEATURE_COUNT];
        int[] counts = new int[k];

        for (int i = 0; i < data.length; i++) {
            int c = assignments[i];
            counts[c]++;
            for (int f = 0; f < DailyProfile.FEATURE_COUNT; f++) {
                newCentroids[c][f] += data[i][f];
            }
        }
        for (int c = 0; c < k; c++) {
            if (counts[c] > 0) {
                for (int f = 0; f < DailyProfile.FEATURE_COUNT; f++) {
                    newCentroids[c][f] /= counts[c];
                }
                centroids[c] = newCentroids[c];
            }
        }
    }

    /**
     * Beschriftet Cluster nach dem ersten Feature (maxLevelCm, normalisiert).
     * Cluster mit höchstem Zentroid-Wert → GEFAHR, mittlerer → ERHOHT, niedrigster → NORMAL.
     */
    private void labelClusters() {
        // Cluster-Indizes nach Feature[0] (max. Pegel) aufsteigend sortieren
        Integer[] order = new Integer[k];
        for (int i = 0; i < k; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(centroids[a][0], centroids[b][0]));

        clusterLabels = new RiskLevel[k];
        RiskLevel[] levels = RiskLevel.values(); // NORMAL=0, ERHOHT=1, GEFAHR=2

        for (int rank = 0; rank < k; rank++) {
            int levelIdx = Math.min(rank, levels.length - 1);
            clusterLabels[order[rank]] = levels[levelIdx];
        }

        System.out.println("[K-Means] Cluster-Labels:");
        for (int c = 0; c < k; c++) {
            System.out.printf("  Cluster %d → %s (Zentroid max. Pegel: %.2f norm.)%n",
                c, clusterLabels[c], centroids[c][0]);
        }
    }

    // -------------------------------------------------------------------------
    // Normalisierung (Z-Score)
    // -------------------------------------------------------------------------

    /** Berechnet Mittelwert und Standardabweichung und normalisiert data in-place. */
    private void normalizeInPlace(double[][] data) {
        int n = data.length;
        int f = DailyProfile.FEATURE_COUNT;
        featureMeans = new double[f];
        featureStdDevs = new double[f];

        // Mittelwert
        for (double[] row : data)
            for (int j = 0; j < f; j++)
                featureMeans[j] += row[j];
        for (int j = 0; j < f; j++) featureMeans[j] /= n;

        // Standardabweichung
        for (double[] row : data)
            for (int j = 0; j < f; j++)
                featureStdDevs[j] += Math.pow(row[j] - featureMeans[j], 2);
        for (int j = 0; j < f; j++) {
            featureStdDevs[j] = Math.sqrt(featureStdDevs[j] / n);
            if (featureStdDevs[j] == 0) featureStdDevs[j] = 1; // Division durch 0 vermeiden
        }

        // Normalisieren
        for (double[] row : data)
            for (int j = 0; j < f; j++)
                row[j] = (row[j] - featureMeans[j]) / featureStdDevs[j];
    }

    /** Normalisiert einen einzelnen Feature-Vektor mit den gespeicherten Parametern. */
    private double[] normalize(double[] features) {
        double[] norm = new double[features.length];
        for (int j = 0; j < features.length; j++)
            norm[j] = (features[j] - featureMeans[j]) / featureStdDevs[j];
        return norm;
    }

    // -------------------------------------------------------------------------
    // Distanzen
    // -------------------------------------------------------------------------

    private static double euclideanDistance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += Math.pow(a[i] - b[i], 2);
        return Math.sqrt(sum);
    }

    private static double minDistanceToCentroids(double[] point, double[][] cents, int count) {
        double min = Double.MAX_VALUE;
        for (int c = 0; c < count; c++)
            min = Math.min(min, euclideanDistance(point, cents[c]));
        return min;
    }

    // -------------------------------------------------------------------------
    // Konvertierung
    // -------------------------------------------------------------------------

    private static double[][] toMatrix(DailyProfile[] profiles) {
        double[][] matrix = new double[profiles.length][DailyProfile.FEATURE_COUNT];
        for (int i = 0; i < profiles.length; i++)
            matrix[i] = profiles[i].toFeatureVector();
        return matrix;
    }
}
