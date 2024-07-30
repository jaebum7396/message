package trade.future.model.Rule;

import org.springframework.data.mongodb.core.mapping.TimeSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.SMAIndicator;

public class ATRTrendRule implements Rule {
    private final ATRIndicator atr;
    private final SMAIndicator shortTermATR;
    private final SMAIndicator longTermATR;

    public ATRTrendRule(BaseBarSeries series, int atrPeriod, int shortTermPeriod, int longTermPeriod) {
        this.atr = new ATRIndicator(series, atrPeriod);
        this.shortTermATR = new SMAIndicator(atr, shortTermPeriod);
        this.longTermATR = new SMAIndicator(atr, longTermPeriod);
    }

    @Override
    public boolean isSatisfied(int index) {
        return shortTermATR.getValue(index).isGreaterThan(longTermATR.getValue(index));
    }
    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        return shortTermATR.getValue(index).isGreaterThan(longTermATR.getValue(index));
    }
}