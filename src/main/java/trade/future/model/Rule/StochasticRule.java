package trade.future.model.Rule;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.StochasticOscillatorDIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public class StochasticRule implements Rule {
    private final BarSeries series;
    private final int period;
    private final boolean isLong;

    public StochasticRule(BarSeries series, int period, boolean isLong) {
        this.series = series;
        this.period = period;
        this.isLong = isLong;
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        if (index < 1) {
            return false;
        }

        StochasticOscillatorKIndicator stochasticOscillatorK = new StochasticOscillatorKIndicator(series, period);
        StochasticOscillatorDIndicator stochasticOscillatorD = new StochasticOscillatorDIndicator(stochasticOscillatorK);

        double currentStochK = stochasticOscillatorK.getValue(index).doubleValue();
        double previousStochK = stochasticOscillatorK.getValue(index - 1).doubleValue();
        double currentStochD = stochasticOscillatorD.getValue(index).doubleValue();
        double previousStochD = stochasticOscillatorD.getValue(index - 1).doubleValue();

        boolean isKAboveD = currentStochK > currentStochD;
        boolean wasKBelowD = previousStochK <= previousStochD;
        boolean isKBelowD = currentStochK < currentStochD;
        boolean wasKAboveD = previousStochK >= previousStochD;

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        EMAIndicator shortEma = new EMAIndicator(closePrice, 20);
        EMAIndicator longEma = new EMAIndicator(closePrice, 50);

        boolean isUptrend = shortEma.getValue(index).isGreaterThan(longEma.getValue(index));
        boolean isDowntrend = shortEma.getValue(index).isLessThan(longEma.getValue(index));

        if (isLong) {
            return isKAboveD && wasKBelowD && currentStochK < 30 && isUptrend;
        } else {
            return isKBelowD && wasKAboveD && currentStochK > 70 && isDowntrend;
        }
    }
}
