package signal.broadcast.model.indicator;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

public class TrendIndicator extends CachedIndicator<Num> {
    private final EMAIndicator shortEma;
    private final EMAIndicator longEma;
    private final RSIIndicator rsi;

    public TrendIndicator(BarSeries series, int shortEmaPeriod, int longEmaPeriod, int rsiPeriod) {
        super(series);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        this.shortEma = new EMAIndicator(closePrice, shortEmaPeriod);
        this.longEma = new EMAIndicator(closePrice, longEmaPeriod);
        this.rsi = new RSIIndicator(closePrice, rsiPeriod);
    }

    @Override
    protected Num calculate(int index) {
        Num shortEmaValue = shortEma.getValue(index);
        Num longEmaValue = longEma.getValue(index);
        Num rsiValue = rsi.getValue(index);

        if (shortEmaValue.isGreaterThan(longEmaValue) &&
                rsiValue.isGreaterThan(numOf(50))) {
            return numOf(1); // 상승
        } else if (shortEmaValue.isLessThan(longEmaValue) &&
                rsiValue.isLessThan(numOf(50))) {
            return numOf(-1); // 하락
        } else {
            return numOf(0); // 횡보
        }
    }
}