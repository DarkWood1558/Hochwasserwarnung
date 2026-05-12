package de.hochwasser.analysis;

import de.hochwasser.model.WaterLevel;

import java.util.List;

/**
 * Vorhersagemodell fuer Hochwasserereignisse.
 *
 * Geplante Funktionen:
 * - K-Means Clustering: Tagesprofile klassifizieren (normal / erhoht / kritisch)
 * - Bayesian Network: Wahrscheinlichkeit fuer Ueberschreitung Meldestufe 1/2 in 24h
 * - Cross-Validation an historischen Ereignissen (2010, 2013, 2024)
 */
public class FloodPredictor {

    public enum RiskLevel {
        NORMAL,
        ERHOHT,
        GEFAHR
    }

    /**
     * Bewertet das aktuelle Risiko basierend auf den letzten Pegelstaenden.
     *
     * @param recentLevels Pegelstaende der letzten Stunden
     * @return aktuelles Risikolevel
     */
    public RiskLevel assessRisk(List<WaterLevel> recentLevels) {
        if (recentLevels.isEmpty()) {
            return RiskLevel.NORMAL;
        }

        double latestLevel = recentLevels.get(recentLevels.size() - 1).levelCm();

        // Einfache Schwellwert-Logik als Platzhalter
        // TODO: Durch ML-Modell (Bayes-Netz) ersetzen
        if (latestLevel > 400) {
            return RiskLevel.GEFAHR;
        } else if (latestLevel > 250) {
            return RiskLevel.ERHOHT;
        } else {
            return RiskLevel.NORMAL;
        }
    }

    /**
     * Berechnet die Anstiegsrate in cm pro Stunde.
     *
     * @param recentLevels Pegelstaende (chronologisch sortiert)
     * @return Anstiegsrate in cm/h
     */
    public double calculateRateOfChange(List<WaterLevel> recentLevels) {
        if (recentLevels.size() < 2) {
            return 0.0;
        }

        WaterLevel first = recentLevels.get(0);
        WaterLevel last = recentLevels.get(recentLevels.size() - 1);

        double levelDiff = last.levelCm() - first.levelCm();
        double hoursDiff = (last.measuredAt().getEpochSecond() - first.measuredAt().getEpochSecond()) / 3600.0;

        if (hoursDiff == 0) {
            return 0.0;
        }

        return levelDiff / hoursDiff;
    }
}
