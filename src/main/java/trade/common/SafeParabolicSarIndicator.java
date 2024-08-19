package trade.common;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.ParabolicSarIndicator;
import org.ta4j.core.num.Num;

public class SafeParabolicSarIndicator extends CachedIndicator<Num> {
    private final ParabolicSarIndicator originalIndicator;
    private final int minIndex;

    public SafeParabolicSarIndicator(BarSeries series) {
        super(series);
        this.originalIndicator = new ParabolicSarIndicator(series);
        this.minIndex = 2; // ParabolicSarIndicator는 최소 2개의 데이터 포인트가 필요합니다
    }

    @Override
    protected Num calculate(int index) {
        if (index < minIndex) {
            return numOf(Double.NaN);
        }
        Num value = originalIndicator.getValue(index);
        return (value != null && !value.isNaN()) ? value : numOf(Double.NaN);
    }
}