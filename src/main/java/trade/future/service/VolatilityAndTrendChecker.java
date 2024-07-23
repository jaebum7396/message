package trade.future.service;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

public class VolatilityAndTrendChecker {
    private final AverageTrueRangeIndicator atr;
    private final Num atrThreshold;
    private final SMAIndicator shortTermSMA;
    private final SMAIndicator longTermSMA;

    public VolatilityAndTrendChecker(BarSeries series, int atrPeriod, double thresholdMultiplier, int shortTermPeriod, int longTermPeriod) {
        this.atr = new AverageTrueRangeIndicator(series, atrPeriod);

        // ATR 평균 계산
        int seriesSize = series.getBarCount();
        Num sumATR = series.numOf(0);
        for (int i = 0; i < seriesSize; i++) {
            sumATR = sumATR.plus(atr.getValue(i));
        }
        Num averageATR = sumATR.dividedBy(series.numOf(seriesSize));
        this.atrThreshold = averageATR.multipliedBy(series.numOf(thresholdMultiplier));

        // 이동 평균 계산
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        this.shortTermSMA = new SMAIndicator(closePrice, shortTermPeriod);
        this.longTermSMA = new SMAIndicator(closePrice, longTermPeriod);
    }

    public int checkMarketCondition(int index) {
        if (index < 1) {
            return 0; // 인덱스가 0일 때 처리
        }
        Num currentATR = atr.getValue(index);
        Num shortSMA = shortTermSMA.getValue(index);
        Num longSMA = longTermSMA.getValue(index);

        if (currentATR.isGreaterThan(atrThreshold)) {
            // 변동성이 높음
            /*if (shortSMA.isGreaterThan(longSMA)) {
                return 1;
            } else if (shortSMA.isLessThan(longSMA)) {
                return 1;
            } else {
                return 1;
            }*/
            return 1;
        } else {
            // 변동성이 낮음
            /*if (shortSMA.isGreaterThan(longSMA)) {
                return "LOW VOLATILITY, UPTREND";
            } else if (shortSMA.isLessThan(longSMA)) {
                return "LOW VOLATILITY, DOWNTREND";
            } else {
                return "LOW VOLATILITY, NO CLEAR TREND";
            }*/
            return -1;
        }
    }
}
