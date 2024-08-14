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

    public void train(BarSeries series, List<Indicator<Num>> indicators, int trainSize) {
        int totalSize = series.getBarCount();
        double[][] X = new double[trainSize][indicators.size()];
        int[] y = new int[trainSize];

        for (int i = 0; i < trainSize; i++) {
            for (int j = 0; j < indicators.size(); j++) {
                X[i][j] = indicators.get(j).getValue(i).doubleValue();
            }
            // 3개의 클래스로 레이블링: -1(하락), 0(유지), 1(상승)
            if (i + 1 < totalSize) {
                double priceDiff = series.getBar(i + 1).getClosePrice().doubleValue() -
                        series.getBar(i).getClosePrice().doubleValue();
                double priceChangePercent = priceDiff / series.getBar(i).getClosePrice().doubleValue() * 100;

                if (priceChangePercent > PRICE_CHANGE_THRESHOLD) y[i] = 1;
                else if (priceChangePercent < -PRICE_CHANGE_THRESHOLD) y[i] = -1;
                else y[i] = 0;
            } else {
                y[i] = 0; // 마지막 데이터 포인트는 '유지'로 가정
            }
        }

        String[] featureNames = IntStream.range(0, indicators.size())
                .mapToObj(i -> "feature" + i)
                .toArray(String[]::new);

        DataFrame df = DataFrame.of(X, featureNames);
        df = df.merge(IntVector.of("y", y));

        Formula formula = Formula.lhs("y");
        this.model = RandomForest.fit(formula, df);
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
            //logger.severe("Error in predictProbabilities: " + e.getMessage());
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