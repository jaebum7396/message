package trade.future.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.num.Num;
import trade.future.model.enums.ADX_GRADE;

@Slf4j
@Service
public class TechnicalIndicatorCalculator {

    public TechnicalIndicatorCalculator() {}

    public static boolean isGoldenCross(BaseBarSeries series, int shortTerm, int longTerm, int signal) {
        // Calculate MACD indicator
        MACDIndicator macd = new MACDIndicator(new ClosePriceIndicator(series), shortTerm, longTerm);

        // Calculate signal line
        EMAIndicator emaMacd = new EMAIndicator(macd, signal);

        // Get the last bar
        Bar lastBar = series.getBar(series.getEndIndex());

        // Check for golden cross
        return macd.getValue(series.getEndIndex()).isGreaterThan(emaMacd.getValue(series.getEndIndex())) &&
                macd.getValue(series.getEndIndex() - 1).isLessThanOrEqual(emaMacd.getValue(series.getEndIndex() - 1));
    }

    public static boolean isDeadCross(BaseBarSeries series, int shortTerm, int longTerm, int signal) {
        // Calculate MACD indicator
        MACDIndicator macd = new MACDIndicator(new ClosePriceIndicator(series), shortTerm, longTerm);

        // Calculate signal line
        EMAIndicator emaMacd = new EMAIndicator(macd, signal);

        // Get the last bar
        Bar lastBar = series.getBar(series.getEndIndex());

        // Check for dead cross
        return macd.getValue(series.getEndIndex()).isLessThan(emaMacd.getValue(series.getEndIndex())) &&
                macd.getValue(series.getEndIndex() - 1).isGreaterThanOrEqual(emaMacd.getValue(series.getEndIndex() - 1));
    }

    public void detectDivergence(BaseBarSeries series, ClosePriceIndicator closePrice, Indicator<Num> indicator, int N) {
        int endIndex = series.getEndIndex();
        int startIndex = Math.max(0, endIndex - N + 1);

        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);

        Num highestPrice = closePrice.getValue(startIndex);
        Num lowestPrice = closePrice.getValue(startIndex);
        int highestPriceIndex = startIndex;
        int lowestPriceIndex = startIndex;

        // 고점 및 저점 탐색
        for (int i = startIndex; i <= endIndex; i++) {
            Num currentPrice = closePrice.getValue(i);
            if (currentPrice.isGreaterThan(highestPrice)) {
                highestPrice = currentPrice;
                highestPriceIndex = i;
            }
            if (currentPrice.isLessThan(lowestPrice)) {
                lowestPrice = currentPrice;
                lowestPriceIndex = i;
            }
        }

        // 고점 간 다이버전스 탐지
        if (highestPriceIndex != startIndex && highestPriceIndex != endIndex) {
            for (int i = highestPriceIndex + 1; i <= endIndex; i++) {
                Num nextPrice = closePrice.getValue(i);
                Num nextIndicatorValue = indicator.getValue(i);
                Num highestIndicatorValue = indicator.getValue(highestPriceIndex);
                /*System.out.println("*** 하락다이버전스체크 ***");
                if (nextPrice.isGreaterThan(highestPrice)){
                    System.out.println("고점 상승");
                }
                if (nextIndicatorValue.isLessThan(highestIndicatorValue)){
                    System.out.println("지표 하락");
                }
                System.out.println("");*/
                if (nextPrice.isGreaterThan(highestPrice) && nextIndicatorValue.isLessThan(highestIndicatorValue)) {
                    System.out.println("Bearish divergence detected between indices " + highestPriceIndex + " and " + i);
                }
            }
        }

        // 저점 간 다이버전스 탐지
        if (lowestPriceIndex != startIndex && lowestPriceIndex != endIndex) {
            for (int i = lowestPriceIndex + 1; i <= endIndex; i++) {
                Num nextPrice = closePrice.getValue(i);
                Num nextIndicatorValue = indicator.getValue(i);
                Num lowestIndicatorValue = indicator.getValue(lowestPriceIndex);

                /*System.out.println("*** 상승다이버전스체크 ***");
                if(nextPrice.isLessThan(lowestPrice)){
                    System.out.println("저점 내림");
                };
                if (nextIndicatorValue.isGreaterThan(lowestIndicatorValue)){
                    System.out.println("지표 상승");
                }
                System.out.println("");*/

                if (nextPrice.isLessThan(lowestPrice) && nextIndicatorValue.isGreaterThan(lowestIndicatorValue)) {
                    System.out.println("Bullish divergence detected between indices " + lowestPriceIndex + " and " + i);
                }
            }
        }
    }

    public String determineTrend(BaseBarSeries series, SMAIndicator sma) {
        int endIndex = series.getEndIndex() - 1;
        Num lastValue = sma.getValue(endIndex);
        Num secondLastValue = sma.getValue(endIndex - 1);

        if (lastValue.isGreaterThan(secondLastValue)) {
            // Calculate upward trend angle
            double angle = calculateUpwardAngle(series, 14); // Choose a suitable period
            return "LONG";
        } else if (lastValue.isLessThan(secondLastValue)) {
            // Calculate downward trend angle
            double angle = calculateDownwardAngle(series, 14); // Choose a suitable period
            return "SHORT";
        } else {
            return "SIDE";
        }
    }

    // Calculate the angle of an upward trend (in degrees)
    public double calculateUpwardAngle(BaseBarSeries series, int period) {
        Bar endBar = series.getBar(series.getEndIndex());
        Bar startBar = series.getBar(Math.max(0, series.getEndIndex() - period));

        double startPrice = startBar.getClosePrice().doubleValue();
        double endPrice = endBar.getClosePrice().doubleValue();

        double priceChange = endPrice - startPrice;
        double timeChange = endBar.getEndTime().toEpochSecond() - startBar.getEndTime().toEpochSecond();

        // Calculate the angle in degrees
        return Math.toDegrees(Math.atan(priceChange / timeChange));
    }

    // Calculate the angle of a downward trend (in degrees)
    public double calculateDownwardAngle(BaseBarSeries series, int period) {
        Bar endBar = series.getBar(series.getEndIndex());
        Bar startBar = series.getBar(Math.max(0, series.getEndIndex() - period));

        double startPrice = startBar.getClosePrice().doubleValue();
        double endPrice = endBar.getClosePrice().doubleValue();

        double priceChange = endPrice - startPrice;
        double timeChange = endBar.getEndTime().toEpochSecond() - startBar.getEndTime().toEpochSecond();

        // Calculate the angle in degrees
        return Math.toDegrees(Math.atan(-priceChange / timeChange)); // Use negative for downward angle
    }

    public double calculateADX(BaseBarSeries series, int adxPeriod, int idx) {
        ADXIndicator adxIndicator = new ADXIndicator(series, adxPeriod);
        return adxIndicator.getValue(idx).doubleValue();
    }

    public double calculateRSI(BaseBarSeries series, int adxPeriod, int idx) {
        RSIIndicator rsiIndicator = new RSIIndicator(new ClosePriceIndicator(series), adxPeriod);
        return rsiIndicator.getValue(idx).doubleValue();
    }

    public double calculateMACD(ClosePriceIndicator closePriceIndicator, int shortPeriod, int longPeriod, int idx) {
        MACDIndicator macdIndicator = new MACDIndicator(closePriceIndicator, shortPeriod, longPeriod);
        return macdIndicator.getValue(idx).doubleValue();
    }

    public double calculateMACDHistogram(ClosePriceIndicator closePriceIndicator, int shortPeriod, int longPeriod, int idx) {
        MACDIndicator macdIndicator = new MACDIndicator(closePriceIndicator, shortPeriod, longPeriod);
        EMAIndicator MACD_신호선 = new EMAIndicator(macdIndicator, 9);
        return macdIndicator.getValue(idx).doubleValue() - MACD_신호선.getValue(idx).doubleValue();
    }

    public double calculatePlusDI(BaseBarSeries series, int period, int idx) {
        PlusDIIndicator plusDI = new PlusDIIndicator(series, period);
        return plusDI.getValue(idx).doubleValue();
    }

    public double calculateMinusDI(BaseBarSeries series, int period, int idx) {
        MinusDIIndicator minusDI = new MinusDIIndicator(series, period);
        return minusDI.getValue(idx).doubleValue();
    }

    public String getDirection(BaseBarSeries series, int period, int idx){
        double plusDI = calculatePlusDI(series, period, idx);
        double minusDI = calculateMinusDI(series, period, idx);

        String direction = "";
        if(plusDI > minusDI){
            direction = "LONG";
            //direction = "SHORT";
        }
        else if(plusDI < minusDI){
            direction = "SHORT";
            //direction = "LONG";
        }
        else{
            direction = "SIDE";
        }
        return direction;
    }

    public ADX_GRADE calculateADXGrade(double adx){
        ADX_GRADE adx_grade = ADX_GRADE.횡보;
        if(adx <= 15){
            adx_grade = ADX_GRADE.횡보;
        }
        else if(adx > 15 && adx <= 25){
            adx_grade = ADX_GRADE.약한추세;
        }
        else if(adx > 25 && adx <= 35){
            adx_grade = ADX_GRADE.추세확정;
        }
        else if(adx > 35 && adx <= 45){
            adx_grade = ADX_GRADE.강한추세;
        }
        else if(adx > 45){
            adx_grade = ADX_GRADE.매우강한추세;
        }
        return adx_grade;
    }
}