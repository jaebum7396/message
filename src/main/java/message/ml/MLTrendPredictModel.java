package message.ml;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.IntVector;
import smile.regression.RandomForest;

import java.util.List;
import java.util.logging.Logger;
import java.util.stream.IntStream;

public class MLTrendPredictModel extends MLModel {
    private static final Logger logger = Logger.getLogger(MLTrendPredictModel.class.getName());
    private static final int TREND_WINDOW = 3; // Number of candles to define a trend

    public MLTrendPredictModel(List<Indicator<Num>> indicators) {
        super(indicators);
    }

    @Override
    public void train(BarSeries series, int trainSize) {
        if (series.getBarCount() < MINIMUM_DATA_POINTS) {
            logger.warning("훈련에 필요한 데이터 포인트가 부족합니다. 필요: " + MINIMUM_DATA_POINTS + ", 가용: " + series.getBarCount());
            return;
        }

        double[][] features = new double[series.getBarCount() - TREND_WINDOW][indicators.size()];
        for (int i = TREND_WINDOW; i < series.getBarCount(); i++) {
            double[] preprocessedData = preprocessIndicators(indicators, i);
            if (preprocessedData == null) {
                logger.severe("전처리 중 오류 발생");
                return;
            }
            features[i - TREND_WINDOW] = preprocessedData;
        }

        int[] y = calculateTrendLabels(series);

        String[] featureNames = IntStream.range(0, indicators.size())
                .mapToObj(i -> "feature" + i)
                .toArray(String[]::new);

        DataFrame df = DataFrame.of(features, featureNames);
        df = df.merge(IntVector.of("y", y));

        Formula formula = Formula.lhs("y");
        this.model = RandomForest.fit(formula, df);

        this.featureImportance = model.importance();

        logger.info("추세 예측 모델 학습이 성공적으로 완료되었습니다.");
    }

    private int[] calculateTrendLabels(BarSeries series) {
        int[] y = new int[series.getBarCount() - TREND_WINDOW];
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        for (int i = TREND_WINDOW; i < series.getBarCount(); i++) {
            double startPrice = closePrice.getValue(i - TREND_WINDOW).doubleValue();
            double endPrice = closePrice.getValue(i).doubleValue();

            if (endPrice > startPrice) {
                y[i - TREND_WINDOW] = 1; // Uptrend
            } else if (endPrice < startPrice) {
                y[i - TREND_WINDOW] = -1; // Downtrend
            } else {
                y[i - TREND_WINDOW] = 0; // No clear trend
            }
        }
        return y;
    }

    @Override
    public double[] predictProbabilities(List<Indicator<Num>> indicators, int index) {
        try {
            double[] features = preprocessIndicators(indicators, index);
            if (features == null) {
                logger.severe("특성 전처리 중 오류 발생");
                return new double[]{0.5, 0.5}; // 기본값으로 50% 확률 반환
            }

            String[] featureNames = IntStream.range(0, indicators.size())
                    .mapToObj(i -> "feature" + i)
                    .toArray(String[]::new);

            DataFrame df = DataFrame.of(new double[][]{features}, featureNames);

            double[] rawProbabilities = model.predict(df);

            // 추세 유지 확률과 추세 반전 확률 계산
            double maintainProb = rawProbabilities[1] + Math.max(rawProbabilities[0], rawProbabilities[2]);
            double reverseProb = 1 - maintainProb;

            // 추가적인 분석을 통한 확률 조정
            boolean currentUptrend = isUptrend(indicators, index);
            double macdValue = getMACDValue(indicators, index);
            double adxValue = getADXValue(indicators, index);

            if ((currentUptrend && macdValue > 0 && adxValue > 25) ||
                    (!currentUptrend && macdValue < 0 && adxValue > 25)) {
                // 현재 추세가 강할 경우, 유지 확률을 약간 증가
                maintainProb = Math.min(1, maintainProb * 1.1);
                reverseProb = 1 - maintainProb;
            }

            return new double[]{maintainProb, reverseProb};
        } catch (Exception e) {
            logger.severe("확률 예측 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
            return new double[]{0.5, 0.5}; // 오류 발생 시 기본값으로 50% 확률 반환
        }
    }

    @Override
    public int predict(List<Indicator<Num>> indicators, int index) {
        double[] probabilities = predictProbabilities(indicators, index);
        double maintainProb = probabilities[0];
        double reverseProb = probabilities[1];

        if (maintainProb > 0.6) {
            return isUptrend(indicators, index) ? 1 : -1; // 현재 추세 유지
        } else if (reverseProb > 0.6) {
            return isUptrend(indicators, index) ? -1 : 1; // 현재 추세 반전
        } else {
            return 0; // 불분명
        }
    }

    private double getIndicatorValue(List<Indicator<Num>> indicators, String indicatorName, int index) {
        return indicators.stream()
                .filter(i -> i.getClass().getSimpleName().equals(indicatorName))
                .findFirst()
                .map(i -> i.getValue(index).doubleValue())
                .orElse(0.0);
    }
}