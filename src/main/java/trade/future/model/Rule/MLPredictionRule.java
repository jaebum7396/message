package trade.future.model.Rule;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.helpers.ConstantIndicator;
import org.ta4j.core.num.Num;
import trade.future.ml.MLModel;

public class MLPredictionRule implements Rule {
    private final MLModel mlModel;
    private final BarSeries series;
    private final ConstantIndicator<Num> threshold;

    public MLPredictionRule(MLModel mlModel, BarSeries series, Number threshold) {
        this.mlModel = mlModel;
        this.series = series;
        this.threshold = new ConstantIndicator<>(series, series.numOf(threshold));
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        if (index < 30) {  // MLModel requires at least 30 bars for prediction
            return false;
        }

        Num prediction = mlModel.predict(series, index);
        return prediction.isGreaterThanOrEqual(threshold.getValue(index));
    }
}