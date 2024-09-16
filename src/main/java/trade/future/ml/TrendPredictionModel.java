package trade.future.ml;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;

import java.util.List;
import java.util.logging.Logger;

public class TrendPredictionModel extends MLModel {
    private static final Logger logger = Logger.getLogger(TrendPredictionModel.class.getName());
    private int trendPeriod;

    public TrendPredictionModel(List<Indicator<Num>> indicators, int trendPeriod) {
        super(indicators);
        this.trendPeriod = trendPeriod;
    }

    @Override
    public void train(BarSeries series, int trainSize) {
        logger.info("트렌드 예측 모델 훈련 시작. 트렌드 기간: " + trendPeriod);
        super.train(series, trainSize);
    }

    @Override
    public int predict(List<Indicator<Num>> indicators, int index) {
        int prediction = super.predict(indicators, index);
        String trendPrediction = prediction == 1 ? "상승 트렌드 유지" : "하락 트렌드 유지 또는 반전";
        //logger.info("트렌드 예측 결과: " + trendPrediction);
        return prediction;
    }

    public String getTrendStrength(BarSeries series, int index) {
        if (index < trendPeriod) {
            return "트렌드 강도를 계산하기에 충분한 데이터가 없습니다.";
        }

        double startPrice = series.getBar(index - trendPeriod).getClosePrice().doubleValue();
        double endPrice = series.getBar(index).getClosePrice().doubleValue();
        double trendStrength = (endPrice - startPrice) / startPrice * 100;

        String strength;
        if (Math.abs(trendStrength) < 1) {
            strength = "약함";
        } else if (Math.abs(trendStrength) < 5) {
            strength = "중간";
        } else {
            strength = "강함";
        }

        String direction = trendStrength > 0 ? "상승" : "하락";
        return String.format("%s 트렌드 (강도: %s, 변화율: %.2f%%)", direction, strength, trendStrength);
    }

    @Override
    public String explainPrediction(List<Indicator<Num>> indicators, int index) {
        String baseExplanation = super.explainPrediction(indicators, index);
        String trendStrength = getTrendStrength(indicators.get(0).getBarSeries(), index);

        return baseExplanation.substring(0, baseExplanation.length() - 1) +
                ", \"trend_strength\": \"" + trendStrength + "\"}";
    }
}