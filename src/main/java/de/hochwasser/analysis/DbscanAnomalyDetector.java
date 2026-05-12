package de.hochwasser.analysis;

import de.hochwasser.model.DailyProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * DBSCAN-basierte Anomalie-Erkennung für Tagespegel-Profile.
 *
 * ── Warum DBSCAN und nicht K-Means? ─────────────────────────────────────────
 * K-Means zwingt jeden Datenpunkt in einen Cluster. Ein Datenpunkt, der
 * zu keinem bekannten Muster passt (z.B. Schneeschmelze + Starkregen
 * gleichzeitig), wird trotzdem einem Cluster zugeordnet – still und leise.
 *
 * DBSCAN erkennt solche Punkte als NOISE (Cluster-ID = -1) und flaggt sie
 * explizit als Anomalie. Das ist für ein Frühwarnsystem entscheidend:
 * Gerade unbekannte Extremereignisse müssen herausgehoben werden.
 *
 * ── Algorithmus ─────────────────────────────────────────────────────────────
 * Für jeden unbesuchten Punkt p:
 *   1. Bestimme ε-Nachbarschaft N(p) = alle Punkte mit dist(p,q) ≤ ε
 *   2. Kernpunkt: |N(p)| ≥ minPts → starte neuen Cluster, expandiere
 *   3. Randpunkt: |N(p)| < minPts, aber in Nachbarschaft eines Kernpunkts
 *   4. Noise:     |N(p)| < minPts und kein Kernpunkt in der Nähe → Anomalie
 *
 * ── Parameter-Wahl ───────────────────────────────────────────────────────────
 * ε (epsilon):   Radius der Nachbarschaft im normalisierten Feature-Raum.
 *                Faustregel: k-Dist-Plot sortieren, Knick = ε.
 *                Für dieses Projekt: Experimentell ~0.8–1.5.
 *
 * minPts:        Mindest-Nachbarn für Kernpunkt. Faustregel: 2 × Feature-Anzahl.
 *                Für FEATURE_COUNT=5 → minPts=10 ist ein guter Startwert.
 */
public class DbscanAnomalyDetector {

    public static final int NOISE = -1;
    public static final int UNVISITED = Integer.MIN_VALUE;

    private final double epsilon;
    private final int minPts;

    // Trainings-Daten (normalisiert) – werden für Online-Klassifikation behalten
    private double[][] trainData;
    private int[]      trainLabels;
    private double[]   featureMeans;
    private double[]   featureStdDevs;

    private boolean trained = false;

    /**
     * @param epsilon Nachbarschaftsradius im normalisierten Feature-Raum (Empfehlung: 1.0)
     * @param minPts  Mindest-Nachbarn für Kernpunkt (Empfehlung: 10)
     */
    public DbscanAnomalyDetector(double epsilon, int minPts) {
        this.epsilon = epsilon;
        this.minPts  = minPts;
    }

    // ── Training ──────────────────────────────────────────────────────────────

    /**
     * Clustert historische Tagesprofile mit DBSCAN.
     * Lernziel: "normale" Cluster kennen → Anomalien in Zukunft erkennen.
     *
     * @param profiles Historische Tagesprofile (gelabelt oder ungelabelt)
     * @return Cluster-Labels für jeden Trainings-Punkt (-1 = Anomalie)
     */
    public int[] fit(DailyProfile[] profiles) {
        trainData  = toMatrix(profiles);
        computeNormalizationParams(trainData);
        double[][] normalized = applyNormalization(trainData);

        trainLabels = runDbscan(normalized);
        trained = true;

        int noise    = (int) Arrays.stream(trainLabels).filter(l -> l == NOISE).count();
        int clusters = Arrays.stream(trainLabels).filter(l -> l >= 0).max().orElse(-1) + 1;

        System.out.printf("[DBSCAN] %d Tagesprofile → %d Cluster, %d Anomalien (%.1f%%)%n",
            profiles.length, clusters, noise, 100.0 * noise / profiles.length);

        return trainLabels.clone();
    }

    // ── Vorhersage ────────────────────────────────────────────────────────────

    /**
     * Klassifiziert ein neues Tagesprofil:
     *   - Liegt es innerhalb eines bekannten Clusters  → Cluster-ID (≥ 0)
     *   - Liegt es zu weit von allen Trainingspunkten  → NOISE = -1 (Anomalie)
     *
     * Da DBSCAN kein parametrisches Modell lernt, wird ein neuer Punkt als
     * Nicht-Anomalie gewertet, wenn er mindestens einen Trainingspunkt
     * innerhalb von ε hat, der selbst ein Kern- oder Randpunkt ist.
     *
     * @param profile Neues Tagesprofil
     * @return Cluster-ID (≥ 0) oder NOISE (-1)
     */
    public int predict(DailyProfile profile) {
        checkTrained();
        double[] point = normalize(profile.toFeatureVector());

        // Finde alle Trainingspunkte innerhalb ε
        List<Integer> neighbors = new ArrayList<>();
        double[][] normalized = applyNormalization(trainData);
        for (int i = 0; i < normalized.length; i++) {
            if (euclidean(point, normalized[i]) <= epsilon) {
                neighbors.add(i);
            }
        }

        if (neighbors.isEmpty()) return NOISE;

        // Cluster-Label: Mehrheitsvoting der Nachbarn (Noise-Punkte ignorieren)
        int[] votes = new int[Arrays.stream(trainLabels).max().orElse(0) + 1];
        for (int idx : neighbors) {
            if (trainLabels[idx] >= 0) votes[trainLabels[idx]]++;
        }

        int best = -1, bestCount = 0;
        for (int c = 0; c < votes.length; c++) {
            if (votes[c] > bestCount) { bestCount = votes[c]; best = c; }
        }

        return bestCount > 0 ? best : NOISE;
    }

    /**
     * Bequeme Methode: gibt direkt zurück ob ein Profil eine Anomalie ist.
     */
    public boolean isAnomaly(DailyProfile profile) {
        return predict(profile) == NOISE;
    }

    /**
     * Gibt eine Beschreibung zurück warum ein Profil anomal ist.
     * Vergleicht Features des Profils mit Trainingsdaten-Mittelwerten.
     */
    public String describeAnomaly(DailyProfile profile) {
        checkTrained();
        double[] features = profile.toFeatureVector();
        String[] names = {"max. Pegel", "avg. Pegel", "Anstiegsrate", "Niederschlag", "Upstream"};
        StringBuilder sb = new StringBuilder("Anomalie-Merkmale:\n");

        for (int j = 0; j < features.length; j++) {
            double zScore = Math.abs((features[j] - featureMeans[j]) / featureStdDevs[j]);
            if (zScore > 2.0) {
                sb.append(String.format("  %-15s: %.1f cm (z=%.1f σ)\n",
                    names[j], features[j], zScore));
            }
        }
        return sb.isEmpty() ? "Keine auffälligen Features (Kombinationseffekt)" : sb.toString();
    }

    // ── DBSCAN Kern-Algorithmus ────────────────────────────────────────────────

    private int[] runDbscan(double[][] data) {
        int n = data.length;
        int[] labels = new int[n];
        Arrays.fill(labels, UNVISITED);
        int clusterId = 0;

        for (int i = 0; i < n; i++) {
            if (labels[i] != UNVISITED) continue; // bereits verarbeitet

            List<Integer> neighbors = regionQuery(data, i);

            if (neighbors.size() < minPts) {
                labels[i] = NOISE; // vorläufig Noise – kann noch Randpunkt werden
            } else {
                expandCluster(data, labels, i, neighbors, clusterId);
                clusterId++;
            }
        }
        return labels;
    }

    private void expandCluster(double[][] data, int[] labels,
                                int pointIdx, List<Integer> neighbors, int clusterId) {
        labels[pointIdx] = clusterId;
        int i = 0;
        while (i < neighbors.size()) {
            int qIdx = neighbors.get(i);
            if (labels[qIdx] == NOISE) labels[qIdx] = clusterId;   // Randpunkt
            if (labels[qIdx] == UNVISITED) {
                labels[qIdx] = clusterId;
                List<Integer> qNeighbors = regionQuery(data, qIdx);
                if (qNeighbors.size() >= minPts) {
                    neighbors.addAll(qNeighbors); // Kernpunkt: Nachbarn hinzufügen
                }
            }
            i++;
        }
    }

    private List<Integer> regionQuery(double[][] data, int idx) {
        List<Integer> result = new ArrayList<>();
        for (int j = 0; j < data.length; j++) {
            if (euclidean(data[idx], data[j]) <= epsilon) result.add(j);
        }
        return result;
    }

    // ── Normalisierung ────────────────────────────────────────────────────────

    private void computeNormalizationParams(double[][] data) {
        int f = DailyProfile.FEATURE_COUNT;
        featureMeans   = new double[f];
        featureStdDevs = new double[f];

        for (double[] row : data)
            for (int j = 0; j < f; j++) featureMeans[j] += row[j];
        for (int j = 0; j < f; j++) featureMeans[j] /= data.length;

        for (double[] row : data)
            for (int j = 0; j < f; j++)
                featureStdDevs[j] += Math.pow(row[j] - featureMeans[j], 2);
        for (int j = 0; j < f; j++) {
            featureStdDevs[j] = Math.sqrt(featureStdDevs[j] / data.length);
            if (featureStdDevs[j] < 1e-9) featureStdDevs[j] = 1;
        }
    }

    private double[][] applyNormalization(double[][] data) {
        double[][] result = new double[data.length][DailyProfile.FEATURE_COUNT];
        for (int i = 0; i < data.length; i++)
            result[i] = normalize(data[i]);
        return result;
    }

    private double[] normalize(double[] features) {
        double[] norm = new double[features.length];
        for (int j = 0; j < features.length; j++)
            norm[j] = (features[j] - featureMeans[j]) / featureStdDevs[j];
        return norm;
    }

    // ── Hilfsmethoden ─────────────────────────────────────────────────────────

    private static double euclidean(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += Math.pow(a[i] - b[i], 2);
        return Math.sqrt(sum);
    }

    private static double[][] toMatrix(DailyProfile[] profiles) {
        double[][] m = new double[profiles.length][DailyProfile.FEATURE_COUNT];
        for (int i = 0; i < profiles.length; i++) m[i] = profiles[i].toFeatureVector();
        return m;
    }

    private void checkTrained() {
        if (!trained) throw new IllegalStateException("DBSCAN nicht trainiert.");
    }
}
