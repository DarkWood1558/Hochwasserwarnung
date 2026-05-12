package de.hochwasser.analysis;

import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * Multipler linearer Regressionsmodell für Pegelstand-Vorhersagen.
 *
 * Sagt den konkreten Pegelstand in 6h, 12h und 24h vorher.
 * Statt "ERHOHT" bekommt der Nutzer: "In 24 Stunden: ~347 cm".
 *
 * ── Modell ───────────────────────────────────────────────────────────────────
 * Für jeden Zeithorizont (6h, 12h, 24h) wird ein eigenes Modell trainiert:
 *
 *   level(t+h) = w₀ + w₁·level(t) + w₂·rateOfChange + w₃·upstreamLevel
 *              + w₄·precipitation + w₅·sin(Jahrestag) + w₆·cos(Jahrestag)
 *
 * Die sin/cos-Terme kodieren die Saisonalität (Frühjahrshochwasser vs. Sommer).
 *
 * ── Normalengleichung ─────────────────────────────────────────────────────────
 * Optimale Gewichte ohne iteratives Training:
 *   w = (Xᵀ X)⁻¹ Xᵀ y
 *
 * Für Matrizen dieser Größe (~7×7) ist Gauß-Elimination schnell und exakt.
 *
 * ── Input (RegressionSample) ─────────────────────────────────────────────────
 * Jeder Sample = ein Zeitpunkt t mit bekanntem Messwert für t+6h, t+12h, t+24h.
 * Diese werden aus der DB geladen (stündliche Messreihe mit time-shift).
 */
public class WaterLevelRegressor {

    public static final int[] HORIZONS_H = {6, 12, 24}; // Vorhersage-Horizonte

    // Gewichtsvektor pro Horizont: weights[horizonIdx][featureIdx]
    private double[][] weights;
    private double[]   trainingRmse;
    private boolean    trained = false;

    // ── Trainings-Datenstruktur ───────────────────────────────────────────────

    /**
     * Ein Trainings-Sample: Features zum Zeitpunkt t, Zielpegel für t+6h/12h/24h.
     *
     * @param levelCm          Aktueller Pegelstand (cm)
     * @param rateOfChangeCmH  Änderungsrate (cm/h, positiv = steigend)
     * @param upstreamLevelCm  Gleichzeitiger Upstream-Pegel (cm)
     * @param precipMm         Niederschlag der letzten 24h (mm)
     * @param timestamp        Zeitpunkt (für Saisonalitäts-Encoding)
     * @param targetLevels     Zielpegel [t+6h, t+12h, t+24h] in cm
     */
    public record RegressionSample(
        double levelCm,
        double rateOfChangeCmH,
        double upstreamLevelCm,
        double precipMm,
        LocalDateTime timestamp,
        double[] targetLevels      // [0]=t+6h, [1]=t+12h, [2]=t+24h
    ) {
        /** Extrahiert den normalisierten Feature-Vektor inkl. Bias und Saisonalität. */
        double[] toFeatureVector() {
            int dayOfYear = timestamp.getDayOfYear();
            return new double[]{
                1.0,                                                // Bias w₀
                levelCm,                                            // w₁
                rateOfChangeCmH,                                    // w₂
                upstreamLevelCm,                                    // w₃
                precipMm,                                           // w₄
                Math.sin(2 * Math.PI * dayOfYear / 365.0),         // w₅ Saisonalität
                Math.cos(2 * Math.PI * dayOfYear / 365.0)          // w₆
            };
        }
    }

    // ── Training ──────────────────────────────────────────────────────────────

    /**
     * Trainiert drei Regressionsmodelle (je eines pro Zeithorizont).
     *
     * @param samples Historische Samples mit bekannten Zielwerten
     */
    public void train(RegressionSample[] samples) {
        int n = samples.length;
        int f = 7; // Feature-Anzahl inkl. Bias

        // Feature-Matrix X aufbauen [n × f]
        double[][] X = new double[n][f];
        for (int i = 0; i < n; i++) X[i] = samples[i].toFeatureVector();

        weights     = new double[HORIZONS_H.length][f];
        trainingRmse = new double[HORIZONS_H.length];

        for (int h = 0; h < HORIZONS_H.length; h++) {
            // Zielvektoren y für diesen Horizont
            double[] y = new double[n];
            for (int i = 0; i < n; i++) y[i] = samples[i].targetLevels()[h];

            // Normalengleichung: w = (XᵀX)⁻¹ Xᵀy
            double[][] XtX = multiply(transpose(X), X);
            double[] Xty   = multiplyVec(transpose(X), y);
            weights[h]     = solveLinearSystem(XtX, Xty);

            // RMSE auf Trainingsdaten
            double sse = 0;
            for (int i = 0; i < n; i++) {
                double pred = dot(weights[h], X[i]);
                sse += (pred - y[i]) * (pred - y[i]);
            }
            trainingRmse[h] = Math.sqrt(sse / n);

            System.out.printf("[Regression] Horizont %2dh: RMSE=%.1f cm%n",
                HORIZONS_H[h], trainingRmse[h]);
        }
        trained = true;
    }

    // ── Vorhersage ────────────────────────────────────────────────────────────

    /**
     * Sagt den Pegelstand für alle drei Horizonte vorher.
     *
     * @param sample Aktueller Messzeitpunkt als RegressionSample (targetLevels ignoriert)
     * @return PredictionResult mit Pegelstand in 6h, 12h und 24h
     */
    public LevelForecast predict(RegressionSample sample) {
        checkTrained();
        double[] features = sample.toFeatureVector();
        double level6h  = Math.max(0, dot(weights[0], features));
        double level12h = Math.max(0, dot(weights[1], features));
        double level24h = Math.max(0, dot(weights[2], features));
        return new LevelForecast(level6h, level12h, level24h, trainingRmse.clone());
    }

    /**
     * Vorhersage-Ergebnis: Pegelstand in 6h, 12h, 24h mit Fehlerrahmen.
     *
     * @param level6h   Vorhergesagter Pegelstand in 6 Stunden (cm)
     * @param level12h  Vorhergesagter Pegelstand in 12 Stunden (cm)
     * @param level24h  Vorhergesagter Pegelstand in 24 Stunden (cm)
     * @param rmse      Trainings-RMSE pro Horizont [6h, 12h, 24h] als Fehlermaß
     */
    public record LevelForecast(
        double level6h, double level12h, double level24h, double[] rmse
    ) {
        @Override public String toString() {
            return """
                Pegelvorhersage Görlitz:
                  in  6h: %.0f cm  (±%.0f cm)
                  in 12h: %.0f cm  (±%.0f cm)
                  in 24h: %.0f cm  (±%.0f cm)
                """.formatted(
                level6h,  rmse[0],
                level12h, rmse[1],
                level24h, rmse[2]);
        }
    }

    // ── Lineare Algebra (von Grund auf, keine externe Library) ─────────────────

    /** Löst Ax = b via Gauß-Elimination mit Pivotierung. */
    static double[] solveLinearSystem(double[][] A, double[] b) {
        int n = b.length;
        double[][] aug = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            aug[i] = Arrays.copyOf(A[i], n + 1);
            aug[i][n] = b[i];
        }

        // Vorwärtselimination
        for (int col = 0; col < n; col++) {
            // Pivot-Zeile suchen
            int maxRow = col;
            for (int row = col + 1; row < n; row++)
                if (Math.abs(aug[row][col]) > Math.abs(aug[maxRow][col])) maxRow = row;

            double[] tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp;

            if (Math.abs(aug[col][col]) < 1e-12) continue; // Singuläre Matrix

            for (int row = col + 1; row < n; row++) {
                double factor = aug[row][col] / aug[col][col];
                for (int j = col; j <= n; j++) aug[row][j] -= factor * aug[col][j];
            }
        }

        // Rückwärtssubstitution
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            x[i] = aug[i][n];
            for (int j = i + 1; j < n; j++) x[i] -= aug[i][j] * x[j];
            if (Math.abs(aug[i][i]) > 1e-12) x[i] /= aug[i][i];
        }
        return x;
    }

    static double[][] transpose(double[][] M) {
        double[][] T = new double[M[0].length][M.length];
        for (int i = 0; i < M.length; i++)
            for (int j = 0; j < M[0].length; j++) T[j][i] = M[i][j];
        return T;
    }

    static double[][] multiply(double[][] A, double[][] B) {
        int m = A.length, k = B.length, p = B[0].length;
        double[][] C = new double[m][p];
        for (int i = 0; i < m; i++)
            for (int j = 0; j < p; j++)
                for (int l = 0; l < k; l++) C[i][j] += A[i][l] * B[l][j];
        return C;
    }

    static double[] multiplyVec(double[][] A, double[] v) {
        double[] r = new double[A.length];
        for (int i = 0; i < A.length; i++)
            for (int j = 0; j < v.length; j++) r[i] += A[i][j] * v[j];
        return r;
    }

    static double dot(double[] a, double[] b) {
        double s = 0; for (int i = 0; i < a.length; i++) s += a[i] * b[i]; return s;
    }

    private void checkTrained() {
        if (!trained) throw new IllegalStateException("Modell nicht trainiert.");
    }
}
