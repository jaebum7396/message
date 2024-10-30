package message.ml;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.IntVector;
import smile.regression.RandomForest;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class MLRewardRatioModel extends MLModel {
    private static final Logger logger = Logger.getLogger(MLRewardRatioModel.class.getName());
    private final int lookForwardPeriod = 10; // 미래를 얼마나 볼지 설정

    public MLRewardRatioModel(List<Indicator<Num>> indicators) {
        super(indicators);
    }

    public MLRewardRatioModel(double priceChangeThreshold, List<Indicator<Num>> indicators) {
        super(priceChangeThreshold, indicators);
    }

    @Override
    public void train(BarSeries series, int trainSize) {
        try {
            if (series.getBarCount() < MINIMUM_DATA_POINTS) {
                logger.warning("훈련에 필요한 데이터 포인트가 부족합니다. 필요: " + MINIMUM_DATA_POINTS + ", 가용: " + series.getBarCount());
                return;
            }

            int totalSize = series.getBarCount();
            double[][] X = new double[trainSize - lookForwardPeriod][indicators.size()];
            double[] y = new double[trainSize - lookForwardPeriod];

            for (int i = 0; i < trainSize - lookForwardPeriod; i++) {
                for (int j = 0; j < indicators.size(); j++) {
                    if (indicators.get(j) instanceof ClosePriceIndicator) {
                        continue;
                    }
                    Num value = indicators.get(j).getValue(i);
                    if (value == null) {
                        logger.warning("지표 " + j + "의 " + i + "번째 인덱스에서 null 값 발견");
                        return;
                    }
                    X[i][j] = value.doubleValue();
                }

                y[i] = calculateRewardRatio(series, i, i + lookForwardPeriod);
            }

            String[] featureNames = IntStream.range(0, indicators.size())
                    .mapToObj(i -> "feature" + i)
                    .toArray(String[]::new);

            DataFrame df = DataFrame.of(X, featureNames);
            df = df.merge(IntVector.of("y", Arrays.stream(y).mapToInt(d -> (int) Math.round(d)).toArray()));

            Formula formula = Formula.lhs("y");
            this.model = RandomForest.fit(formula, df);

            this.featureImportance = model.importance();

            logger.info("보상비 예측 모델 학습이 성공적으로 완료되었습니다.");
        } catch (Exception e) {
            logger.severe("모델 학습 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private double calculateRewardRatio(BarSeries series, int startIndex, int endIndex) {
        double startPrice = series.getBar(startIndex).getClosePrice().doubleValue();
        double endPrice = series.getBar(endIndex).getClosePrice().doubleValue();
        double maxPrice = startPrice;
        double minPrice = startPrice;

        for (int i = startIndex + 1; i <= endIndex; i++) {
            double high = series.getBar(i).getHighPrice().doubleValue();
            double low = series.getBar(i).getLowPrice().doubleValue();
            maxPrice = Math.max(maxPrice, high);
            minPrice = Math.min(minPrice, low);
        }

        double potentialProfit = Math.max(maxPrice - startPrice, startPrice - minPrice);
        double actualProfit = Math.abs(endPrice - startPrice);
        double maxLoss = Math.abs(startPrice - minPrice);

        return maxLoss > 0 ? actualProfit / maxLoss : 0;
    }

    @Override
    public double[] predictProbabilities(List<Indicator<Num>> indicators, int index) {
        try {
            double[] features = new double[indicators.size()];
            for (int i = 0; i < indicators.size(); i++) {
                features[i] = indicators.get(i).getValue(index).doubleValue();
            }

            String[] featureNames = IntStream.range(0, indicators.size())
                    .mapToObj(i -> "feature" + i)
                    .toArray(String[]::new);

            double[][] featuresArray = new double[][]{features};
            DataFrame df = DataFrame.of(featuresArray, featureNames);

            double rawPrediction = model.predict(df)[0];

            // 소프트맥스 함수를 사용하여 확률 계산
            double[] logits = new double[]{-rawPrediction, 0, rawPrediction};  // 하락, 중립, 상승에 대한 로짓
            double temperature = 100;  // 온도 매개변수 (1.0보다 작으면 더 극단적, 크면 더 부드러운 분포)

            double[] expValues = Arrays.stream(logits).map(l -> Math.exp(l / temperature)).toArray();
            double sumExpValues = Arrays.stream(expValues).sum();

            double[] probabilities = new double[3];
            for (int i = 0; i < 3; i++) {
                probabilities[i] = expValues[i] / sumExpValues;
            }

            return probabilities;
        } catch (Exception e) {
            logger.severe("확률 예측 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
            return new double[]{1.0/3, 1.0/3, 1.0/3};  // 오류 발생 시 균등 확률 반환
        }
    }

    @Override
    public String explainPrediction(List<Indicator<Num>> indicators, int index) {
        try {
            double[] probabilities = predictProbabilities(indicators, index);
            Map<String, Double> importanceMap = getFeatureImportanceMap(indicators);

            List<Map.Entry<String, Double>> topFeatures = importanceMap.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(3)
                    .collect(Collectors.toList());

            Map<String, Object> explanation = new HashMap<>();
            explanation.put("reward_ratio_prediction", model.predict(DataFrame.of(new double[][]{
                    indicators.stream().mapToDouble(i -> i.getValue(index).doubleValue()).toArray()
            }, IntStream.range(0, indicators.size()).mapToObj(i -> "feature" + i).toArray(String[]::new)))[0]);

            List<Map<String, Object>> topFeaturesList = new ArrayList<>();
            for (int i = 0; i < topFeatures.size(); i++) {
                Map<String, Object> featureMap = new HashMap<>();
                String featureName = topFeatures.get(i).getKey();
                featureMap.put("name", featureName);
                featureMap.put("importance", roundToFourDecimals(topFeatures.get(i).getValue()));
                featureMap.put("value", roundToFourDecimals(indicators.get(i).getValue(index).doubleValue()));
                topFeaturesList.add(featureMap);
            }
            explanation.put("top_features", topFeaturesList);

            explanation.put("probabilities", new HashMap<String, Double>() {{
                put("down", roundToFourDecimals(probabilities[0]));
                put("neutral", roundToFourDecimals(probabilities[1]));
                put("up", roundToFourDecimals(probabilities[2]));
            }});

            explanation.put("trend", new HashMap<String, Object>() {{
                put("is_uptrend", isUptrend(indicators, index));
                put("macd", roundToFourDecimals(getMACDValue(indicators, index)));
                put("adx", roundToFourDecimals(getADXValue(indicators, index)));
            }});

            return mapper.writeValueAsString(explanation);
        } catch (JsonProcessingException e) {
            logger.severe("JSON 생성 중 오류 발생: " + e.getMessage());
            return "{\"error\": \"JSON 생성 실패\"}";
        }
    }

    // 다른 메서드들은 MLModel에서 상속받아 그대로 사용
}