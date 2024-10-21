package signal.broadcast.model.indicator;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public class TrendIndicator extends CachedIndicator<String> {
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
    protected String calculate(int index) {
        double shortEmaValue = shortEma.getValue(index).doubleValue();
        double longEmaValue = longEma.getValue(index).doubleValue();
        double rsiValue = rsi.getValue(index).doubleValue();

        if (shortEmaValue > longEmaValue && rsiValue > 50) {
            return "상승";
        } else if (shortEmaValue < longEmaValue && rsiValue < 50) {
            return "하락";
        } else {
            return "중립";
        }
    }
}