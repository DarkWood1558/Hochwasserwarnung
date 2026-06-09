package de.hochwasser.analysis;

import de.hochwasser.analysis.FloodPredictor.RiskLevel;
import de.hochwasser.model.DailyProfile;
import smile.clustering.CentroidClustering;
import smile.clustering.KMeans;

import java.util.Arrays;

public class SmileKMeansClusterer {

    private CentroidClustering<double[], double[]> model;
    private RiskLevel[] clusterLabels;
    private double[] featureMeans;
    private double[] featureStdDevs;
    private final int k;

    public SmileKMeansClusterer(int k) {
        this.k = k;
    }

    public void fit(DailyProfile[] profiles) {
        double[][] data = new double[profiles.length][DailyProfile.FEATURE_COUNT];
        for (int i = 0; i < profiles.length; i++)
            data[i] = profiles[i].toFeatureVector();

        computeNormParams(data);
        double[][] normalized = applyNorm(data);

        // Smile 4.x: KMeans.fit(data, k, maxIter) — gibt CentroidClustering zurück
        model = KMeans.fit(normalized, k, 200);
        labelClusters();

        System.out.printf("[SmileKMeans] %d Cluster gefunden, Distortion=%.2f%n",
                k, model.distortion());
    }

    public RiskLevel predict(DailyProfile profile) {
        double[] norm = applyNormSingle(profile.toFeatureVector());
        // CentroidClustering.predict(U observation) — Methode, kein Feld
        int cluster = model.predict(norm);
        return clusterLabels[cluster];
    }

    private void labelClusters() {
        // In Smile 4.x: model.centroids() ist eine Methode (Record-Accessor)
        // die double[][] zurückgibt — Index = Cluster-ID
        double[][] centroids = model.centers();
        Integer[] order = new Integer[k];
        for (int i = 0; i < k; i++) order[i] = i;
        // Sortieren nach Feature[0] (max. Pegel) aufsteigend → NORMAL < ERHOHT < GEFAHR
        Arrays.sort(order, (a, b) -> Double.compare(centroids[a][0], centroids[b][0]));

        clusterLabels = new RiskLevel[k];
        RiskLevel[] levels = RiskLevel.values();
        for (int rank = 0; rank < k; rank++)
            clusterLabels[order[rank]] = levels[Math.min(rank, levels.length - 1)];

        for (int c = 0; c < k; c++)
            System.out.printf("[SmileKMeans] Cluster %d → %s (max. Pegel Zentroid: %.2f norm.)%n",
                    c, clusterLabels[c], centroids[c][0]);
    }

    // ── Z-Score Normalisierung ────────────────────────────────────────────────

    private void computeNormParams(double[][] data) {
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

    private double[][] applyNorm(double[][] data) {
        double[][] r = new double[data.length][DailyProfile.FEATURE_COUNT];
        for (int i = 0; i < data.length; i++) r[i] = applyNormSingle(data[i]);
        return r;
    }

    private double[] applyNormSingle(double[] v) {
        double[] r = new double[v.length];
        for (int j = 0; j < v.length; j++)
            r[j] = (v[j] - featureMeans[j]) / featureStdDevs[j];
        return r;
    }
}