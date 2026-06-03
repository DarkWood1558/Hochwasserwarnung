package de.hochwasser.analysis;

import de.hochwasser.analysis.FloodPredictor.RiskLevel;
import de.hochwasser.model.DailyProfile;
import smile.clustering.KMeans;

import java.util.Arrays;

public class SmileKMeansClusterer {

    private KMeans model;
    private RiskLevel[] clusterLabels;

    public void fit(DailyProfile[] profiles) {

        double[][] data = Arrays.stream(profiles)
                .map(DailyProfile::toFeatureVector)
                .toArray(double[][]::new);

        model = KMeans.fit(data, 3);

        clusterLabels = new RiskLevel[3];

        double[] centroidLevels = new double[3];

        for (int i = 0; i < 3; i++) {
            centroidLevels[i] = model.centroids()[i][0];
        }

        Integer[] order = {0,1,2};

        Arrays.sort(order,
                (a,b) -> Double.compare(
                        centroidLevels[a],
                        centroidLevels[b]));

        clusterLabels[order[0]] = RiskLevel.NORMAL;
        clusterLabels[order[1]] = RiskLevel.ERHOHT;
        clusterLabels[order[2]] = RiskLevel.GEFAHR;
    }

    public RiskLevel predict(DailyProfile profile) {

        int cluster =
                model.predict(profile.toFeatureVector());

        return clusterLabels[cluster];
    }
}
