package de.hochwasser.analysis;

import de.hochwasser.model.DailyProfile;
import de.hochwasser.analysis.CrossValidator.CrossValidationResult;

/**
 * Orchestriert das gesamte ML-Pipeline für die Hochwasser-Frühwarnung.
 *
 * Workflow:
 *   1. train(historicalProfiles)
 *      a) K-Means clustert die Historien-Daten → automatische Profil-Erkennung
 *      b) Naive Bayes trainiert auf denselben Daten → Wahrscheinlichkeits-Modell
 *      c) Cross-Validation bewertet die Modellgüte
 *
 *   2. assessRisk(currentProfile)
 *      → Kombiniert K-Means (Cluster-Zugehörigkeit) und Naive Bayes (Wahrscheinlichkeit)
 *      → Gibt RiskLevel + Wahrscheinlichkeiten zurück
 */
public class FloodPredictor {

    public enum RiskLevel {
        NORMAL,
        ERHOHT,
        GEFAHR
    }

    private final KMeansClusterer kmeans;
    private final NaiveBayesPredictor naiveBayes;
    private final CrossValidator crossValidator;

    private boolean trained = false;

    public FloodPredictor() {
        this.kmeans         = new KMeansClusterer(3, 200, 42L);
        this.naiveBayes     = new NaiveBayesPredictor();
        this.crossValidator = new CrossValidator(5, 42L);
    }

    // -------------------------------------------------------------------------
    // Training
    // -------------------------------------------------------------------------

    /**
     * Trainiert das komplette Modell auf historischen Tagesprofilen.
     * Gibt den Cross-Validation-Bericht auf der Konsole aus.
     *
     * @param profiles Historische, gelabelte Tagesprofile (aus flood_events + water_levels)
     */
    public CrossValidationResult train(DailyProfile[] profiles) {
        System.out.println("=== FloodPredictor Training ===");
        System.out.printf("Datensatz: %d Tagesprofile%n%n", profiles.length);

        // Schritt 1: K-Means – unsupervised, zeigt natürliche Cluster in den Daten
        System.out.println("--- K-Means Clustering ---");
        kmeans.fit(profiles);

        // Schritt 2: Naive Bayes – supervised, nutzt die Labels aus flood_events
        System.out.println("\n--- Naive Bayes Training ---");
        naiveBayes.train(profiles);

        // Schritt 3: Cross-Validation – bewertet die Modellgüte objektiv
        System.out.println("\n--- 5-Fold Cross-Validation ---");
        CrossValidationResult result = crossValidator.evaluate(profiles);

        trained = true;
        System.out.println("=== Training abgeschlossen ===\n");
        return result;
    }

    // -------------------------------------------------------------------------
    // Vorhersage
    // -------------------------------------------------------------------------

    /**
     * Bewertet das aktuelle Hochwasserrisiko für ein Tagesprofil.
     *
     * Die Risikostufe wird durch den Naive-Bayes-Klassifikator bestimmt.
     * K-Means dient als Plausibilitätsprüfung – wenn beide Modelle
     * übereinstimmen, ist die Vorhersage besonders zuverlässig.
     *
     * @param profile Aktuelles Tagesprofil (ungelabelt)
     * @return PredictionResult mit Risikolevel und Wahrscheinlichkeiten
     */
    public PredictionResult assessRisk(DailyProfile profile) {
        checkTrained();

        RiskLevel bayesLevel    = naiveBayes.predict(profile);
        double[]  probabilities = naiveBayes.predictProbabilities(profile);
        RiskLevel kmeansLevel   = kmeans.predict(profile);

        boolean modelsAgree = bayesLevel.equals(kmeansLevel);

        return new PredictionResult(bayesLevel, probabilities, kmeansLevel, modelsAgree);
    }

    // -------------------------------------------------------------------------
    // Ergebnis-Record
    // -------------------------------------------------------------------------

    /**
     * Ergebnis einer Einzelvorhersage.
     *
     * @param riskLevel       Vorhergesagte Risikostufe (vom Naive Bayes)
     * @param probabilities   Wahrscheinlichkeiten [P(NORMAL), P(ERHOHT), P(GEFAHR)]
     * @param kmeansCluster   Cluster-Zuweisung durch K-Means (zur Validierung)
     * @param highConfidence  true, wenn Naive Bayes und K-Means übereinstimmen
     */
    public record PredictionResult(
        RiskLevel riskLevel,
        double[] probabilities,
        RiskLevel kmeansCluster,
        boolean highConfidence
    ) {
        public double probabilityNormal() { return probabilities[0]; }
        public double probabilityErhoht() { return probabilities[1]; }
        public double probabilityGefahr() { return probabilities[2]; }

        @Override
        public String toString() {
            return """
                Risikostufe:  %s %s
                P(NORMAL):    %.1f%%
                P(ERHOHT):    %.1f%%
                P(GEFAHR):    %.1f%%
                K-Means:      %s
                Konfidenz:    %s
                """.formatted(
                riskLevel,
                emoji(riskLevel),
                probabilityNormal() * 100,
                probabilityErhoht() * 100,
                probabilityGefahr() * 100,
                kmeansCluster,
                highConfidence ? "✅ Hoch (beide Modelle einig)" : "⚠️  Niedrig (Modelle uneinig)"
            );
        }

        private static String emoji(RiskLevel level) {
            return switch (level) {
                case NORMAL -> "🟢";
                case ERHOHT -> "🟡";
                case GEFAHR -> "🔴";
            };
        }
    }

    // -------------------------------------------------------------------------
    // Hilfsmethoden
    // -------------------------------------------------------------------------

    private void checkTrained() {
        if (!trained) throw new IllegalStateException(
            "FloodPredictor nicht trainiert – bitte zuerst train() aufrufen.");
    }
}
