package trade.future.model.Rule;

import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.Num;

public class RelativeATRIndicator extends CachedIndicator<Num> {

    private final ATRIndicator atr;
    private final SMAIndicator atrSMA;
    private final StandardDeviationIndicator atrStdDev;
    private final int period;
    private final int lookbackPeriod;

    public RelativeATRIndicator(BaseBarSeries series, int period, int lookbackPeriod) {
        super(series);
        this.atr = new ATRIndicator(series, period);
        this.atrSMA = new SMAIndicator(atr, lookbackPeriod);
        this.atrStdDev = new StandardDeviationIndicator(atr, lookbackPeriod);
        this.period = period;
        this.lookbackPeriod = lookbackPeriod;
    }

    @Override
    protected Num calculate(int index) {
        if (index < lookbackPeriod) {
            return numOf(0);  // 충분한 데이터가 없을 경우 0 반환
        }

        Num currentATR = atr.getValue(index);
        Num meanATR = atrSMA.getValue(index);
        Num stdDevATR = atrStdDev.getValue(index);

        // Z-점수 계산: (현재값 - 평균) / 표준편차
        return currentATR.minus(meanATR).dividedBy(stdDevATR);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " period: " + period + " lookback: " + lookbackPeriod;
    }
}