package trade.future.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MLDeepLearningModel {
    private static final Logger logger = Logger.getLogger(MLDeepLearningModel.class.getName());

    @Getter
    private List<Indicator<Num>> indicators;
    private MultiLayerNetwork model;
    private final double priceChangeThreshold;
    private static final int MINIMUM_DATA_POINTS = 50;
    private double[] featureImportance;
    private ObjectMapper mapper = new ObjectMapper();

    public MLDeepLearningModel(List<Indicator<Num>> indicators) {
        this(0.01, indicators);
    }

    public MLDeepLearningModel(double priceChangeThreshold, List<Indicator<Num>> indicators) {
        this.priceChangeThreshold = priceChangeThreshold;
        this.indicators = indicators;
    }

    public void train(BarSeries series, int trainSize) {
        try {
            if (series.getBarCount() < MINIMUM_DATA_POINTS) {
                logger.warning("훈련에 필요한 데이터 포인트가 부족합니다. 필요: " + MINIMUM_DATA_POINTS + ", 가용: " + series.getBarCount());
                return;
            }

            int inputSize = indicators.size();
            int outputSize = 3; // 하락, 중립, 상승

            // 신경망 구성
            MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                    .seed(123)
                    .weightInit(WeightInit.XAVIER)
                    .updater(new Adam(0.01))
                    .list()
                    .layer(new DenseLayer.Builder().nIn(inputSize).nOut(50).activation(Activation.RELU).build())
                    .layer(new DenseLayer.Builder().nOut(30).activation(Activation.RELU).build())
                    .layer(new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                            .activation(Activation.SOFTMAX).nOut(outputSize).build())
                    .build();

            model = new MultiLayerNetwork(conf);
            model.init();
            model.setListeners(new ScoreIterationListener(100));

            // 데이터 준비
            INDArray features = Nd4j.create(trainSize, inputSize);
            INDArray labels = Nd4j.create(trainSize, outputSize);

            for (int i = 0; i < trainSize; i++) {
                for (int j = 0; j < indicators.size(); j++) {
                    if (indicators.get(j) instanceof ClosePriceIndicator) {
                        continue;
                    }
                    features.putScalar(new int[]{i, j}, indicators.get(j).getValue(i).doubleValue());
                }

                if (i + 1 < series.getBarCount()) {
                    double currentPrice = series.getBar(i).getClosePrice().doubleValue();
                    double nextPrice = series.getBar(i + 1).getClosePrice().doubleValue();
                    double priceChangePercent = (nextPrice - currentPrice) / currentPrice * 100;

                    if (priceChangePercent < -priceChangeThreshold) {
                        labels.putScalar(new int[]{i, 0}, 1.0); // 하락
                    } else if (priceChangePercent > priceChangeThreshold) {
                        labels.putScalar(new int[]{i, 2}, 1.0); // 상승
                    } else {
                        labels.putScalar(new int[]{i, 1}, 1.0); // 중립
                    }
                }
            }

            // 모델 학습
            for (int epoch = 0; epoch < 100; epoch++) {
                model.fit(new DataSet(features, labels));
            }

            // 특성 중요도 계산 (간단한 방법: 첫 번째 레이어의 가중치 절대값 평균)
            INDArray weights = model.getLayer(0).getParam("W");
            this.featureImportance = weights.mean(1).toDoubleVector();

            logger.info("모델 학습이 성공적으로 완료되었습니다.");
        } catch (Exception e) {
            logger.severe("모델 학습 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public int predict(List<Indicator<Num>> indicators, int index) {
        INDArray input = Nd4j.create(1, indicators.size());
        for (int i = 0; i < indicators.size(); i++) {
            if (indicators.get(i) instanceof ClosePriceIndicator) {
                continue;
            }
            input.putScalar(new int[]{0, i}, indicators.get(i).getValue(index).doubleValue());
        }

        INDArray output = model.output(input);
        return Nd4j.argMax(output, 1).getInt(0) - 1; // -1: 하락, 0: 중립, 1: 상승
    }

    public double[] predictProbabilities(List<Indicator<Num>> indicators, int index) {
        INDArray input = Nd4j.create(1, indicators.size());
        for (int i = 0; i < indicators.size(); i++) {
            if (indicators.get(i) instanceof ClosePriceIndicator) {
                continue;
            }
            input.putScalar(new int[]{0, i}, indicators.get(i).getValue(index).doubleValue());
        }

        INDArray output = model.output(input);
        return output.toDoubleVector();
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

            return mapper.writeValueAsString(explanation);
        } catch (Exception e) {
            logger.severe("예측 설명 생성 중 오류 발생: " + e.getMessage());
            return "{\"error\": \"예측 설명 생성 실패\"}";
        }
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

    private double roundToFourDecimals(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}