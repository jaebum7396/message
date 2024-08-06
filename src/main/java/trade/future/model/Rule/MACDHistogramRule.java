package trade.future.model.Rule;

import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public class MACDHistogramRule implements Rule {
    private final ClosePriceIndicator closePrice;
    private final int shortMovingPeriod;
    private final int longMovingPeriod;
    private final boolean isPositive;

    public MACDHistogramRule(ClosePriceIndicator closePrice, int shortMovingPeriod, int longMovingPeriod, boolean isPositive) {
        this.closePrice = closePrice;
        this.shortMovingPeriod = shortMovingPeriod;
        this.longMovingPeriod = longMovingPeriod;
        this.isPositive = isPositive;
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        if (index < 2) {
            return false;
        }

        double currentMACDHistogram     = calculateMACDHistogram(closePrice, shortMovingPeriod, longMovingPeriod, index);
        double previousMACDHistogram    = calculateMACDHistogram(closePrice, shortMovingPeriod, longMovingPeriod, index - 1);
        double prePreviousMACDHistogram = calculateMACDHistogram(closePrice, shortMovingPeriod, longMovingPeriod, index - 2);

        double macdHistogramGap         = currentMACDHistogram - previousMACDHistogram;
        double previousMACDHistogramGap = previousMACDHistogram - prePreviousMACDHistogram;

        boolean MACD_히스토그램_증가 = macdHistogramGap > 0;
        boolean 이전_MACD_히스토그램_증가 = previousMACDHistogramGap > 0;

        if (isPositive) {
            return MACD_히스토그램_증가 && !이전_MACD_히스토그램_증가;
        } else {
            return !MACD_히스토그램_증가 && 이전_MACD_히스토그램_증가;
        }
    }

    private double calculateMACDHistogram(ClosePriceIndicator closePriceIndicator, int shortPeriod, int longPeriod, int idx) {
        MACDIndicator macdIndicator = new MACDIndicator(closePriceIndicator, shortPeriod, longPeriod);
        EMAIndicator MACD_신호선 = new EMAIndicator(macdIndicator, 9);
        return macdIndicator.getValue(idx).doubleValue() - MACD_신호선.getValue(idx).doubleValue();
    }
}
