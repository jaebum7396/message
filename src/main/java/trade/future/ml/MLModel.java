package trade.future.ml;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
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

public class MLModel {
    private static final Logger logger = Logger.getLogger(MLModel.class.getName());
    @Getter
    List<Indicator<Num>> indicators;
    private RandomForest model;
    private final double priceChangeThreshold;
    private static final int MINIMUM_DATA_POINTS = 50;
    private double[] featureImportance;
    private ObjectMapper mapper = new ObjectMapper();

    public MLModel(List<Indicator<Num>> indicators) {
        this(0.01, indicators); // 기본값 설정
    }

    public MLModel(double priceChangeThreshold, List<Indicator<Num>> indicators) {
        this.priceChangeThreshold = priceChangeThreshold;
        this.indicators = indicators;
    }

    // 특성 이름과 중요도를 함께 저장하기 위한 내부 클래스
    private static class FeatureImportance {
        private final String name;
        private final double importance;

        public FeatureImportance(String name, double importance) {
            this.name = name;
            this.importance = importance;
        }

        public String getName() {
            return name;
        }

        public double getImportance() {
            return importance;
        }
    }

    public void train(BarSeries series, int trainSize) {
        try {
            if (series.getBarCount() < MINIMUM_DATA_POINTS) {
                logger.warning("훈련에 필요한 데이터 포인트가 부족합니다. 필요: " + MINIMUM_DATA_POINTS + ", 가용: " + series.getBarCount());
                return;
            }

            int totalSize = series.getBarCount();
            double[][] X = new double[trainSize][indicators.size()];
            int[] y = new int[trainSize];

            for (int i = 0; i < trainSize; i++) {
                for (int j = 0; j < indicators.size(); j++) {
                    if (indicators.get(j).equals(ClosePriceIndicator.class)) {
                        //logger.warning("ClosePriceIndicator는 지표로 사용할 수 없습니다.");
                        continue;
                    }
                    Num value = indicators.get(j).getValue(i);
                    if (value == null) {
                        logger.warning("지표 " + j + "의 " + i + "번째 인덱스에서 null 값 발견");
                        return;
                    }
                    X[i][j] = value.doubleValue();
                }

                if (i + 1 < totalSize) {
                    double currentPrice = series.getBar(i).getClosePrice().doubleValue();
                    double nextPrice = series.getBar(i + 1).getClosePrice().doubleValue();
                    if (currentPrice == 0) {
                        logger.warning(i + "번째 인덱스에서 0의 가격 발견");
                        return;
                    }
                    double feeRate = 0.0005;

                    double priceChangePercent = (nextPrice - currentPrice) / currentPrice * 100;

                    if (priceChangePercent > (priceChangeThreshold + feeRate)) y[i] = 1;
                    else if (priceChangePercent < -(priceChangeThreshold + feeRate)) y[i] = -1;
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

            this.featureImportance = model.importance();

            // 특성 이름과 중요도를 매핑하고 내림차순으로 정렬
            String[] indicatorNames = indicators.stream()
                    .map(indicator -> indicator.getClass().getSimpleName())
                    .toArray(String[]::new);

            FeatureImportance[] sortedImportance = IntStream.range(0, featureImportance.length)
                    .mapToObj(i -> new FeatureImportance(indicatorNames[i], featureImportance[i]))
                    .sorted(Comparator.comparingDouble(FeatureImportance::getImportance).reversed())
                    .toArray(FeatureImportance[]::new);

            // 정렬된 특성 중요도 로깅
            StringBuilder sb = new StringBuilder("특성 중요도 (내림차순):\n");
            for (FeatureImportance fi : sortedImportance) {
                sb.append(String.format("%s: %.4f\n", fi.getName(), fi.getImportance()));
            }
            logger.info(sb.toString());

            logger.info("모델 학습이 성공적으로 완료되었습니다.");
        } catch (Exception e) {
            logger.severe("모델 학습 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public int predict(List<Indicator<Num>> indicators, int index) {
        double rawPrediction = predictRaw(indicators, index);
        double[] probabilities = predictProbabilities(indicators, index);

        //logger.info(String.format("Raw prediction: %.4f", rawPrediction));
        /*logger.info(String.format("Probabilities - Down: %.4f, Neutral: %.4f, Up: %.4f",
                probabilities[0], probabilities[1], probabilities[2]));*/

        //logCurrentFeatureValues(indicators, index);
        //logTopFeatures(indicators, 5);

        int finalPrediction;
        if (rawPrediction > 0.5) {
            finalPrediction = 1;
        } else if (rawPrediction < -0.5) {
            finalPrediction = -1;
        } else {
            finalPrediction = 0;
        }

        //logger.info("Final prediction: " + finalPrediction);
        return finalPrediction;
    }

    public double[] predictProbabilities(List<Indicator<Num>> indicators, int index) {
        try {
            double rawPrediction = predictRaw(indicators, index);

            double[] probabilities = new double[3];
            double expNeg = Math.exp(-rawPrediction);
            double expZero = Math.exp(0);
            double expPos = Math.exp(rawPrediction);
            double sum = expNeg + expZero + expPos;

            probabilities[0] = expNeg / sum;
            probabilities[1] = expZero / sum;
            probabilities[2] = expPos / sum;

            return probabilities;
        } catch (Exception e) {
            logger.severe("확률 예측 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
            return new double[]{0, 1, 0};
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

    public double[] getFeatureImportance() {
        return featureImportance;
    }

    public Map<String, Double> getFeatureImportanceMap(List<Indicator<Num>> indicators) {
        Map<String, Double> importanceMap = new HashMap<>();
        for (int i = 0; i < indicators.size(); i++) {
            importanceMap.put(indicators.get(i).getClass().getSimpleName(), featureImportance[i]);
        }
        return importanceMap;
    }

    public void logTopFeatures(List<Indicator<Num>> indicators, int topN) {
        Map<String, Double> importanceMap = getFeatureImportanceMap(indicators);
        List<Map.Entry<String, Double>> sortedFeatures = importanceMap.entrySet()
                .stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topN)
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder("Top " + topN + " important features:\n");
        for (Map.Entry<String, Double> entry : sortedFeatures) {
            sb.append(entry.getKey()).append(": ").append(String.format("%.4f", entry.getValue())).append("\n");
        }
        logger.info(sb.toString());
    }

    public void logCurrentFeatureValues(List<Indicator<Num>> indicators, int index) {
        StringBuilder sb = new StringBuilder("Current feature values:\n");
        for (int i = 0; i < indicators.size(); i++) {
            String featureName = indicators.get(i).getClass().getSimpleName();
            double value = indicators.get(i).getValue(index).doubleValue();
            sb.append(String.format("%s: %.4f\n", featureName, value));
        }
        logger.info(sb.toString());
    }

    public String explainPrediction(List<Indicator<Num>> indicators, int index) {
        try {
            double rawPrediction = predictRaw(indicators, index);
            double[] probabilities = predictProbabilities(indicators, index);
            Map<String, Double> importanceMap = getFeatureImportanceMap(indicators);

            List<Map.Entry<String, Double>> topFeatures = importanceMap.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(3)
                    .collect(Collectors.toList());

            Map<String, Object> explanation = new HashMap<>();
            explanation.put("raw_prediction", roundToFourDecimals(rawPrediction));

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

            return mapper.writeValueAsString(explanation);
        } catch (JsonProcessingException e) {
            logger.severe("JSON 생성 중 오류 발생: " + e.getMessage());
            return "{\"error\": \"JSON 생성 실패\"}";
        }
    }

    private double roundToFourDecimals(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

}