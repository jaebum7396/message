package trade.future.model.Rule;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.helpers.ConstantIndicator;
import org.ta4j.core.num.Num;
import trade.future.ml.MLModel;

public class FlexibleMLPredictionRule implements Rule {
    private final MLModel mlModel;
    private final BarSeries series;
    private final ConstantIndicator<Num> threshold;
    private final int lookbackPeriod;

    public FlexibleMLPredictionRule(MLModel mlModel, BarSeries series, Number threshold, int lookbackPeriod) {
        this.mlModel = mlModel;
        this.series = series;
        this.threshold = new ConstantIndicator<>(series, series.numOf(threshold));
        this.lookbackPeriod = lookbackPeriod;
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        if (index < 30) {  // MLModel requires at least 30 bars for prediction
            return false;
        }

        // 최근 N개의 예측을 확인
        for (int i = Math.max(30, index - lookbackPeriod + 1); i <= index; i++) {
            Num prediction = mlModel.predict(series, i);
            if (prediction.isGreaterThanOrEqual(threshold.getValue(i))) {
                return true;
            }
        }
        return false;
    }
}