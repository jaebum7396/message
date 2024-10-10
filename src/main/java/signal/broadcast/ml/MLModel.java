package signal.broadcast.ml;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.bollinger.PercentBIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.volume.ChaikinMoneyFlowIndicator;
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
    RandomForest model;
    protected final double priceChangeThreshold;
    protected static final int MINIMUM_DATA_POINTS = 50;
    double[] featureImportance;
    ObjectMapper mapper = new ObjectMapper();

    public MLModel(List<Indicator<Num>> indicators) {
        this(0.01, indicators);
    }

    public MLModel(double priceChangeThreshold, List<Indicator<Num>> indicators) {
        this.priceChangeThreshold = priceChangeThreshold;
        this.indicators = indicators;
    }

    double[] preprocessIndicators(List<Indicator<Num>> indicators, int index) {
        double[] preprocessedData = new double[indicators.size()];
        for (int j = 0; j < indicators.size(); j++) {
            if (indicators.get(j) instanceof ClosePriceIndicator) {
                continue;
            }
            Num value = indicators.get(j).getValue(index);
            if (value == null) {
                logger.warning("지표 " + indicators.get(j).getClass().getSimpleName() + "의 " + index + "번째 인덱스에서 null 값 발견");
                preprocessedData[j] = 0; // null 값을 0으로 대체
                continue;
            }
            double normalizedValue = normalizeIndicator(indicators.get(j), value.doubleValue());
            if (Double.isNaN(normalizedValue)) {
                logger.warning("지표 " + indicators.get(j).getClass().getSimpleName() + "의 " + index + "번째 인덱스에서 NaN 값 발견. 원래 값: " + value.doubleValue());
                preprocessedData[j] = 0; // NaN 값을 0으로 대체
            } else {
                preprocessedData[j] = clamp(normalizedValue, -1, 1);
            }
        }
        return preprocessedData;
    }

    private double normalizeIndicator(Indicator<Num> indicator, double value) {
        if (indicator instanceof ADXIndicator) {
            return (value / 100.0) * 2 - 1;  // ADX를 -1에서 1 사이로 정규화
        } else if (indicator instanceof MACDIndicator) {
            return Math.tanh(value / 100.0);  // MACD는 tanh 함수를 사용하여 -1에서 1 사이로 정규화
        } else if (indicator instanceof EMAIndicator) {
            // EMA의 경우, 상대적 변화율을 사용
            double avgValue = getAverageValue(indicator);
            return (value - avgValue) / avgValue;
        } else if (indicator instanceof BollingerBandsMiddleIndicator
                || indicator instanceof BollingerBandsUpperIndicator
                || indicator instanceof BollingerBandsLowerIndicator) {
            // Bollinger Bands의 경우, 종가 대비 상대적 위치로 정규화
            ClosePriceIndicator closePrice = new ClosePriceIndicator(indicator.getBarSeries());
            return (value - closePrice.getValue(indicator.getBarSeries().getEndIndex()).doubleValue())
                    / closePrice.getValue(indicator.getBarSeries().getEndIndex()).doubleValue();
        } else if (indicator instanceof RSIIndicator || indicator instanceof StochasticOscillatorKIndicator) {
            return (value / 100.0) * 2 - 1;  // RSI와 Stochastic %K는 0-100 범위를 -1에서 1로 변환
        } else if (indicator instanceof PercentBIndicator) {
            return (value - 0.5) * 2;  // %B는 0-1 범위를 -1에서 1로 변환
        } else if (indicator instanceof ATRIndicator) {
            // ATR의 경우, 최근 N일 평균 ATR로 정규화
            return Math.tanh(value / getAverageValue(indicator));
        } else if (indicator instanceof ChaikinMoneyFlowIndicator) {
            return Math.tanh(value * 3);  // CMF를 -1에서 1 사이로 정규화
        } else if (indicator instanceof PlusDIIndicator || indicator instanceof MinusDIIndicator) {
            return (value / 100.0) * 2 - 1;  // +DI와 -DI를 -1에서 1로 변환
        } else {
            // 기타 지표들에 대해서는 간단한 스케일링 적용
            return Math.tanh(value / 100.0);  // tanh를 사용하여 -1에서 1 사이로 제한
        }
    }

    public void printNormalizationStats(List<Indicator<Num>> indicators, double[][] preprocessedData) {
        for (int j = 0; j < preprocessedData[0].length; j++) {
            double min = Double.MAX_VALUE;
            double max = Double.MIN_VALUE;
            double sum = 0;
            double sumSquares = 0;
            int count = 0;
            int nanCount = 0;

            for (double[] preprocessedDatum : preprocessedData) {
                double value = preprocessedDatum[j];
                if (Double.isNaN(value)) {
                    nanCount++;
                    continue;
                }
                min = Math.min(min, value);
                max = Math.max(max, value);
                sum += value;
                sumSquares += value * value;
                count++;
            }

            if (count == 0) {
                logger.warning("특성 " + j + "에 대한 데이터가 없습니다.");
                continue;
            }

            double mean = sum / count;
            double variance = (sumSquares / count) - (mean * mean);
            double stdDev = Math.sqrt(variance);

            String indicatorName = indicators.get(j).getClass().getSimpleName();
            logger.info(String.format("%s (Feature %d) - Min: %.4f, Max: %.4f, Mean: %.4f, StdDev: %.4f, NaN count: %d",
                    indicatorName, j, min, max, mean, stdDev, nanCount));
        }
    }

    public void printHistogram(double[][] preprocessedData, int featureIndex, int bins) {
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for (double[] row : preprocessedData) {
            min = Math.min(min, row[featureIndex]);
            max = Math.max(max, row[featureIndex]);
        }

        int[] histogram = new int[bins];
        double binSize = (max - min) / bins;

        for (double[] row : preprocessedData) {
            int bin = (int) ((row[featureIndex] - min) / binSize);
            if (bin == bins) bin--;
            histogram[bin]++;
        }

        for (int i = 0; i < bins; i++) {
            System.out.printf("[%.2f, %.2f): %s\n",
                    min + i * binSize, min + (i + 1) * binSize, "*".repeat(histogram[i]));
        }
    }

    public void printCorrelationMatrix(double[][] preprocessedData) {
        int features = preprocessedData[0].length;
        double[][] correlationMatrix = new double[features][features];

        for (int i = 0; i < features; i++) {
            for (int j = i; j < features; j++) {
                double correlation = calculateCorrelation(preprocessedData, i, j);
                correlationMatrix[i][j] = correlation;
                correlationMatrix[j][i] = correlation;
            }
        }

        for (int i = 0; i < features; i++) {
            for (int j = 0; j < features; j++) {
                System.out.printf("%.2f ", correlationMatrix[i][j]);
            }
            System.out.println();
        }
    }

    private double calculateCorrelation(double[][] data, int feature1, int feature2) {
        double sum1 = 0, sum2 = 0, sum1Sq = 0, sum2Sq = 0, pSum = 0;
        int n = data.length;

        for (double[] row : data) {
            double x = row[feature1];
            double y = row[feature2];
            sum1 += x;
            sum2 += y;
            sum1Sq += x * x;
            sum2Sq += y * y;
            pSum += x * y;
        }

        double num = pSum - (sum1 * sum2 / n);
        double den = Math.sqrt((sum1Sq - sum1 * sum1 / n) * (sum2Sq - sum2 * sum2 / n));

        return num / den;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double getAverageValue(Indicator<Num> indicator) {
        // 지표의 평균값 계산 (예: 최근 100개 값의 평균)
        int count = Math.min(indicator.getBarSeries().getBarCount(), 100);
        double sum = 0;
        for (int i = indicator.getBarSeries().getBarCount() - count; i < indicator.getBarSeries().getBarCount(); i++) {
            sum += indicator.getValue(i).doubleValue();
        }
        return sum / count;
    }

    public void train(BarSeries series, int trainSize) {
        try {
            if (series.getBarCount() < MINIMUM_DATA_POINTS) {
                logger.warning("훈련에 필요한 데이터 포인트가 부족합니다. 필요: " + MINIMUM_DATA_POINTS + ", 가용: " + series.getBarCount());
                return;
            }

            //double[][] features = preprocessIndicators(indicators, trainSize);

            double[][] features = new double[series.getBarCount()][indicators.size()];
            for (int i = 0; i < series.getBarCount(); i++) {
                double[] preprocessedData = preprocessIndicators(indicators, i);
                if (preprocessedData == null) {
                    logger.severe("전처리 중 오류 발생");
                    return;
                }
                features[i] = preprocessedData;
            }

            if (features == null) {
                logger.severe("전처리 중 오류 발생");
                return;
            }

            // 정규화 결과 확인
            //printNormalizationStats(indicators, features);
            //printHistogram(features, 0, 20);  // 첫 번째 특성의 히스토그램 출력
            //printCorrelationMatrix(features);

            int[] y = calculateLabels(series, trainSize);

            String[] featureNames = IntStream.range(0, indicators.size())
                    .mapToObj(i -> "feature" + i)
                    .toArray(String[]::new);

            DataFrame df = DataFrame.of(features, featureNames);
            df = df.merge(IntVector.of("y", y));

            Formula formula = Formula.lhs("y");
            this.model = RandomForest.fit(formula, df);

            this.featureImportance = model.importance();

            logger.info("모델 학습이 성공적으로 완료되었습니다.");
        } catch (Exception e) {
            logger.severe("모델 학습 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private int[] calculateLabels(BarSeries series, int size) {
        int[] y = new int[size];
        for (int i = 0; i < size; i++) {
            if (i + 1 < series.getBarCount()) {
                double currentPrice = series.getBar(i).getClosePrice().doubleValue();
                double nextPrice = series.getBar(i + 1).getClosePrice().doubleValue();
                if (currentPrice == 0) {
                    logger.warning(i + "번째 인덱스에서 0의 가격 발견");
                    return new int[0];
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
        return y;
    }

    public double[] predictProbabilities(List<Indicator<Num>> indicators, int index) {
        try {
            double[] features = preprocessIndicators(indicators, index);
            if (features == null) {
                logger.severe("특성 전처리 중 오류 발생");
                return new double[]{0, 1, 0};
            }

            // 정규화 결과 확인
            //printNormalizationStats(indicators, features);
            //printHistogram(features, 0, 20);  // 첫 번째 특성의 히스토그램 출력
            //printCorrelationMatrix(features);

            String[] featureNames = IntStream.range(0, indicators.size())
                    .mapToObj(i -> "feature" + i)
                    .toArray(String[]::new);

            DataFrame df = DataFrame.of(new double[][]{features}, featureNames);

            double rawPrediction = model.predict(df)[0];

            return softmax(rawPrediction);
        } catch (Exception e) {
            logger.severe("확률 예측 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
            return new double[]{0, 1, 0};
        }
    }

    private double[] softmax(double x) {
        double[] probabilities = new double[3];
        double expNeg = Math.exp(-x);
        double expZero = Math.exp(0);
        double expPos = Math.exp(x);
        double sum = expNeg + expZero + expPos;

        probabilities[0] = expNeg / sum;
        probabilities[1] = expZero / sum;
        probabilities[2] = expPos / sum;

        return probabilities;
    }

    public int predict(List<Indicator<Num>> indicators, int index) {
        double[] probabilities = predictProbabilities(indicators, index);
        double modelPrediction = probabilities[2] - probabilities[0]; // -1 to 1 range

        // 추가적인 지표 분석
        boolean isUptrend = isUptrend(indicators, index);
        double macdValue = getMACDValue(indicators, index);
        double adxValue = getADXValue(indicators, index);

        // 최종 예측 결정
        double finalPrediction = modelPrediction;
        if (isUptrend && macdValue > 0 && adxValue > 25) {
            finalPrediction += 0.2; // Slightly increase long bias
        } else if (!isUptrend && macdValue < 0 && adxValue > 25) {
            finalPrediction -= 0.2; // Slightly increase short bias
        }

        if (finalPrediction > 0.3) {
            return 1; // Long
        } else if (finalPrediction < -0.3) {
            return -1; // Short
        } else {
            return 0; // Neutral
        }
    }

    boolean isUptrend(List<Indicator<Num>> indicators, int index) {
        Optional<Indicator<Num>> shortEMA = indicators.stream()
                .filter(i -> i instanceof EMAIndicator)
                .findFirst();
        Optional<Indicator<Num>> longEMA = indicators.stream()
                .filter(i -> i instanceof EMAIndicator && i != shortEMA.orElse(null))
                .findFirst();

        if (shortEMA.isPresent() && longEMA.isPresent()) {
            return shortEMA.get().getValue(index).isGreaterThan(longEMA.get().getValue(index));
        }
        return false;
    }

    double getMACDValue(List<Indicator<Num>> indicators, int index) {
        Optional<Indicator<Num>> macd = indicators.stream()
                .filter(i -> i instanceof MACDIndicator)
                .findFirst();

        return macd.map(indicator -> indicator.getValue(index).doubleValue()).orElse(0.0);
    }

    double getADXValue(List<Indicator<Num>> indicators, int index) {
        Optional<Indicator<Num>> adx = indicators.stream()
                .filter(i -> i instanceof ADXIndicator)
                .findFirst();

        return adx.map(indicator -> indicator.getValue(index).doubleValue()).orElse(0.0);
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
            int prediction = predict(indicators, index);
            double[] probabilities = predictProbabilities(indicators, index);
            Map<String, Double> importanceMap = getFeatureImportanceMap(indicators);

            List<Map.Entry<String, Double>> topFeatures = importanceMap.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(3)
                    .collect(Collectors.toList());

            Map<String, Object> explanation = new HashMap<>();
            explanation.put("prediction", prediction);

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



    double roundToFourDecimals(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}