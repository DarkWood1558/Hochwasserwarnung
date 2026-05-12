package de.hochwasser.analysis;

import de.hochwasser.analysis.FloodPredictor.RiskLevel;
import de.hochwasser.model.DailyProfile;

import java.time.Month;
import java.util.EnumMap;
import java.util.Map;

/**
 * Bayessches Netz für Hochwasser-Risikoklassifikation.
 *
 * ── Warum Bayessches Netz statt Naive Bayes? ────────────────────────────────
 * Naive Bayes nimmt an, alle Features seien bedingt unabhängig. Das stimmt
 * hier offensichtlich nicht: Upstream-Pegel und Niederschlag sind stark
 * korreliert. Ein Bayessches Netz modelliert diese kausalen Abhängigkeiten
 * explizit als gerichteten azyklischen Graph (DAG).
 *
 * ── Netzstruktur (DAG) ───────────────────────────────────────────────────────
 *
 *   Jahreszeit ──┬──→ Bodenfeuchte ──┐
 *                │                    ↓
 *   Niederschlag ┘──→ Upstream-Pegel → FloodRisk
 *                │                    ↑
 *                └───────────────────┘
 *
 * In Worten: Flood-Risiko hängt von Upstream-Pegel und Bodenfeuchte ab.
 * Bodenfeuchte wird durch Jahreszeit und Niederschlag beeinflusst.
 * Upstream-Pegel hängt von Niederschlag ab.
 *
 * ── Diskretisierung ──────────────────────────────────────────────────────────
 * Alle Knoten werden in diskrete Stufen aufgeteilt:
 *   Niederschlag  : NONE / LIGHT / HEAVY
 *   UpstreamLevel : LOW / MEDIUM / HIGH
 *   Jahreszeit    : SPRING / SUMMER / AUTUMN / WINTER
 *   Bodenfeuchte  : DRY / MOIST / SATURATED    (latenter Knoten)
 *   FloodRisk     : NORMAL / ERHOHT / GEFAHR
 *
 * ── Parameter-Lernen ─────────────────────────────────────────────────────────
 * Bedingte Wahrscheinlichkeitstabellen (CPTs) werden durch Häufigkeitszählung
 * auf Trainingsdaten geschätzt. Laplace-Glättung verhindert Nullwahrscheinl.
 *
 * ── Inferenz (Variable Elimination) ─────────────────────────────────────────
 * Gegeben: Niederschlag, UpstreamLevel, Jahreszeit (direkt messbar)
 * Gesucht: P(FloodRisk | Niederschlag, Upstream, Jahreszeit)
 *
 * Da Bodenfeuchte der einzige latente Knoten ist, marginalisieren wir über ihn:
 *   P(risk | precip, upstream, season)
 *     = Σ_soil P(risk | upstream, soil) · P(soil | precip, season)
 */
public class BayesianNetwork {

    // ── Diskrete Zustandsräume ────────────────────────────────────────────────

    public enum Precipitation { NONE, LIGHT, HEAVY }
    public enum UpstreamLevel  { LOW, MEDIUM, HIGH }
    public enum Season         { SPRING, SUMMER, AUTUMN, WINTER }
    public enum SoilMoisture   { DRY, MOIST, SATURATED }

    private static final int P = Precipitation.values().length;   // 3
    private static final int U = UpstreamLevel.values().length;   // 3
    private static final int S = Season.values().length;          // 4
    private static final int M = SoilMoisture.values().length;    // 3
    private static final int R = RiskLevel.values().length;       // 3

    // ── Bedingte Wahrscheinlichkeitstabellen ──────────────────────────────────

    // P(SoilMoisture | Precipitation, Season)  →  [P][S][M]
    private double[][][] cptSoil;

    // P(FloodRisk | UpstreamLevel, SoilMoisture)  →  [U][M][R]
    private double[][][] cptRisk;

    private boolean trained = false;

    // ── Training ──────────────────────────────────────────────────────────────

    /**
     * Lernt die CPTs aus historischen Tagesprofilen.
     * Bodenfeuchte ist nicht direkt messbar – wir schätzen sie aus
     * rollierendem Niederschlag und Jahreszeit heraus (Heuristik).
     *
     * @param profiles Gelabelte historische Tagesprofile
     */
    public void train(DailyProfile[] profiles) {
        // Zähler: [P][S][M] und [U][M][R]
        double[][][] countSoil = new double[P][S][M];
        double[][][] countRisk = new double[U][M][R];

        for (DailyProfile p : profiles) {
            if (p.label() == null) continue;

            int precip   = discretizePrecipitation(p.totalPrecipMm()).ordinal();
            int upstream = discretizeUpstream(p.upstreamMaxLevelCm()).ordinal();
            int season   = getSeason(p.date().getMonth()).ordinal();
            int soil     = estimateSoilMoisture(p).ordinal();
            int risk     = p.label().ordinal();

            countSoil[precip][season][soil]++;
            countRisk[upstream][soil][risk]++;
        }

        // CPTs aus Zählern (mit Laplace-Glättung α=1)
        cptSoil = normalizeCpt3(countSoil, 1.0);
        cptRisk = normalizeCpt3(countRisk, 1.0);

        trained = true;

        System.out.println("[BayesNet] CPTs gelernt:");
        printCptSoil();
        printCptRisk();
    }

    // ── Inferenz ──────────────────────────────────────────────────────────────

    /**
     * Berechnet P(FloodRisk | Niederschlag, UpstreamLevel, Jahreszeit)
     * durch Marginalisierung über Bodenfeuchte:
     *
     *   P(risk | p, u, s) ∝ Σ_m P(risk | u, m) · P(m | p, s)
     *
     * @param profile Tagesprofil (label wird ignoriert)
     * @return Wahrscheinlichkeitsverteilung [P(NORMAL), P(ERHOHT), P(GEFAHR)]
     */
    public double[] infer(DailyProfile profile) {
        checkTrained();

        int precip   = discretizePrecipitation(profile.totalPrecipMm()).ordinal();
        int upstream = discretizeUpstream(profile.upstreamMaxLevelCm()).ordinal();
        int season   = getSeason(profile.date().getMonth()).ordinal();

        double[] result = new double[R];

        // Marginalisierung über Bodenfeuchte
        for (int m = 0; m < M; m++) {
            double pSoil = cptSoil[precip][season][m];
            for (int r = 0; r < R; r++) {
                result[r] += cptRisk[upstream][m][r] * pSoil;
            }
        }

        // Normalisieren (Summe = 1)
        double sum = 0; for (double v : result) sum += v;
        if (sum > 0) for (int r = 0; r < R; r++) result[r] /= sum;

        return result;
    }

    /**
     * Gibt das wahrscheinlichste RiskLevel zurück.
     */
    public RiskLevel predict(DailyProfile profile) {
        double[] probs = infer(profile);
        int best = 0;
        for (int i = 1; i < probs.length; i++) if (probs[i] > probs[best]) best = i;
        return RiskLevel.values()[best];
    }

    // ── Diskretisierungs-Hilfsmethoden ────────────────────────────────────────

    static Precipitation discretizePrecipitation(double mm) {
        if (mm < 1.0)  return Precipitation.NONE;
        if (mm < 10.0) return Precipitation.LIGHT;
        return Precipitation.HEAVY;
    }

    static UpstreamLevel discretizeUpstream(double cm) {
        if (cm < 100)  return UpstreamLevel.LOW;
        if (cm < 300)  return UpstreamLevel.MEDIUM;
        return UpstreamLevel.HIGH;
    }

    static Season getSeason(Month month) {
        return switch (month) {
            case MARCH, APRIL, MAY         -> Season.SPRING;
            case JUNE, JULY, AUGUST        -> Season.SUMMER;
            case SEPTEMBER, OCTOBER, NOVEMBER -> Season.AUTUMN;
            default                        -> Season.WINTER;
        };
    }

    /**
     * Schätzt Bodenfeuchte aus Niederschlag und Jahreszeit.
     * Diese Heuristik ersetzt fehlende direkte Messwerte.
     *
     * Logik: Im Frühling + hoher Niederschlag → SATURATED (Schneeschmelze)
     *         Sommer + kein Niederschlag      → DRY
     *         Rest                            → MOIST
     */
    static SoilMoisture estimateSoilMoisture(DailyProfile profile) {
        Season season = getSeason(profile.date().getMonth());
        double precip = profile.totalPrecipMm();

        if (season == Season.SPRING && precip > 5) return SoilMoisture.SATURATED;
        if (season == Season.WINTER && precip > 3) return SoilMoisture.SATURATED;
        if (precip < 1 && (season == Season.SUMMER || season == Season.AUTUMN))
            return SoilMoisture.DRY;
        return SoilMoisture.MOIST;
    }

    // ── CPT-Normalisierung ────────────────────────────────────────────────────

    /** Normalisiert 3D-Zählerarray zu Wahrscheinlichkeiten mit Laplace-Glättung. */
    private static double[][][] normalizeCpt3(double[][][] counts, double alpha) {
        int d1 = counts.length, d2 = counts[0].length, d3 = counts[0][0].length;
        double[][][] cpt = new double[d1][d2][d3];
        for (int i = 0; i < d1; i++) {
            for (int j = 0; j < d2; j++) {
                double sum = alpha * d3;
                for (int k = 0; k < d3; k++) sum += counts[i][j][k];
                for (int k = 0; k < d3; k++)
                    cpt[i][j][k] = (counts[i][j][k] + alpha) / sum;
            }
        }
        return cpt;
    }

    // ── Diagnose ─────────────────────────────────────────────────────────────

    private void printCptSoil() {
        System.out.println("  CPT P(Bodenfeuchte | Niederschlag=HEAVY, Jahreszeit):");
        int heavyIdx = Precipitation.HEAVY.ordinal();
        for (Season s : Season.values()) {
            double[] row = cptSoil[heavyIdx][s.ordinal()];
            System.out.printf("    %-8s → DRY=%.2f  MOIST=%.2f  SATURATED=%.2f%n",
                s, row[0], row[1], row[2]);
        }
    }

    private void printCptRisk() {
        System.out.println("  CPT P(FloodRisk | Upstream=HIGH, Bodenfeuchte):");
        int highIdx = UpstreamLevel.HIGH.ordinal();
        for (SoilMoisture m : SoilMoisture.values()) {
            double[] row = cptRisk[highIdx][m.ordinal()];
            System.out.printf("    %-12s → NORMAL=%.2f  ERHOHT=%.2f  GEFAHR=%.2f%n",
                m, row[0], row[1], row[2]);
        }
    }

    private void checkTrained() {
        if (!trained) throw new IllegalStateException("BayesNet nicht trainiert.");
    }
}
