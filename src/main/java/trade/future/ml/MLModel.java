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
    private final double priceChangeThreshold;
    private static final int MINIMUM_DATA_POINTS = 50; // 최소 필요 데이터 포인트 수

    public MLModel(double priceChangeThreshold) {
        this.priceChangeThreshold = priceChangeThreshold;
        //logger.info("MLModel 생성. 가격 변동 임계값: " + priceChangeThreshold);
    }

    public void train(BarSeries series, List<Indicator<Num>> indicators, int trainSize) {
        try {
            // 충분한 데이터가 있는지 확인
            if (series.getBarCount() < MINIMUM_DATA_POINTS) {
                logger.warning("훈련에 필요한 데이터 포인트가 부족합니다. 필요: " + MINIMUM_DATA_POINTS + ", 가용: " + series.getBarCount());
                return;
            }

            int totalSize = series.getBarCount();
            double[][] X = new double[trainSize][indicators.size()];
            int[] y = new int[trainSize];

            // 특성(X)과 레이블(y) 데이터 준비
            for (int i = 0; i < trainSize; i++) {
                // 각 지표의 값을 특성으로 사용
                for (int j = 0; j < indicators.size(); j++) {
                    Num value = indicators.get(j).getValue(i);
                    if (value == null) {
                        logger.warning("지표 " + j + "의 " + i + "번째 인덱스에서 null 값 발견");
                        return;
                    }
                    X[i][j] = value.doubleValue();
                }

                // 다음 봉의 가격 변화를 기반으로 레이블 생성
                if (i + 1 < totalSize) {
                    double currentPrice = series.getBar(i).getClosePrice().doubleValue();
                    double nextPrice = series.getBar(i + 1).getClosePrice().doubleValue();
                    if (currentPrice == 0) {
                        logger.warning(i + "번째 인덱스에서 0의 가격 발견");
                        return;
                    }
                    double priceChangePercent = (nextPrice - currentPrice) / currentPrice * 100;

                    // 가격 변화에 따라 레이블 할당 (1: 상승, -1: 하락, 0: 유지)
                    if (priceChangePercent > priceChangeThreshold) y[i] = 1;
                    else if (priceChangePercent < -priceChangeThreshold) y[i] = -1;
                    else y[i] = 0;
                } else {
                    y[i] = 0; // 마지막 데이터 포인트
                }
            }

            // 특성 이름 생성
            String[] featureNames = IntStream.range(0, indicators.size())
                    .mapToObj(i -> "feature" + i)
                    .toArray(String[]::new);

            // DataFrame 생성 및 모델 학습
            DataFrame df = DataFrame.of(X, featureNames);
            df = df.merge(IntVector.of("y", y));

            Formula formula = Formula.lhs("y");
            this.model = RandomForest.fit(formula, df);
            logger.info("모델 학습이 성공적으로 완료되었습니다.");
        } catch (Exception e) {
            logger.severe("모델 학습 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 예측 메서드
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

    // 확률 예측 메서드
    public double[] predictProbabilities(List<Indicator<Num>> indicators, int index) {
        try {
            double rawPrediction = predictRaw(indicators, index);

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

            return probabilities;
        } catch (Exception e) {
            logger.severe("확률 예측 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
            return new double[]{0, 100, 0};  // 오류 시 기본값 반환
        }
    }

    // 원시 예측 메서드
    private double predictRaw(List<Indicator<Num>> indicators, int index) {
        // 특성 데이터 준비
        double[] features = new double[indicators.size()];
        for (int i = 0; i < indicators.size(); i++) {
            features[i] = indicators.get(i).getValue(index).doubleValue();
        }

        // 특성 이름 생성
        String[] featureNames = IntStream.range(0, indicators.size())
                .mapToObj(i -> "feature" + i)
                .toArray(String[]::new);

        // DataFrame 생성 및 예측
        double[][] featuresArray = new double[][]{features};
        DataFrame df = DataFrame.of(featuresArray, featureNames);

        return model.predict(df)[0];
    }
}