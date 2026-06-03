package de.hochwasser.analysis;

import de.hochwasser.analysis.FloodPredictor.RiskLevel;
import de.hochwasser.model.DailyProfile;

import smile.classification.GaussianNaiveBayes;

public class SmileNaiveBayesPredictor {

    private GaussianNaiveBayes model;

    public void train(DailyProfile[] profiles) {

        double[][] X = new double[profiles.length][];
        int[] y = new int[profiles.length];

        for (int i = 0; i < profiles.length; i++) {

            X[i] = profiles[i].toFeatureVector();

            y[i] = encode(profiles[i].label());
        }

        model = new GaussianNaiveBayes(X, y);
    }

    public RiskLevel predict(DailyProfile profile) {

        int result =
                model.predict(profile.toFeatureVector());

        return decode(result);
    }

    private int encode(RiskLevel level) {
        return switch (level) {
            case NORMAL -> 0;
            case ERHOHT -> 1;
            case GEFAHR -> 2;
        };
    }

    private RiskLevel decode(int value) {
        return switch (value) {
            case 0 -> RiskLevel.NORMAL;
            case 1 -> RiskLevel.ERHOHT;
            default -> RiskLevel.GEFAHR;
        };
    }
}