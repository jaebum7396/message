package trade.future.model.Rule;

import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.num.Num;

public class ATRIncreaseRule implements Rule {
    private final ATRIndicator atr;
    private final Num increaseThreshold;

    public ATRIncreaseRule(BaseBarSeries series, int atrPeriod, double increasePercentage) {
        this.atr = new ATRIndicator(series, atrPeriod);
        this.increaseThreshold = series.numOf(1 + increasePercentage);
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        if (index == 0) return false;
        Num currentATR = atr.getValue(index);
        Num previousATR = atr.getValue(index - 1);
        return currentATR.dividedBy(previousATR).isGreaterThan(increaseThreshold);
    }
}