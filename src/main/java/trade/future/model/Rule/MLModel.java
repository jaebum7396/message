package trade.future.model.Rule;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.BaseVector;
import smile.data.vector.DoubleVector;
import smile.data.vector.IntVector;
import smile.regression.RandomForest;

import java.util.ArrayList;
import java.util.List;

public class MLModel {
    private RandomForest model;

    public MLModel() {
        // 모델 초기화
    }

    public void train(BarSeries series) {
        List<double[]> features = new ArrayList<>();
        List<Integer> labels = new ArrayList<>();

        // 특성 추출
        RSIIndicator rsi = new RSIIndicator(new ClosePriceIndicator(series), 14);
        MACDIndicator macd = new MACDIndicator(new ClosePriceIndicator(series));
        BollingerBandsMiddleIndicator bbm = new BollingerBandsMiddleIndicator(new ClosePriceIndicator(series));

        for (int i = 30; i < series.getBarCount() - 1; i++) {
            double[] feature = new double[3];
            feature[0] = rsi.getValue(i).doubleValue();
            feature[1] = macd.getValue(i).doubleValue();
            feature[2] = bbm.getValue(i).doubleValue();
            features.add(feature);

            // 간단한 레이블링: 다음 봉의 종가가 현재 봉보다 높으면 1, 아니면 0
            int label = series.getBar(i + 1).getClosePrice().isGreaterThan(series.getBar(i).getClosePrice()) ? 1 : 0;
            labels.add(label);
        }

        // DataFrame 생성
        double[][] X = features.toArray(new double[0][]);
        int[] y = labels.stream().mapToInt(Integer::intValue).toArray();

        BaseVector[] vectors = new BaseVector[4];
        vectors[0] = DoubleVector.of("RSI", getColumn(X, 0));
        vectors[1] = DoubleVector.of("MACD", getColumn(X, 1));
        vectors[2] = DoubleVector.of("BBM", getColumn(X, 2));
        vectors[3] = IntVector.of("Label", y);

        DataFrame df = DataFrame.of(vectors);

        // 모델 학습
        Formula formula = Formula.lhs("Label");
        this.model = RandomForest.fit(formula, df);
    }

    public Num predict(BarSeries series, int index) {
        if (model == null) {
            throw new IllegalStateException("Model has not been trained yet");
        }

        RSIIndicator rsi = new RSIIndicator(new ClosePriceIndicator(series), 14);
        MACDIndicator macd = new MACDIndicator(new ClosePriceIndicator(series));
        BollingerBandsMiddleIndicator bbm = new BollingerBandsMiddleIndicator(new ClosePriceIndicator(series));

        double[] feature = new double[3];
        feature[0] = rsi.getValue(index).doubleValue();
        feature[1] = macd.getValue(index).doubleValue();
        feature[2] = bbm.getValue(index).doubleValue();

        // DataFrame 생성
        BaseVector[] vectors = new BaseVector[3];
        vectors[0] = DoubleVector.of("RSI", new double[]{feature[0]});
        vectors[1] = DoubleVector.of("MACD", new double[]{feature[1]});
        vectors[2] = DoubleVector.of("BBM", new double[]{feature[2]});
        DataFrame df = DataFrame.of(vectors);

        // 예측 수행
        double[] predictions = model.predict(df);
        int prediction = (int) predictions[0];  // 단일 예측 결과

        // ta4j의 Num 형식으로 변환
        return series.numOf(prediction);
    }

    // 헬퍼 메소드: 2D 배열에서 특정 열을 추출
    private double[] getColumn(double[][] array, int index) {
        return java.util.Arrays.stream(array).mapToDouble(row -> row[index]).toArray();
    }
}