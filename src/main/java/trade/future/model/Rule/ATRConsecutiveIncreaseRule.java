package trade.future.model.Rule;

import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.ATRIndicator;

public class ATRConsecutiveIncreaseRule implements Rule {
    private final ATRIndicator atr;
    private final int consecutivePeriods;

    public ATRConsecutiveIncreaseRule(BaseBarSeries series, int atrPeriod, int consecutivePeriods) {
        this.atr = new ATRIndicator(series, atrPeriod);
        this.consecutivePeriods = consecutivePeriods;
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        if (index < consecutivePeriods) return false;
        for (int i = 1; i <= consecutivePeriods; i++) {
            if (atr.getValue(index - i + 1).isLessThanOrEqual(atr.getValue(index - i))) {
                return false;
            }
        }
        return true;
    }
}