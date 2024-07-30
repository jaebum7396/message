package trade.future.service;

import org.springframework.data.mongodb.core.mapping.TimeSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

public class RelativeATRIndicator extends CachedIndicator<Num> {

    private final ATRIndicator atr;
    private final ClosePriceIndicator closePrice;
    private final int period;

    public RelativeATRIndicator(BaseBarSeries series, int period) {
        super(series);
        this.atr = new ATRIndicator(series, period);
        this.closePrice = new ClosePriceIndicator(series);
        this.period = period;
    }

    @Override
    protected Num calculate(int index) {
        Num atrValue = atr.getValue(index);
        Num currentClose = closePrice.getValue(index);

        // ATR을 현재 종가로 나누어 상대적인 값으로 변환
        return atrValue.dividedBy(currentClose).multipliedBy(numOf(100));
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " period: " + period;
    }
}