package de.hochwasser.analysis;

import de.hochwasser.analysis.FloodPredictor.RiskLevel;
import de.hochwasser.model.DailyProfile;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/**
 * Gaussscher Naive-Bayes-Klassifikator für Hochwasser-Risikoklassifikation.
 *
 * Zweck im Projekt (Data Mining 1):
 * Gegeben die aktuellen Features (Pegelstand, Anstiegsrate, Upstream-Pegel,
 * Niederschlag) berechnet das Modell die Wahrscheinlichkeit für jede Risikostufe.
 * Das ermöglicht eine probabilistische 24h-Vorhersage statt harter Schwellwerte.
 *
 * Modell: Gauß'scher Naive Bayes
 * --------------------------------
 * Annahme: Features sind bedingt unabhängig gegeben der Klasse (naive Annahme).
 * Jede Feature-Klasse-Kombination wird durch eine Normalverteilung N(μ, σ²)
 * beschrieben, deren Parameter aus den Trainingsdaten geschätzt werden.
 *
 * Klassifikation:
 *   P(class | x) ∝ P(class) * ∏_j P(x_j | class)
 *
 * Log-Wahrscheinlichkeiten werden für numerische Stabilität verwendet.
 *
 * Warum Naive Bayes für Hochwasser?
 * - Funktioniert gut bei kleinen Datensätzen (historische Ereignisse sind selten)
 * - Liefert Wahrscheinlichkeiten, nicht nur ein Label → besser für Frühwarnung
 * - Gut interpretierbar → wichtig für Präsentation
 */
public class NaiveBayesPredictor {

    private final RiskLevel[] classes = RiskLevel.values(); // NORMAL, ERHOHT, GEFAHR
    private final int numFeatures = DailyProfile.FEATURE_COUNT;

    // Gelernte Parameter: pro Klasse und Feature → Mittelwert und Varianz
    private Map<RiskLevel, double[]> classMeans;    // [FEATURE_COUNT]
    private Map<RiskLevel, double[]> classVariances; // [FEATURE_COUNT]
    private Map<RiskLevel, Double>   classPriors;    // P(class)

    private boolean trained = false;

    // -------------------------------------------------------------------------
    // Training
    // -------------------------------------------------------------------------

    /**
     * Schätzt die Parameter des Modells aus den Trainingsdaten.
     * Benötigt gelabelte DailyProfile (label != null).
     *
     * @param profiles Historische, gelabelte Tagesprofile
     */
    public void train(DailyProfile[] profiles) {
        classMeans     = new EnumMap<>(RiskLevel.class);
        classVariances = new EnumMap<>(RiskLevel.class);
        classPriors    = new EnumMap<>(RiskLevel.class);

        for (RiskLevel level : classes) {
            DailyProfile[] subset = Arrays.stream(profiles)
                .filter(p -> level.equals(p.label()))
                .toArray(DailyProfile[]::new);

            if (subset.length == 0) {
                // Kein Training-Beispiel für diese Klasse → Laplace-Smoothing
                classMeans.put(level, new double[numFeatures]);
                classVariances.put(level, zerosFilledWith(numFeatures, 1.0));
                classPriors.put(level, 1.0 / (profiles.length + classes.length));
                continue;
            }

            classMeans.put(level, computeMeans(subset));
            classVariances.put(level, computeVariances(subset, classMeans.get(level)));
            classPriors.put(level, (double) subset.length / profiles.length);

            System.out.printf("[NaiveBayes] Klasse %-8s: %3d Samples, " +
                "μ_maxLevel=%.1f, σ²_maxLevel=%.1f%n",
                level, subset.length,
                classMeans.get(level)[0],
                classVariances.get(level)[0]);
        }
        trained = true;
    }

    // -------------------------------------------------------------------------
    // Vorhersage
    // -------------------------------------------------------------------------

    /**
     * Sagt die wahrscheinlichste Risikostufe vorher.
     *
     * @param profile Tagesprofil (kann ungelabelt sein)
     * @return Vorhergesagtes Risikolevel
     */
    public RiskLevel predict(DailyProfile profile) {
        checkTrained();
        double[] logProbs = computeLogPosteriors(profile.toFeatureVector());
        return classes[argmax(logProbs)];
    }

    /**
     * Gibt die Wahrscheinlichkeiten für alle Risikostufen zurück.
     * Summe ≈ 1.0 (normalisiert via Softmax auf Log-Wahrscheinlichkeiten).
     *
     * @param profile Tagesprofil
     * @return double[] mit P(NORMAL), P(ERHOHT), P(GEFAHR)
     */
    public double[] predictProbabilities(DailyProfile profile) {
        checkTrained();
        double[] logProbs = computeLogPosteriors(profile.toFeatureVector());
        return softmax(logProbs);
    }

    /**
     * Gibt die Feature-Mittelwerte pro Klasse zurück (für Analyse/Präsentation).
     */
    public Map<RiskLevel, double[]> getClassMeans() {
        return classMeans;
    }

    // -------------------------------------------------------------------------
    // Kern: Log-Posterior berechnen
    // -------------------------------------------------------------------------

    /**
     * Berechnet den un-normalisierten log-Posterior für jede Klasse:
     *   log P(class | x) ∝ log P(class) + Σ_j log P(x_j | class)
     *
     * P(x_j | class) = Gauß-PDF: (1 / √(2πσ²)) * exp(-(x-μ)²/(2σ²))
     * Im Log-Raum:    = -0.5 * log(2πσ²) - (x-μ)²/(2σ²)
     */
    private double[] computeLogPosteriors(double[] features) {
        double[] logProbs = new double[classes.length];

        for (int c = 0; c < classes.length; c++) {
            RiskLevel level = classes[c];
            double[] means = classMeans.get(level);
            double[] variances = classVariances.get(level);

            // Log-Prior
            logProbs[c] = Math.log(classPriors.get(level));

            // Log-Likelihood: Summe der Gauß-Log-PDFs
            for (int j = 0; j < numFeatures; j++) {
                logProbs[c] += gaussianLogPdf(features[j], means[j], variances[j]);
            }
        }
        return logProbs;
    }

    /**
     * Gauß'sche Log-Wahrscheinlichkeitsdichte.
     *   log P(x | μ, σ²) = -0.5 * [log(2π) + log(σ²) + (x-μ)²/σ²]
     */
    private static double gaussianLogPdf(double x, double mean, double variance) {
        double v = Math.max(variance, 1e-9); // Numerische Stabilität
        return -0.5 * (Math.log(2 * Math.PI * v) + Math.pow(x - mean, 2) / v);
    }

    // -------------------------------------------------------------------------
    // Parameter-Schätzung
    // -------------------------------------------------------------------------

    private double[] computeMeans(DailyProfile[] profiles) {
        double[] means = new double[numFeatures];
        for (DailyProfile p : profiles) {
            double[] features = p.toFeatureVector();
            for (int j = 0; j < numFeatures; j++) means[j] += features[j];
        }
        for (int j = 0; j < numFeatures; j++) means[j] /= profiles.length;
        return means;
    }

    private double[] computeVariances(DailyProfile[] profiles, double[] means) {
        double[] variances = new double[numFeatures];
        for (DailyProfile p : profiles) {
            double[] features = p.toFeatureVector();
            for (int j = 0; j < numFeatures; j++)
                variances[j] += Math.pow(features[j] - means[j], 2);
        }
        for (int j = 0; j < numFeatures; j++) {
            variances[j] = profiles.length > 1
                ? variances[j] / (profiles.length - 1)   // Stichprobenvarianz (Bessel)
                : 1.0;                                     // Einzelner Datenpunkt: Fallback
        }
        return variances;
    }

    // -------------------------------------------------------------------------
    // Hilfsmethoden
    // -------------------------------------------------------------------------

    /** Softmax: normalisiert log-Wahrscheinlichkeiten zu einer Wahrscheinlichkeitsverteilung. */
    private static double[] softmax(double[] logits) {
        double max = Arrays.stream(logits).max().orElse(0);
        double[] exp = new double[logits.length];
        double sum = 0;
        for (int i = 0; i < logits.length; i++) {
            exp[i] = Math.exp(logits[i] - max); // numerische Stabilität
            sum += exp[i];
        }
        for (int i = 0; i < exp.length; i++) exp[i] /= sum;
        return exp;
    }

    private static int argmax(double[] arr) {
        int best = 0;
        for (int i = 1; i < arr.length; i++) if (arr[i] > arr[best]) best = i;
        return best;
    }

    private static double[] zerosFilledWith(int size, double val) {
        double[] arr = new double[size];
        Arrays.fill(arr, val);
        return arr;
    }

    private void checkTrained() {
        if (!trained) throw new IllegalStateException(
            "Modell nicht trainiert – bitte zuerst train() aufrufen.");
    }
}
