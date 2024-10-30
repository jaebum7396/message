package message.ml;

import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MLDeepLearningModel extends MLModel {
    private static final Logger logger = Logger.getLogger(MLDeepLearningModel.class.getName());
    private MultiLayerNetwork model;
    private boolean isModelTrained = false;

    public MLDeepLearningModel(List<Indicator<Num>> indicators) {
        super(indicators);
    }

    public MLDeepLearningModel(double priceChangeThreshold, List<Indicator<Num>> indicators) {
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
            int inputSize = indicators.size();
            INDArray features = Nd4j.zeros(trainSize, inputSize);
            INDArray labels = Nd4j.zeros(trainSize, 3);

            // 데이터 준비 및 검증
            for (int i = 0; i < trainSize; i++) {
                for (int j = 0; j < inputSize; j++) {
                    Num value = indicators.get(j).getValue(i);
                    if (value == null) {
                        logger.warning("지표 " + j + "의 " + i + "번째 인덱스에서 null 값 발견");
                        return;
                    }
                    double doubleValue = value.doubleValue();
                    if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
                        logger.warning("지표 " + j + "의 " + i + "번째 인덱스에서 NaN 또는 Infinite 값 발견");
                        doubleValue = 0.0; // 또는 다른 적절한 기본값
                    }
                    features.putScalar(new int[]{i, j}, doubleValue);
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

                    if (priceChangePercent > (priceChangeThreshold + feeRate))
                        labels.putScalar(new int[]{i, 2}, 1.0);
                    else if (priceChangePercent < -(priceChangeThreshold + feeRate))
                        labels.putScalar(new int[]{i, 0}, 1.0);
                    else
                        labels.putScalar(new int[]{i, 1}, 1.0);
                } else {
                    labels.putScalar(new int[]{i, 1}, 1.0);
                }
            }

            // 데이터 정규화
            features = normalizeFeatures(features);

            // 모델 구성
            MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                    .seed(123)
                    .weightInit(WeightInit.XAVIER)
                    .updater(new Adam(0.001))  // 학습률 조정
                    .l2(1e-4)  // L2 정규화 추가
                    .list()
                    .layer(0, new DenseLayer.Builder().nIn(inputSize).nOut(50).activation(Activation.RELU).dropOut(0.5).build())
                    .layer(1, new DenseLayer.Builder().nOut(30).activation(Activation.RELU).dropOut(0.5).build())
                    .layer(2, new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                            .activation(Activation.SOFTMAX)
                            .nOut(3).build())
                    .build();

            model = new MultiLayerNetwork(conf);
            model.init();

            // 학습 과정 모니터링
            double bestLoss = Double.MAX_VALUE;
            int patience = 10;
            int noImprovement = 0;

            for (int i = 0; i < 1000; i++) {  // 최대 1000 에폭
                model.fit(new DataSet(features, labels));
                double loss = model.score();
                //logger.info("에폭 " + i + ": 손실 = " + loss);

                if (loss < bestLoss) {
                    bestLoss = loss;
                    noImprovement = 0;
                } else {
                    noImprovement++;
                }

                if (noImprovement >= patience) {
                    logger.info("조기 종료: " + patience + " 에폭 동안 개선 없음");
                    break;
                }

                // NaN 체크
                if (Double.isNaN(loss)) {
                    logger.warning("NaN 손실 발생. 학습 중단.");
                    return;
                }
            }

            isModelTrained = true;
            logger.info("딥러닝 모델 학습이 성공적으로 완료되었습니다.");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "딥러닝 모델 학습 중 오류 발생", e);
            isModelTrained = false;
        }
    }

    @Override
    public int predict(List<Indicator<Num>> indicators, int index) {
        double[] probabilities = predictProbabilities(indicators, index);
        int prediction = probabilities[2] > probabilities[0] ?
                (probabilities[2] > 0.5 ? 1 : 0) :
                (probabilities[0] > 0.5 ? -1 : 0);

        // 추가적인 지표 분석
        boolean isUptrend = isUptrend(indicators, index);
        double macdValue = getMACDValue(indicators, index);
        double adxValue = getADXValue(indicators, index);

        // 최종 예측 조정
        if (isUptrend && macdValue > 0 && adxValue > 25) {
            prediction = Math.min(prediction + 1, 1);
        } else if (!isUptrend && macdValue < 0 && adxValue > 25) {
            prediction = Math.max(prediction - 1, -1);
        }

        return prediction;
    }

    @Override
    public double[] predictProbabilities(List<Indicator<Num>> indicators, int index) {
        try {
            INDArray features = Nd4j.zeros(1, indicators.size());
            for (int i = 0; i < indicators.size(); i++) {
                double value = indicators.get(i).getValue(index).doubleValue();
                if (Double.isNaN(value) || Double.isInfinite(value)) {
                    logger.warning("지표 " + i + "에서 NaN 또는 Infinite 값이 발견되었습니다.");
                    value = 0.0;  // 또는 다른 적절한 기본값
                }
                features.putScalar(new int[]{0, i}, value);
            }

            // 입력 데이터 정규화
            features = normalizeFeatures(features);

            INDArray output = model.output(features);
            double[] probabilities = output.toDoubleVector();

            // NaN 체크 및 처리
            for (int i = 0; i < probabilities.length; i++) {
                if (Double.isNaN(probabilities[i]) || Double.isInfinite(probabilities[i])) {
                    logger.warning("예측 결과에 NaN 또는 Infinite 값이 포함되어 있습니다.");
                    probabilities[i] = 1.0 / probabilities.length;  // 균등 확률 분포로 대체
                }
            }

            log.info("probabilities: " + probabilities[0] + ", " + probabilities[1] + ", " + probabilities[2]);

            return probabilities;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "확률 예측 중 오류 발생", e);
            return new double[]{1.0/3, 1.0/3, 1.0/3};  // 균등 확률 분포 반환
        }
    }

    private INDArray normalizeFeatures(INDArray features) {
        INDArray mean = features.mean(0);
        INDArray std = features.std(0);
        std.addi(1e-5);  // 0으로 나누는 것을 방지
        return features.subiRowVector(mean).diviRowVector(std);
    }

    private boolean hasNaN(MultiLayerNetwork network) {
        for (org.deeplearning4j.nn.api.Layer layer : network.getLayers()) {
            INDArray params = layer.params();
            if (params.isNaN().sumNumber().doubleValue() > 0) {
                return true;
            }
        }
        return false;
    }

    // MLModel에서 상속받은 다른 메서드들 (isUptrend, getMACDValue, getADXValue 등)은 그대로 사용
}