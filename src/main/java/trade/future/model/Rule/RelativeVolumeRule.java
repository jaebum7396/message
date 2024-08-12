package trade.future.model.Rule;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;

public class RelativeVolumeRule implements Rule {
    private final BarSeries series;
    private final int period;
    private final double threshold;
    private final boolean isGreaterThan;

    public RelativeVolumeRule(BarSeries series, int period, double threshold, boolean isGreaterThan) {
        this.series = series;
        this.period = period;
        this.threshold = threshold;
        this.isGreaterThan = isGreaterThan;
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        if (index < period - 1) {
            return false;
        }

        double relativeVolume = getRelativeVolume(series, index, period);

        return isGreaterThan ? relativeVolume > threshold : relativeVolume < threshold;
    }

    private double getRelativeVolume(BarSeries series, int index, int period) {
        double sumVolume = 0;
        for (int i = Math.max(0, index - period + 1); i <= index; i++) {
            sumVolume += series.getBar(i).getVolume().doubleValue();
        }
        double avgVolume = sumVolume / Math.min(period, index + 1);
        return series.getBar(index).getVolume().doubleValue() / avgVolume;
    }
}