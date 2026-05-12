package de.hochwasser.analysis;

import de.hochwasser.analysis.FloodPredictor.RiskLevel;
import de.hochwasser.model.DailyProfile;

import java.util.Arrays;
import java.util.Random;

/**
 * k-Fold-Cross-Validation für den NaiveBayesPredictor.
 *
 * Zweck im Projekt (Data Mining 1):
 * Da historische Hochwasserereignisse selten sind, haben wir wenige Daten.
 * Cross-Validation maximiert den Dateneinsatz: Jeder Datenpunkt wird genau
 * einmal als Testdaten verwendet, der Rest zum Training.
 *
 * Ablauf (k=5):
 *   Daten aufteilen in 5 gleich große Folds
 *   Für jeden Fold i (i = 0..4):
 *     - Trainiere Modell auf allen Folds außer i
 *     - Teste auf Fold i
 *     - Messe Accuracy und Konfusionsmatrix
 *   Durchschnittliche Accuracy über alle Folds = Ergebnis
 *
 * Ergebnis-Interpretation:
 *   - Accuracy 0.85 = 85% der Tage korrekt klassifiziert
 *   - Konfusionsmatrix zeigt, welche Risikostufen verwechselt werden
 */
public class CrossValidator {

    private final int k;
    private final long seed;

    /**
     * @param k    Anzahl Folds (typisch 5 oder 10)
     * @param seed Zufalls-Seed für reproduzierbares Mischen
     */
    public CrossValidator(int k, long seed) {
        if (k < 2) throw new IllegalArgumentException("k muss mindestens 2 sein");
        this.k = k;
        this.seed = seed;
    }

    // -------------------------------------------------------------------------
    // Haupt-Methode
    // -------------------------------------------------------------------------

    /**
     * Führt k-Fold-Cross-Validation durch und gibt ein vollständiges
     * Evaluierungsergebnis zurück.
     *
     * @param profiles Gelabelte Tagesprofile (label != null)
     * @return CrossValidationResult mit Accuracy und Konfusionsmatrix
     */
    public CrossValidationResult evaluate(DailyProfile[] profiles) {
        if (profiles.length < k) {
            throw new IllegalArgumentException(
                "Zu wenig Daten (%d) für %d Folds".formatted(profiles.length, k));
        }

        DailyProfile[] shuffled = shuffle(profiles);
        DailyProfile[][] folds = splitIntoFolds(shuffled);

        RiskLevel[] allLabels = RiskLevel.values();
        int numClasses = allLabels.length;
        int[][] confusionMatrix = new int[numClasses][numClasses]; // [actual][predicted]
        int totalCorrect = 0;
        int totalSamples = 0;

        System.out.printf("[CrossValidation] %d-Fold CV auf %d Samples%n", k, profiles.length);

        for (int fold = 0; fold < k; fold++) {
            // Training: alle Folds außer fold
            DailyProfile[] trainData = concat(folds, fold);
            DailyProfile[] testData  = folds[fold];

            // Neues Modell pro Fold trainieren
            NaiveBayesPredictor model = new NaiveBayesPredictor();
            model.train(trainData);

            // Testen
            int foldCorrect = 0;
            for (DailyProfile testProfile : testData) {
                RiskLevel predicted = model.predict(testProfile);
                RiskLevel actual    = testProfile.label();

                int actualIdx    = indexOf(allLabels, actual);
                int predictedIdx = indexOf(allLabels, predicted);
                confusionMatrix[actualIdx][predictedIdx]++;

                if (predicted.equals(actual)) foldCorrect++;
            }

            double foldAccuracy = (double) foldCorrect / testData.length;
            System.out.printf("  Fold %d/%d: Accuracy = %.1f%% (%d/%d korrekt)%n",
                fold + 1, k, foldAccuracy * 100, foldCorrect, testData.length);

            totalCorrect += foldCorrect;
            totalSamples += testData.length;
        }

        double overallAccuracy = (double) totalCorrect / totalSamples;
        System.out.printf("[CrossValidation] Gesamt-Accuracy: %.1f%%%n",
            overallAccuracy * 100);
        printConfusionMatrix(confusionMatrix, allLabels);

        return new CrossValidationResult(overallAccuracy, confusionMatrix, allLabels);
    }

    // -------------------------------------------------------------------------
    // Hilfsmethoden
    // -------------------------------------------------------------------------

    private DailyProfile[] shuffle(DailyProfile[] profiles) {
        DailyProfile[] shuffled = profiles.clone();
        Random rng = new Random(seed);
        for (int i = shuffled.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            DailyProfile tmp = shuffled[i];
            shuffled[i] = shuffled[j];
            shuffled[j] = tmp;
        }
        return shuffled;
    }

    private DailyProfile[][] splitIntoFolds(DailyProfile[] data) {
        DailyProfile[][] folds = new DailyProfile[k][];
        int base = data.length / k;
        int remainder = data.length % k;
        int offset = 0;

        for (int i = 0; i < k; i++) {
            int size = base + (i < remainder ? 1 : 0);
            folds[i] = Arrays.copyOfRange(data, offset, offset + size);
            offset += size;
        }
        return folds;
    }

    /** Kombiniert alle Folds außer skipFold zu einem Trainings-Array. */
    private DailyProfile[] concat(DailyProfile[][] folds, int skipFold) {
        int totalSize = 0;
        for (int i = 0; i < folds.length; i++)
            if (i != skipFold) totalSize += folds[i].length;

        DailyProfile[] result = new DailyProfile[totalSize];
        int pos = 0;
        for (int i = 0; i < folds.length; i++) {
            if (i == skipFold) continue;
            System.arraycopy(folds[i], 0, result, pos, folds[i].length);
            pos += folds[i].length;
        }
        return result;
    }

    private static int indexOf(RiskLevel[] levels, RiskLevel level) {
        for (int i = 0; i < levels.length; i++)
            if (levels[i] == level) return i;
        return -1;
    }

    private static void printConfusionMatrix(int[][] matrix, RiskLevel[] labels) {
        System.out.println("\n[CrossValidation] Konfusionsmatrix (Zeilen=Actual, Spalten=Predicted):");
        System.out.printf("%-10s", "");
        for (RiskLevel l : labels) System.out.printf("%-10s", l);
        System.out.println();
        for (int i = 0; i < labels.length; i++) {
            System.out.printf("%-10s", labels[i]);
            for (int j = 0; j < labels.length; j++)
                System.out.printf("%-10d", matrix[i][j]);
            System.out.println();
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Ergebnis-Record
    // -------------------------------------------------------------------------

    /**
     * Ergebnis der Cross-Validation.
     *
     * @param accuracy        Durchschnittliche Accuracy (0.0 – 1.0)
     * @param confusionMatrix [actual][predicted] – Anzahl Vorhersagen pro Klassen-Paar
     * @param labels          Reihenfolge der Klassen in der Konfusionsmatrix
     */
    public record CrossValidationResult(
        double accuracy,
        int[][] confusionMatrix,
        RiskLevel[] labels
    ) {
        /** Precision für eine bestimmte Klasse: TP / (TP + FP) */
        public double precision(RiskLevel level) {
            int classIdx = indexOf(labels, level);
            int tp = confusionMatrix[classIdx][classIdx];
            int fp = 0;
            for (int i = 0; i < labels.length; i++)
                if (i != classIdx) fp += confusionMatrix[i][classIdx];
            return tp + fp == 0 ? 0 : (double) tp / (tp + fp);
        }

        /** Recall für eine bestimmte Klasse: TP / (TP + FN) */
        public double recall(RiskLevel level) {
            int classIdx = indexOf(labels, level);
            int tp = confusionMatrix[classIdx][classIdx];
            int fn = 0;
            for (int j = 0; j < labels.length; j++)
                if (j != classIdx) fn += confusionMatrix[classIdx][j];
            return tp + fn == 0 ? 0 : (double) tp / (tp + fn);
        }

        private static int indexOf(RiskLevel[] arr, RiskLevel val) {
            for (int i = 0; i < arr.length; i++) if (arr[i] == val) return i;
            return -1;
        }
    }
}
