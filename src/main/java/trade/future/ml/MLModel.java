package trade.future.ml;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.BaseVector;
import smile.data.vector.DoubleVector;
import smile.regression.RandomForest;

import java.util.ArrayList;
import java.util.List;

public class MLModel {
    private RandomForest model;
    private List<Indicator<Num>> indicators;

    public MLModel() {
        indicators = new ArrayList<>();
    }

    public void train(BarSeries series) {
        initializeIndicators(series);
        List<double[]> features = new ArrayList<>();
        List<Double> labels = new ArrayList<>();

        for (int i = 50; i < series.getBarCount() - 1; i++) {
            double[] feature = new double[indicators.size()];
            for (int j = 0; j < indicators.size(); j++) {
                feature[j] = indicators.get(j).getValue(i).doubleValue();
            }
            features.add(feature);

            // 연속적인 레이블 사용
            double futureReturn = series.getBar(i + 1).getClosePrice().dividedBy(series.getBar(i).getClosePrice()).doubleValue() - 1;
            labels.add(futureReturn);
        }

        // DataFrame 생성 및 모델 학습
        double[][] X = features.toArray(new double[0][]);
        double[] y = labels.stream().mapToDouble(Double::doubleValue).toArray();

        BaseVector[] vectors = new BaseVector[indicators.size() + 1];
        for (int i = 0; i < indicators.size(); i++) {
            vectors[i] = DoubleVector.of("Feature" + i, getColumn(X, i));
        }
        vectors[indicators.size()] = DoubleVector.of("Label", y);

        DataFrame df = DataFrame.of(vectors);
        this.model = RandomForest.fit(Formula.lhs("Label"), df);
    }

    public Num predict(BarSeries series, int index) {
        if (model == null) {
            throw new IllegalStateException("Model has not been trained yet");
        }

        double[] feature = new double[indicators.size()];
        for (int i = 0; i < indicators.size(); i++) {
            feature[i] = indicators.get(i).getValue(index).doubleValue();
        }

        // DataFrame을 생성하여 예측
        BaseVector[] vectors = new BaseVector[indicators.size()];
        for (int i = 0; i < indicators.size(); i++) {
            vectors[i] = DoubleVector.of("Feature" + i, new double[]{feature[i]});
        }
        DataFrame df = DataFrame.of(vectors);

        double[] predictions = model.predict(df);
        double prediction = predictions[0];

        // 예측값을 0과 1 사이로 정규화 (시그모이드 함수 사용)
        double normalizedPrediction = 1 / (1 + Math.exp(-prediction));
        return series.numOf(normalizedPrediction);
    }

    private void initializeIndicators(BarSeries series) {
        indicators.add(new RSIIndicator(new ClosePriceIndicator(series), 14));
        indicators.add(new RSIIndicator(new ClosePriceIndicator(series), 28));
        indicators.add(new MACDIndicator(new ClosePriceIndicator(series)));
        indicators.add(new EMAIndicator(new ClosePriceIndicator(series), 20));
        indicators.add(new EMAIndicator(new ClosePriceIndicator(series), 50));
        indicators.add(new BollingerBandsMiddleIndicator(new ClosePriceIndicator(series)));
        indicators.add(new StochasticOscillatorKIndicator(series, 14));
        indicators.add(new ATRIndicator(series, 14));
        indicators.add(new ADXIndicator(series, 14));
        indicators.add(new CCIIndicator(series, 20));
        indicators.add(new ROCIndicator(new ClosePriceIndicator(series), 10));
        indicators.add(new WilliamsRIndicator(series, 14));
        indicators.add(new CMOIndicator(new ClosePriceIndicator(series), 14));
        indicators.add(new ParabolicSarIndicator(series));
    }

    // 헬퍼 메소드
    private double[] getColumn(double[][] array, int index) {
        return java.util.Arrays.stream(array).mapToDouble(row -> row[index]).toArray();
    }
}