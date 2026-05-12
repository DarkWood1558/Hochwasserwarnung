package de.hochwasser.analysis;

import de.hochwasser.analysis.WaterLevelRegressor.LevelForecast;
import de.hochwasser.analysis.WaterLevelRegressor.RegressionSample;
import de.hochwasser.analysis.CrossValidator.CrossValidationResult;
import de.hochwasser.model.DailyProfile;

/**
 * Zentrale ML-Pipeline: orchestriert alle Modelle und liefert ein
 * umfassendes Vorhersage-Ergebnis aus einem einzigen Aufruf.
 *
 * ── Modelle ──────────────────────────────────────────────────────────────────
 *  1. KMeansClusterer        Unsupervised: Cluster-Zugehörigkeit (DM1)
 *  2. NaiveBayesPredictor    Supervised:  Risikoklasse + Wahrscheinlichkeit (DM1)
 *  3. CrossValidator         Evaluation:  Modellgüte (DM1)
 *  4. WaveTravelTimeModel    Zeitreihe:   Wellen-Laufzeit CZ→DE (DM1 + ADBC2)
 *  5. WaterLevelRegressor    Regression:  Pegelstand in 6h/12h/24h (DM1)
 *  6. DbscanAnomalyDetector  Clustering:  Anomalie-Flagging (DM1)
 *  7. BayesianNetwork        Kausal:      P(Risiko | Ursachen) (DM1)
 *
 * ── Ensemble-Entscheidung ────────────────────────────────────────────────────
 * Jedes Modell liefert eine Risikoeinschätzung. Die finale Entscheidung
 * wird durch gewichtetes Voting getroffen:
 *
 *   BayesNet   → Gewicht 3  (kausales Modell, höchste Aussagekraft)
 *   NaiveBayes → Gewicht 2
 *   KMeans     → Gewicht 1
 *
 * Bei erkannter Anomalie (DBSCAN) wird das Risikolevel mindestens auf
 * ERHOHT angehoben – unbekannte Ereignisse sind immer verdächtig.
 */
public class FloodPredictor {

    // ── Risikoklassen ─────────────────────────────────────────────────────────

    public enum RiskLevel { NORMAL, ERHOHT, GEFAHR }

    // ── Modelle ───────────────────────────────────────────────────────────────

    private final KMeansClusterer        kmeans     = new KMeansClusterer(3, 200, 42L);
    private final NaiveBayesPredictor    naiveBayes = new NaiveBayesPredictor();
    private final CrossValidator         crossVal   = new CrossValidator(5, 42L);
    private final WaveTravelTimeModel    travelTime = new WaveTravelTimeModel();
    private final WaterLevelRegressor    regressor  = new WaterLevelRegressor();
    private final DbscanAnomalyDetector  dbscan     = new DbscanAnomalyDetector(1.0, 10);
    private final BayesianNetwork        bayesNet   = new BayesianNetwork();

    private boolean profileModelsTrained   = false;
    private boolean travelTimeModelTrained = false;
    private boolean regressorTrained       = false;

    // ── Training ──────────────────────────────────────────────────────────────

    /**
     * Trainiert alle profilbasierten Modelle auf historischen Tagesprofilen.
     * Muss vor assessRisk() aufgerufen werden.
     *
     * Trainiert: KMeans, NaiveBayes, CrossValidator, DBSCAN, BayesianNetwork
     *
     * @param profiles Historische, gelabelte Tagesprofile
     * @return CrossValidationResult für Reporting / Beleg
     */
    public CrossValidationResult trainProfileModels(DailyProfile[] profiles) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║   FloodPredictor – Profil-Training   ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.printf("Datensatz: %d Tagesprofile%n%n", profiles.length);

        System.out.println("─── 1/5  K-Means Clustering ───────────");
        kmeans.fit(profiles);

        System.out.println("\n─── 2/5  Naive Bayes ──────────────────");
        naiveBayes.train(profiles);

        System.out.println("\n─── 3/5  Bayessches Netz ──────────────");
        bayesNet.train(profiles);

        System.out.println("\n─── 4/5  DBSCAN Anomalie-Erkennung ────");
        dbscan.fit(profiles);

        System.out.println("\n─── 5/5  5-Fold Cross-Validation ──────");
        CrossValidationResult cv = crossVal.evaluate(profiles);

        profileModelsTrained = true;
        System.out.println("\n✓ Profil-Modelle trainiert\n");
        return cv;
    }

    /**
     * Trainiert das Laufzeit-Modell auf stündlichen Zeitreihendaten.
     * Kann unabhängig von trainProfileModels() aufgerufen werden.
     *
     * @param upstreamHourly   Stündliche Pegelstände Hradek (cm), zeitlich aligned
     * @param goerlitzHourly   Stündliche Pegelstände Görlitz (cm)
     */
    public void trainTravelTimeModel(double[] upstreamHourly, double[] goerlitzHourly) {
        System.out.println("─── Laufzeit-Modell (Kreuzkorrelation) ─");
        travelTime.fit(upstreamHourly, goerlitzHourly);
        travelTimeModelTrained = true;
        System.out.println("✓ Laufzeit-Modell trainiert\n");
    }

    /**
     * Trainiert den Pegel-Regressor auf historischen Stichproben.
     * Jeder Sample enthält Features zum Zeitpunkt t und Zielwerte für t+6h/12h/24h.
     *
     * @param samples Trainings-Samples mit Ziel-Pegelständen
     */
    public void trainRegressor(RegressionSample[] samples) {
        System.out.println("─── Pegel-Regression (6h / 12h / 24h) ─");
        regressor.train(samples);
        regressorTrained = true;
        System.out.println("✓ Regressionsmodell trainiert\n");
    }

    // ── Vorhersage ────────────────────────────────────────────────────────────

    /**
     * Vollständige Risikoabschätzung für ein aktuelles Tagesprofil.
     * Alle verfügbaren Modelle werden kombiniert.
     *
     * @param profile         Aktuelles Tagesprofil (ungelabelt)
     * @param regressionSample Aktueller Messzeitpunkt für Regression (darf null sein)
     * @return ComprehensiveResult mit allen Modell-Outputs und Ensemble-Entscheidung
     */
    public ComprehensiveResult assessRisk(DailyProfile profile,
                                          RegressionSample regressionSample) {
        if (!profileModelsTrained)
            throw new IllegalStateException("trainProfileModels() muss zuerst aufgerufen werden.");

        // ── Einzelmodelle ──────────────────────────────────────────────────
        RiskLevel  kmeansLevel   = kmeans.predict(profile);
        RiskLevel  nbLevel       = naiveBayes.predict(profile);
        double[]   nbProbs       = naiveBayes.predictProbabilities(profile);
        double[]   bayesProbs    = bayesNet.infer(profile);
        RiskLevel  bayesLevel    = bayesNet.predict(profile);
        boolean    isAnomaly     = dbscan.isAnomaly(profile);
        int        dbscanCluster = dbscan.predict(profile);
        String     anomalyDesc   = isAnomaly ? dbscan.describeAnomaly(profile) : null;

        double travelHours = travelTimeModelTrained
            ? travelTime.predictTravelHours(profile.upstreamMaxLevelCm())
            : -1;
        String travelDesc = travelTimeModelTrained
            ? travelTime.describeArrival(profile.upstreamMaxLevelCm())
            : "Laufzeit-Modell nicht trainiert";

        LevelForecast forecast = (regressorTrained && regressionSample != null)
            ? regressor.predict(regressionSample)
            : null;

        // ── Ensemble-Voting ────────────────────────────────────────────────
        RiskLevel ensembleRisk = ensembleVote(kmeansLevel, nbLevel, bayesLevel, isAnomaly);

        // ── Konfidenz: stimmen alle Hauptmodelle überein? ──────────────────
        boolean highConfidence = kmeansLevel == nbLevel
                              && nbLevel == bayesLevel
                              && !isAnomaly;

        return new ComprehensiveResult(
            ensembleRisk, highConfidence,
            nbLevel, nbProbs,
            bayesLevel, bayesProbs,
            kmeansLevel,
            isAnomaly, dbscanCluster, anomalyDesc,
            travelHours, travelDesc,
            forecast
        );
    }

    /** Vereinfachte Variante ohne Regression (für schnellen Echtzeit-Check). */
    public ComprehensiveResult assessRisk(DailyProfile profile) {
        return assessRisk(profile, null);
    }

    // ── Ensemble-Logik ────────────────────────────────────────────────────────

    /**
     * Gewichtetes Voting:
     *   BayesNet   → 3 Punkte
     *   NaiveBayes → 2 Punkte
     *   KMeans     → 1 Punkt
     *
     * DBSCAN-Anomalie → Mindest-Risikolevel ERHOHT (Vorsichtsprinzip)
     */
    private static RiskLevel ensembleVote(RiskLevel kmeans, RiskLevel nb,
                                           RiskLevel bayes, boolean anomaly) {
        int[] votes = new int[RiskLevel.values().length];
        votes[bayes.ordinal()] += 3;
        votes[nb.ordinal()]    += 2;
        votes[kmeans.ordinal()]+= 1;

        int best = 0;
        for (int i = 1; i < votes.length; i++) if (votes[i] > votes[best]) best = i;
        RiskLevel result = RiskLevel.values()[best];

        // Anomalie-Anhebung: unbekannte Ereignisse → mind. ERHOHT
        if (anomaly && result == RiskLevel.NORMAL) return RiskLevel.ERHOHT;
        return result;
    }

    // ── Ergebnis-Record ───────────────────────────────────────────────────────

    /**
     * Vollständiges Vorhersage-Ergebnis aller Modelle.
     */
    public record ComprehensiveResult(
        RiskLevel ensembleRisk,        // Finale Ensemble-Entscheidung
        boolean   highConfidence,      // Alle Modelle einig?

        RiskLevel nbRisk,              // Naive Bayes
        double[]  nbProbabilities,     // [P(NORMAL), P(ERHOHT), P(GEFAHR)]

        RiskLevel bayesRisk,           // Bayessches Netz
        double[]  bayesProbabilities,  // [P(NORMAL), P(ERHOHT), P(GEFAHR)]

        RiskLevel kmeansCluster,       // K-Means Cluster-Label

        boolean   isAnomaly,           // DBSCAN: unbekanntes Muster?
        int       dbscanClusterId,     // DBSCAN Cluster-ID (-1 = Anomalie)
        String    anomalyDescription,  // Welche Features sind auffällig?

        double    travelTimeHours,     // Wellen-Laufzeit von Hradek (-1 = unbekannt)
        String    travelTimeDesc,      // Lesbare Beschreibung Ankunftszeit

        LevelForecast levelForecast    // Pegelstand in 6h/12h/24h (null = nicht verfügbar)
    ) {

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("╔══════════════════════════════════════════╗\n");
            sb.append(String.format("║  Risiko: %-5s %-4s  Konfidenz: %-3s   ║%n",
                ensembleRisk, emoji(ensembleRisk),
                highConfidence ? "✅" : "⚠️ "));
            sb.append("╠══════════════════════════════════════════╣\n");

            sb.append(String.format("║  NaiveBayes: %-8s  "
                + "P(G)=%.0f%%  P(E)=%.0f%%   ║%n",
                nbRisk,
                nbProbabilities[2] * 100,
                nbProbabilities[1] * 100));

            sb.append(String.format("║  BayesNet:   %-8s  "
                + "P(G)=%.0f%%  P(E)=%.0f%%   ║%n",
                bayesRisk,
                bayesProbabilities[2] * 100,
                bayesProbabilities[1] * 100));

            sb.append(String.format("║  K-Means:    %-28s ║%n", kmeansCluster));

            sb.append(String.format("║  DBSCAN:     %-28s ║%n",
                isAnomaly ? "⚠️  ANOMALIE (Cluster " + dbscanClusterId + ")"
                          : "Cluster " + dbscanClusterId));

            sb.append(String.format("║  Laufzeit:   %-28s ║%n", travelTimeDesc));

            if (levelForecast != null) {
                sb.append("╠══════════════════════════════════════════╣\n");
                sb.append(String.format("║  Pegel  6h: %5.0f cm  "
                    + "12h: %5.0f cm  24h: %5.0f cm ║%n",
                    levelForecast.level6h(),
                    levelForecast.level12h(),
                    levelForecast.level24h()));
            }

            if (isAnomaly && anomalyDescription != null) {
                sb.append("╠══════════════════════════════════════════╣\n");
                sb.append("║  ").append(anomalyDescription.replace("\n", "\n║  "));
            }

            sb.append("╚══════════════════════════════════════════╝");
            return sb.toString();
        }

        private static String emoji(RiskLevel l) {
            return switch (l) { case NORMAL -> "🟢"; case ERHOHT -> "🟡"; case GEFAHR -> "🔴"; };
        }
    }
}
