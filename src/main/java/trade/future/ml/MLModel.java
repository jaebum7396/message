package trade.future.ml;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.IntVector;
import smile.regression.RandomForest;

import java.util.List;
import java.util.logging.Logger;
import java.util.stream.IntStream;

public class MLModel {
    private static final Logger logger = Logger.getLogger(MLModel.class.getName());
    private RandomForest model;
    private static final double PRICE_CHANGE_THRESHOLD = 0.2; // 0.5% threshold, 필요에 따라 조정 가능

    private static final int MINIMUM_DATA_POINTS = 50; // 예시 값, 실제 필요한 최소 데이터 포인트 수에 맞게 조정

    public void train(BarSeries series, List<Indicator<Num>> indicators, int trainSize) {
        try {
            if (series.getBarCount() < MINIMUM_DATA_POINTS) {
                logger.warning("Not enough data points for training. Required: " + MINIMUM_DATA_POINTS + ", Available: " + series.getBarCount());
                return;
            }

            int totalSize = series.getBarCount();
            double[][] X = new double[trainSize][indicators.size()];
            int[] y = new int[trainSize];

            for (int i = 0; i < trainSize; i++) {
                for (int j = 0; j < indicators.size(); j++) {
                    Num value = indicators.get(j).getValue(i);
                    if (value == null) {
                        logger.warning("Null value encountered for indicator " + j + " at index " + i);
                        return;
                    }
                    X[i][j] = value.doubleValue();
                }

                if (i + 1 < totalSize) {
                    double currentPrice = series.getBar(i).getClosePrice().doubleValue();
                    double nextPrice = series.getBar(i + 1).getClosePrice().doubleValue();
                    if (currentPrice == 0) {
                        logger.warning("Zero price encountered at index " + i);
                        return;
                    }
                    double priceChangePercent = (nextPrice - currentPrice) / currentPrice * 100;

                    if (priceChangePercent > PRICE_CHANGE_THRESHOLD) y[i] = 1;
                    else if (priceChangePercent < -PRICE_CHANGE_THRESHOLD) y[i] = -1;
                    else y[i] = 0;
                } else {
                    y[i] = 0;
                }
            }

            String[] featureNames = IntStream.range(0, indicators.size())
                    .mapToObj(i -> "feature" + i)
                    .toArray(String[]::new);

            DataFrame df = DataFrame.of(X, featureNames);
            df = df.merge(IntVector.of("y", y));

            Formula formula = Formula.lhs("y");
            this.model = RandomForest.fit(formula, df);
            logger.info("Model training completed successfully.");
        } catch (Exception e) {
            logger.severe("Error during model training: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public int predict(List<Indicator<Num>> indicators, int index) {
        double prediction = predictRaw(indicators, index);
        if (prediction > 0.5) {
            return 1;  // 상승
        } else if (prediction < -0.5) {
            return -1;  // 하락
        } else {
            return 0;  // 유지
        }
    }

    public double[] predictProbabilities(List<Indicator<Num>> indicators, int index) {
        try {
            double rawPrediction = predictRaw(indicators, index);
            //logger.info("Raw prediction: " + rawPrediction);

            // 원시 예측값을 확률로 변환
            double[] probabilities = new double[3];
            if (rawPrediction > 0.5) {
                probabilities[2] = rawPrediction;
                probabilities[1] = 1 - rawPrediction;
                probabilities[0] = 0;
            } else if (rawPrediction < -0.5) {
                probabilities[0] = -rawPrediction;
                probabilities[1] = 1 + rawPrediction;
                probabilities[2] = 0;
            } else {
                probabilities[1] = 1 - Math.abs(rawPrediction);
                probabilities[0] = probabilities[2] = Math.abs(rawPrediction) / 2;
            }

            //logger.info("Estimated probabilities: " + Arrays.toString(probabilities));
            return probabilities;
        } catch (Exception e) {
            logger.severe("Error in predictProbabilities: " + e.getMessage());
            e.printStackTrace();
            return new double[]{1.0/3, 1.0/3, 1.0/3};
        }
    }

    private double predictRaw(List<Indicator<Num>> indicators, int index) {
        double[] features = new double[indicators.size()];
        for (int i = 0; i < indicators.size(); i++) {
            features[i] = indicators.get(i).getValue(index).doubleValue();
        }

        String[] featureNames = IntStream.range(0, indicators.size())
                .mapToObj(i -> "feature" + i)
                .toArray(String[]::new);

        double[][] featuresArray = new double[][]{features};
        DataFrame df = DataFrame.of(featuresArray, featureNames);

        return model.predict(df)[0];
    }
}