package de.hochwasser.analysis;

import de.hochwasser.analysis.FloodPredictor.RiskLevel;
import de.hochwasser.model.DailyProfile;
import smile.classification.NaiveBayes;
import smile.stat.distribution.Distribution;
import smile.stat.distribution.GaussianDistribution;

import java.util.Arrays;
import java.util.stream.IntStream;

public class SmileNaiveBayesPredictor {

    private NaiveBayes model;

    public void train(DailyProfile[] profiles) {
        RiskLevel[] levels   = RiskLevel.values();
        int numClasses       = levels.length;
        int numFeatures      = DailyProfile.FEATURE_COUNT;
        int n                = profiles.length;

        // ── Prior ──────────────────────────────────────────────────────────
        // Laplace-geglättet: (count + 1) / (n + numClasses)
        int[] counts = new int[numClasses];
        for (DailyProfile p : profiles)
            if (p.label() != null) counts[p.label().ordinal()]++;

        double[] priori = new double[numClasses];
        for (int c = 0; c < numClasses; c++)
            priori[c] = (counts[c] + 1.0) / (n + numClasses);

        // Summe normalisieren (Laplace kann minimal von 1.0 abweichen)
        double sum = Arrays.stream(priori).sum();
        for (int c = 0; c < numClasses; c++) priori[c] /= sum;

        // ── Bedingte Verteilungen P(feature_j | class_c) ──────────────────
        // condprob[c][j] = GaussianDistribution(μ, σ²) aus Trainingsdaten
        Distribution[][] condprob = new Distribution[numClasses][numFeatures];

        for (int c = 0; c < numClasses; c++) {
            final int cls = c;
            double[][] subset = Arrays.stream(profiles)
                    .filter(p -> p.label() != null && p.label().ordinal() == cls)
                    .map(DailyProfile::toFeatureVector)
                    .toArray(double[][]::new);

            for (int j = 0; j < numFeatures; j++) {
                if (subset.length == 0) {
                    // Fallback: Standardnormalverteilung
                    condprob[c][j] = new GaussianDistribution(0.0, 1.0);
                    continue;
                }
                final int fj = j;
                double mean = Arrays.stream(subset)
                        .mapToDouble(row -> row[fj]).average().orElse(0.0);
                double variance = Arrays.stream(subset)
                        .mapToDouble(row -> Math.pow(row[fj] - mean, 2))
                        .average().orElse(1.0);
                // GaussianDistribution(mu, sigma) — sigma ist Standardabweichung, NICHT Varianz!
                condprob[c][j] = new GaussianDistribution(mean, Math.max(Math.sqrt(variance), 1e-4));

                System.out.printf("[SmileNaiveBayes] Klasse %-8s Feature[%d]: μ=%.2f σ=%.2f%n",
                        levels[c], j, mean, Math.sqrt(variance));
            }
        }

        // NaiveBayes(double[] priori, Distribution[][] condprob)
        model = new NaiveBayes(priori, condprob);
    }

    public RiskLevel predict(DailyProfile profile) {
        int idx = model.predict(profile.toFeatureVector());
        return RiskLevel.values()[idx];
    }

    /**
     * Gibt posteriori Wahrscheinlichkeiten zurück: [P(NORMAL), P(ERHOHT), P(GEFAHR)]
     * SoftClassifier.predict(x, posteriori) befüllt das Array in-place.
     */
    public double[] predictProbabilities(DailyProfile profile) {
        double[] posteriori = new double[RiskLevel.values().length];
        model.predict(profile.toFeatureVector(), posteriori);
        return posteriori;
    }
}