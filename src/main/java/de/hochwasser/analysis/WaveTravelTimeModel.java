package de.hochwasser.analysis;

/**
 * Modelliert die Laufzeit einer Hochwasserwelle von Hradek (CZ) nach Görlitz (DE).
 *
 * Das ist das stärkste Feature dieses Projekts:
 * Steigende Pegel in Tschechien kündigen das Hochwasser in Görlitz
 * typisch 4–12 Stunden vorher an. Diese Laufzeit ist aber nicht konstant –
 * sie hängt von der Wassermenge ab (voller Fluss = schneller).
 *
 * ── Schritt 1: Kreuzkorrelation ─────────────────────────────────────────────
 * Für jeden Lag τ (1..MAX_LAG Stunden) berechnen wir die Pearson-Korrelation
 * zwischen upstream(t) und downstream(t+τ):
 *
 *   r(τ) = Σ[(up(t) − μ_up)(down(t+τ) − μ_down)] / (n · σ_up · σ_down)
 *
 * Der Lag τ* mit dem höchsten r(τ) ist die durchschnittliche Laufzeit.
 *
 * ── Schritt 2: Laufzeit-Regression ──────────────────────────────────────────
 * Die Laufzeit ist nicht fix: Bei 300 cm Upstream-Pegel dauert es länger
 * als bei 600 cm (mehr Wasser = mehr Druck = schneller).
 * Wir fitten eine lineare Regression: travelTime = a · peakLevel + b
 * Damit kann das Modell für jeden aktuellen Upstream-Pegel die individuelle
 * Ankunftszeit vorhersagen.
 *
 * Datenformat (stündlich, zeitlich ausgerichtet):
 *   upstreamLevels[i]   = Pegel Hradek zum Zeitpunkt i
 *   downstreamLevels[i] = Pegel Görlitz zum Zeitpunkt i
 */
public class WaveTravelTimeModel {

    private static final int MAX_LAG = 48; // Maximale Laufzeit in Stunden

    // Ergebnis der Kreuzkorrelation
    private int optimalLagHours = -1;
    private double[] correlations; // correlations[lag] = r(lag)

    // Laufzeit-Regression: travelTime = slope * peakLevel + intercept
    private double regressionSlope     = 0;
    private double regressionIntercept = 0;
    private boolean trained = false;

    // ── Training ──────────────────────────────────────────────────────────────

    /**
     * Trainiert das Modell auf historischen Stundenwerten.
     *
     * @param upstreamLevels   Stündliche Pegelstände Hradek (cm)
     * @param downstreamLevels Stündliche Pegelstände Görlitz (cm), zeitgleich
     */
    public void fit(double[] upstreamLevels, double[] downstreamLevels) {
        if (upstreamLevels.length != downstreamLevels.length)
            throw new IllegalArgumentException("Arrays müssen gleich lang sein");
        if (upstreamLevels.length < MAX_LAG + 10)
            throw new IllegalArgumentException("Zu wenig Daten für Kreuzkorrelation");

        correlations = computeCrossCorrelation(upstreamLevels, downstreamLevels);
        optimalLagHours = argmax(correlations);

        fitTravelTimeRegression(upstreamLevels, downstreamLevels);
        trained = true;

        System.out.printf("[WaveTravelTime] Optimaler Lag: %d Stunden (r=%.3f)%n",
            optimalLagHours, correlations[optimalLagHours]);
        System.out.printf("[WaveTravelTime] Regression: travelTime = %.4f * peakLevel + %.2f%n",
            regressionSlope, regressionIntercept);
    }

    // ── Vorhersage ────────────────────────────────────────────────────────────

    /**
     * Schätzt die Laufzeit für einen gegebenen Upstream-Peakpegel.
     * Höherer Pegel = mehr Wasserdruck = kürzere Laufzeit.
     *
     * @param upstreamPeakLevelCm Aktueller Maximal-Pegel in Hradek
     * @return Vorhergesagte Laufzeit in Stunden
     */
    public double predictTravelHours(double upstreamPeakLevelCm) {
        checkTrained();
        double predicted = regressionSlope * upstreamPeakLevelCm + regressionIntercept;
        // Plausibilitätsgrenzen: min 1h, max MAX_LAG
        return Math.max(1, Math.min(MAX_LAG, predicted));
    }

    /**
     * Gibt die geschätzte Ankunftszeit als lesbare Zeichenkette zurück.
     * z.B. "~6 Stunden (Ankunft gegen 14:00 Uhr)"
     */
    public String describeArrival(double upstreamPeakLevelCm) {
        checkTrained();
        double hours = predictTravelHours(upstreamPeakLevelCm);
        long nowHour = java.time.LocalTime.now().getHour();
        long arrivalHour = (nowHour + Math.round(hours)) % 24;
        return String.format("~%.0f Stunden (Ankunft gegen %02d:00 Uhr)", hours, arrivalHour);
    }

    /** Gibt den durch Kreuzkorrelation bestimmten mittleren Lag zurück. */
    public int getOptimalLagHours() { checkTrained(); return optimalLagHours; }

    /** Gibt alle Korrelationswerte zurück (für Visualisierung im Dashboard). */
    public double[] getAllCorrelations() { checkTrained(); return correlations.clone(); }

    // ── Kreuzkorrelation ─────────────────────────────────────────────────────

    private double[] computeCrossCorrelation(double[] upstream, double[] downstream) {
        int n = upstream.length;
        double muUp   = mean(upstream);
        double muDown = mean(downstream);
        double sigUp  = stdDev(upstream,  muUp);
        double sigDown = stdDev(downstream, muDown);
        double denom  = sigUp * sigDown;

        double[] r = new double[MAX_LAG + 1];
        for (int lag = 0; lag <= MAX_LAG; lag++) {
            double sum = 0;
            int count = n - lag;
            for (int t = 0; t < count; t++) {
                sum += (upstream[t] - muUp) * (downstream[t + lag] - muDown);
            }
            r[lag] = denom > 0 ? sum / (count * denom) : 0;
        }
        return r;
    }

    // ── Laufzeit-Regression ───────────────────────────────────────────────────

    /**
     * Findet für jedes lokale Maximum im Upstream-Signal den korrespondierenden
     * Downstream-Peak und misst die tatsächliche Laufzeit.
     * Diese (peakLevel, travelTime)-Paare werden dann linear regressiert.
     */
    private void fitTravelTimeRegression(double[] upstream, double[] downstream) {
        java.util.List<double[]> pairs = new java.util.ArrayList<>();

        for (int i = 1; i < upstream.length - optimalLagHours - 1; i++) {
            // Lokales Maximum im Upstream?
            if (upstream[i] > upstream[i-1] && upstream[i] > upstream[i+1]
                    && upstream[i] > mean(upstream) * 1.2) {

                // Finde korrespondierendes Maximum im Downstream (im Suchfenster ±12h)
                int searchStart = Math.max(i, i + optimalLagHours - 12);
                int searchEnd   = Math.min(downstream.length - 1, i + optimalLagHours + 12);

                int downPeakIdx = searchStart;
                for (int j = searchStart + 1; j <= searchEnd; j++) {
                    if (downstream[j] > downstream[downPeakIdx]) downPeakIdx = j;
                }

                int actualLag = downPeakIdx - i;
                if (actualLag > 0 && actualLag <= MAX_LAG) {
                    pairs.add(new double[]{upstream[i], actualLag});
                }
            }
        }

        if (pairs.size() < 2) {
            // Fallback: konstante Laufzeit = optimalLagHours
            regressionSlope     = 0;
            regressionIntercept = optimalLagHours;
            System.out.println("[WaveTravelTime] Zu wenig Peaks – verwende konstante Laufzeit");
            return;
        }

        // Einfache lineare Regression: y = a*x + b
        double[] x = pairs.stream().mapToDouble(p -> p[0]).toArray();
        double[] y = pairs.stream().mapToDouble(p -> p[1]).toArray();
        double[] ab = linearRegression(x, y);
        regressionSlope     = ab[0];
        regressionIntercept = ab[1];

        System.out.printf("[WaveTravelTime] Regression auf %d Ereignis-Paaren%n", pairs.size());
    }

    // ── Statistik-Hilfsmethoden ───────────────────────────────────────────────

    /** Gibt [slope, intercept] der einfachen linearen Regression zurück. */
    static double[] linearRegression(double[] x, double[] y) {
        int n = x.length;
        double muX = mean(x), muY = mean(y);
        double num = 0, den = 0;
        for (int i = 0; i < n; i++) {
            num += (x[i] - muX) * (y[i] - muY);
            den += (x[i] - muX) * (x[i] - muX);
        }
        double slope     = den != 0 ? num / den : 0;
        double intercept = muY - slope * muX;
        return new double[]{slope, intercept};
    }

    static double mean(double[] arr) {
        double s = 0; for (double v : arr) s += v; return s / arr.length;
    }

    static double stdDev(double[] arr, double mu) {
        double s = 0; for (double v : arr) s += (v - mu) * (v - mu);
        return Math.sqrt(s / arr.length);
    }

    private static int argmax(double[] arr) {
        int best = 0;
        for (int i = 1; i < arr.length; i++) if (arr[i] > arr[best]) best = i;
        return best;
    }

    private void checkTrained() {
        if (!trained) throw new IllegalStateException("Modell nicht trainiert.");
    }
}
