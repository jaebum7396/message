package trade.future.service;

import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.num.Num;

public class AverageTrueRangeIndicator extends CachedIndicator<Num> {
    private final int barCount;

    public AverageTrueRangeIndicator(BarSeries series, int barCount) {
        super(series);
        this.barCount = barCount;
    }

    @Override
    protected Num calculate(int index) {
        if (index == 0) {
            return getBarSeries().numOf(0);
        }

        Num tr = calculateTrueRange(index);
        Num sumTr = tr;

        for (int i = 1; i < barCount && (index - i) >= 0; i++) {
            sumTr = sumTr.plus(calculateTrueRange(index - i));
        }

        return sumTr.dividedBy(getBarSeries().numOf(barCount));
    }

    private Num calculateTrueRange(int index) {
        if (index == 0) {
            return getBarSeries().numOf(0); // 첫 번째 바에서는 True Range를 계산하지 않음
        }

        Num high = getBarSeries().getBar(index).getHighPrice();
        Num low = getBarSeries().getBar(index).getLowPrice();
        Num previousClose = getBarSeries().getBar(index - 1).getClosePrice();

        Num tr1 = high.minus(low).abs();
        Num tr2 = high.minus(previousClose).abs();
        Num tr3 = low.minus(previousClose).abs();

        return tr1.max(tr2).max(tr3);
    }
}
