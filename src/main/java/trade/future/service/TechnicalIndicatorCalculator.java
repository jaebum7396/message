package trade.future.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.num.Num;
import trade.future.model.entity.TradingEntity;
import trade.future.model.enums.ADX_GRADE;
import trade.future.model.enums.CONSOLE_COLORS;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;

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
            //double angle = calculateUpwardAngle(series, 14); // Choose a suitable period
            return "LONG";
        } else if (lastValue.isLessThan(secondLastValue)) {
            // Calculate downward trend angle
            //double angle = calculateDownwardAngle(series, 14); // Choose a suitable period
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

    public double calculateRSI(BaseBarSeries series, int rsiPeriod, int idx) {
        RSIIndicator rsiIndicator = new RSIIndicator(new ClosePriceIndicator(series), rsiPeriod);
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
        double plusDI  = calculatePlusDI(series, period, idx);
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

    public HashMap<String,Object> adxStrategy(BaseBarSeries series, int period, String directionDI){
        HashMap<String,Object> resultMap = new HashMap<>();
        //adx
        double currentAdx             = calculateADX(series, period, series.getEndIndex());
        double previousAdx            = calculateADX(series, period, series.getEndIndex()-1);
        double prePreviousAdx         = calculateADX(series, period, series.getEndIndex()-2);

        ADX_GRADE currentAdxGrade     = calculateADXGrade(currentAdx);
        ADX_GRADE previousAdxGrade    = calculateADXGrade(previousAdx);
        ADX_GRADE prePreviousAdxGrade = calculateADXGrade(prePreviousAdx);

        double adxGap = currentAdx - previousAdx; //adx차
        double previousAdxGap = previousAdx - prePreviousAdx; //이전adx차

        boolean isAdxGapPositive = adxGap > 0; //adx차가 양수(adx가 올랐는지)
        boolean isPreviousAdxGapPositive = previousAdxGap > 0;

        int adxSignal = 0;
        int adxDirectionSignal = 0;

        String commonRemark = CONSOLE_COLORS.YELLOW + "ADX(" + currentAdx + "[" + adxGap + "]) " + CONSOLE_COLORS.RESET;
        String specialRemark = "";
        String adxDirectionExpression = "";
        if (isAdxGapPositive == isPreviousAdxGapPositive) {
            //System.out.println("추세유지");
        } else {
            if (adxGap > 0.5) {
                adxSignal = 1;
                if (directionDI.equals("LONG")) {
                    adxDirectionSignal = 1;
                    adxDirectionExpression = CONSOLE_COLORS.BRIGHT_GREEN + "LONG";
                } else if (directionDI.equals("SHORT")) {
                    adxDirectionSignal = -1;
                    adxDirectionExpression = CONSOLE_COLORS.BRIGHT_RED + "SHORT";
                }
                specialRemark += CONSOLE_COLORS.YELLOW + "[ADX " + adxDirectionExpression + " 시그널]" + CONSOLE_COLORS.RESET + "추세감소 >>> 추세증가[" + directionDI + "] :" + previousAdx + " >>> " + currentAdx + "(" + previousAdxGap + "/" + adxGap + ") " + CONSOLE_COLORS.RESET;
            } else if (adxGap < -0.5) {
                adxSignal = -1;
                if (directionDI.equals("LONG")) {
                    adxDirectionSignal = -1;
                    adxDirectionExpression = CONSOLE_COLORS.BRIGHT_RED + "SHORT";
                } else if (directionDI.equals("SHORT")) {
                    adxDirectionSignal = 1;
                    adxDirectionExpression = CONSOLE_COLORS.BRIGHT_GREEN + "LONG";
                }
                specialRemark += CONSOLE_COLORS.YELLOW + "[ADX " + adxDirectionExpression + " 시그널]" + CONSOLE_COLORS.RESET + "추세증가 >>> 추세감소[" + directionDI + "] :" + previousAdx + " >>> " + currentAdx + "(" + previousAdxGap + "/" + adxGap + ") " + CONSOLE_COLORS.RESET;
            }
        }
        resultMap.put("currentAdx", currentAdx);
        resultMap.put("currentAdxGrade", currentAdxGrade);
        resultMap.put("previousAdx", previousAdx);
        resultMap.put("previousAdxGrade", previousAdxGrade);
        resultMap.put("adxGap", adxGap);
        resultMap.put("adxSignal", adxSignal);
        resultMap.put("adxDirectionSignal", adxDirectionSignal);
        resultMap.put("commonRemark", commonRemark);
        resultMap.put("specialRemark", specialRemark);
        return resultMap;
    }

    public HashMap<String,Object> macdStrategy(BaseBarSeries series, ClosePriceIndicator closePrice){
        HashMap<String,Object> resultMap = new HashMap<>();

        // Calculate MACD
        MACDIndicator macd = new MACDIndicator(closePrice, 6, 12);

        double currentMacdHistogram     = calculateMACDHistogram(closePrice, 6, 12, series.getEndIndex());
        double previousMacdHistogram    = calculateMACDHistogram(closePrice, 6, 12, series.getEndIndex()-1);
        double prePreviousMacdHistogram = calculateMACDHistogram(closePrice, 6, 12, series.getEndIndex()-2);

        double macdHistogramGap = currentMacdHistogram - previousMacdHistogram;
        double previousMacdHistogramGap = previousMacdHistogram - prePreviousMacdHistogram;

        boolean MACD_히스토그램_증가 = macdHistogramGap > 0;
        boolean 이전_MACD_히스토그램_증가 = previousMacdHistogramGap > 0;

        String commonRemark = "";
        String specialRemark = "";

        commonRemark += CONSOLE_COLORS.BRIGHT_PURPLE+"MACD(" + currentMacdHistogram +"[" + macdHistogramGap + "]) "+CONSOLE_COLORS.RESET;

        int macdReversalSignal = 0;
        if (MACD_히스토그램_증가 == 이전_MACD_히스토그램_증가) {
            //System.out.println("추세유지");
        } else {
            if(!이전_MACD_히스토그램_증가 && MACD_히스토그램_증가 && previousMacdHistogram < 0){ // 이전 히스토그램이 음수였다가 양수로 전환되는 시그널
                specialRemark += CONSOLE_COLORS.PURPLE+"[MACD "+CONSOLE_COLORS.BRIGHT_GREEN+"LONG 시그널]"+CONSOLE_COLORS.RESET+"MACD히스토그램감소 >>> MACD히스토그램증가 :" + previousMacdHistogram + " >>> " + currentMacdHistogram + "(" + previousMacdHistogramGap + "/" + macdHistogramGap +") "+CONSOLE_COLORS.RESET;
                macdReversalSignal = 1;
            }
            if(이전_MACD_히스토그램_증가 && !MACD_히스토그램_증가 && previousMacdHistogram > 0){ // 이전 히스토그램이 양수였다가 음수로 전환되는 시그널
                specialRemark += CONSOLE_COLORS.PURPLE+"[MACD "+CONSOLE_COLORS.BRIGHT_RED+"SHORT 시그널]"+CONSOLE_COLORS.RESET+"MACD히스토그램증가 >>> MACD히스토그램감소 :" + previousMacdHistogram + " >>> " + currentMacdHistogram + "(" + previousMacdHistogramGap + "/" + macdHistogramGap +") "+CONSOLE_COLORS.RESET;
                macdReversalSignal = -1;
            }
        }

        EMAIndicator macdSignalLine = new EMAIndicator(macd, 5);
        // MACD 크로스 신호 계산
        int macdPreliminarySignal = 0;
        boolean isMacdHighAndDeadCrossSoon = macd.getValue(series.getEndIndex() - 1).isGreaterThan(macdSignalLine.getValue(series.getEndIndex() - 1))
                && macd.getValue(series.getEndIndex()).isLessThan(macdSignalLine.getValue(series.getEndIndex()));
        boolean isMacdLowAndGoldenCrossSoon = macd.getValue(series.getEndIndex() - 1).isLessThan(macdSignalLine.getValue(series.getEndIndex() - 1))
                && macd.getValue(series.getEndIndex()).isGreaterThan(macdSignalLine.getValue(series.getEndIndex()));

        if (isMacdHighAndDeadCrossSoon) {
            macdPreliminarySignal = -1;
        } else if (isMacdLowAndGoldenCrossSoon) {
            macdPreliminarySignal = 1;
        }
        int macdCrossSignal = 0 ; // 골든 크로스일시 1, 데드 크로스일시 -1
        if(isGoldenCross(series, 6, 12, 5) && macd.getValue(series.getEndIndex()).isLessThan(series.zero())){
            macdCrossSignal = 1;
        }
        if(isDeadCross(series, 6, 12, 5) && macd.getValue(series.getEndIndex()).isGreaterThan(series.zero())){
            macdCrossSignal = -1;
        }

        resultMap.put("currentMacd", macd.getValue(series.getEndIndex()).doubleValue());
        resultMap.put("currentMacdHistogram", currentMacdHistogram);
        resultMap.put("macdHistogramGap", macdHistogramGap);
        resultMap.put("macdCrossSignal", macdCrossSignal);
        resultMap.put("macdReversalSignal", macdReversalSignal);
        resultMap.put("commonRemark", commonRemark);
        resultMap.put("specialRemark", specialRemark);
        return resultMap;
    }

    public HashMap<String,Object> rsiStrategy(BaseBarSeries series, ClosePriceIndicator closePrice, int period){
        HashMap<String,Object> resultMap = new HashMap<>();

        RSIIndicator rsi = new RSIIndicator(closePrice, period);

        double currentRsi     = rsi.getValue(series.getEndIndex()).doubleValue();
        double previousRsi    = rsi.getValue(series.getEndIndex() - 1).doubleValue();
        double prePreviousRsi = rsi.getValue(series.getEndIndex() - 2).doubleValue();

        double rsiGap = currentRsi - previousRsi;
        double previousRsiGap = previousRsi - prePreviousRsi;

        boolean RSI_증가 = rsiGap > 0;
        boolean 이전_RSI_증가 = previousRsiGap > 0;

        String commonRemark = "";
        String specialRemark = "";

        commonRemark += CONSOLE_COLORS.BRIGHT_BLUE+"RSI(" + currentRsi +"[" + rsiGap + "]) "+CONSOLE_COLORS.RESET;

        int rsiSignal = 0;
        if (RSI_증가 == 이전_RSI_증가) {
            //System.out.println("추세유지");
        } else {
            if (currentRsi < 20) {
                if(!이전_RSI_증가 && RSI_증가){
                    specialRemark += CONSOLE_COLORS.BRIGHT_BLUE+"[RSI "+CONSOLE_COLORS.BRIGHT_GREEN+"LONG 시그널]"+CONSOLE_COLORS.RESET+"RSI감소 >>> RSI증가 :" + previousRsi + " >>> " + currentRsi + "(" + previousRsiGap + "/" + rsiGap +") "+CONSOLE_COLORS.RESET;
                    rsiSignal = 1;
                }
            }
            if (currentRsi > 70) {
                if(이전_RSI_증가 && !RSI_증가){
                    specialRemark += CONSOLE_COLORS.BRIGHT_BLUE+"[RSI "+CONSOLE_COLORS.BRIGHT_RED+"SHORT 시그널]"+CONSOLE_COLORS.RESET+"RSI증가 >>> RSI감소 :" + previousRsi + " >>> " + currentRsi + "(" + previousRsiGap + "/" + rsiGap +") "+CONSOLE_COLORS.RESET;
                    rsiSignal = -1;
                }
            }
        }
        resultMap.put("currentRsi", currentRsi);
        resultMap.put("rsiSignal", rsiSignal);
        resultMap.put("commonRemark", commonRemark);
        resultMap.put("specialRemark", specialRemark);
        return resultMap;
    }


    public HashMap<String,Object> stochStrategy(BaseBarSeries series, ClosePriceIndicator closePrice, int period){
        HashMap<String,Object> resultMap = new HashMap<>();

        StochasticOscillatorKIndicator stochasticOscillatorK = new StochasticOscillatorKIndicator(series, period);
        StochasticOscillatorDIndicator stochasticOscillatorD = new StochasticOscillatorDIndicator(stochasticOscillatorK);

        double currentStochK = stochasticOscillatorK.getValue(series.getEndIndex()).doubleValue();
        double previousStochK = stochasticOscillatorK.getValue(series.getEndIndex() - 1).doubleValue();
        double currentStochD = stochasticOscillatorD.getValue(series.getEndIndex()).doubleValue();
        double previousStochD = stochasticOscillatorD.getValue(series.getEndIndex() - 1).doubleValue();

        boolean isKAboveD = currentStochK > currentStochD;
        boolean wasKBelowD = previousStochK <= previousStochD;
        boolean isKBelowD = currentStochK < currentStochD;
        boolean wasKAboveD = previousStochK >= previousStochD;

        // 이동평균 필터 설정
        EMAIndicator shortEma = new EMAIndicator(closePrice, 20);  // 50-period EMA
        EMAIndicator longEma = new EMAIndicator(closePrice, 50);  // 200-period EMA

        // 추세 필터 조건
        boolean isUptrend = shortEma.getValue(series.getEndIndex()).isGreaterThan(longEma.getValue(series.getEndIndex()));
        boolean isDowntrend = shortEma.getValue(series.getEndIndex()).isLessThan(longEma.getValue(series.getEndIndex()));

        VolumeIndicator volumeIndicator = new VolumeIndicator(series, period); // 볼륨 필터를 위한 설정
        // 볼륨 필터 조건
        Num currentVolume = volumeIndicator.getValue(series.getEndIndex());
        Num averageVolume = volumeIndicator.getValue(series.getEndIndex() - 1); // 임의로 설정

        String commonRemark = "";
        String specialRemark = "";

        String kDExpression = "";
        if(isKAboveD && wasKBelowD) {
            kDExpression = "상향 돌파" + CONSOLE_COLORS.RESET;
        } else if (isKBelowD && wasKAboveD) {
            kDExpression = "하향 돌파" + CONSOLE_COLORS.RESET;
        } else {
            kDExpression = "변동 없음" + CONSOLE_COLORS.RESET;
        }
        commonRemark += CONSOLE_COLORS.BRIGHT_CYAN+ "Stochastic K/D(" + currentStochK + "/" + currentStochD + "[" + kDExpression + "]) "+CONSOLE_COLORS.RESET;

        int stochSignal = 0;
        if (isKAboveD && wasKBelowD && currentStochK < 30 && isUptrend) {
            specialRemark += CONSOLE_COLORS.BRIGHT_CYAN+"[Stochastic "+CONSOLE_COLORS.BRIGHT_GREEN+"LONG 진입 시그널]"+CONSOLE_COLORS.RESET+" Stochastic K/D 상향 돌파 : " + previousStochK + "/" + previousStochD + " >>> " + currentStochK + "/" + currentStochD + " "+CONSOLE_COLORS.RESET;
            stochSignal = 1;
        } else if (isKBelowD && wasKAboveD && currentStochK > 70 && isDowntrend) {
            specialRemark += CONSOLE_COLORS.BRIGHT_CYAN+"[Stochastic "+CONSOLE_COLORS.BRIGHT_RED+"SHORT 진입 시그널]"+CONSOLE_COLORS.RESET+" Stochastic K/D 하향 돌파 : " + previousStochK + "/" + previousStochD + " >>> " + currentStochK + "/" + currentStochD + " "+CONSOLE_COLORS.RESET;
            stochSignal = -1;
        }
        resultMap.put("stochK", currentStochK);
        resultMap.put("stochD", currentStochD);
        resultMap.put("stochSignal", stochSignal);
        resultMap.put("commonRemark", commonRemark);
        resultMap.put("specialRemark", specialRemark);

        return resultMap;
    }

    public HashMap<String,Object> stochRsiStrategy(BaseBarSeries series, ClosePriceIndicator closePrice, int period){
        HashMap<String,Object> resultMap = new HashMap<>();

        StochasticRSIIndicator stochasticRSI = new StochasticRSIIndicator(closePrice, period);
        double currentStochRSI = stochasticRSI.getValue(series.getEndIndex()).doubleValue();
        double previousStochRSI = stochasticRSI.getValue(series.getEndIndex() - 1).doubleValue();
        double prePreviousStochRSI = stochasticRSI.getValue(series.getEndIndex() - 2).doubleValue();

        double stochRsiGap = currentStochRSI - previousStochRSI;
        double previousStochRSIGap = previousStochRSI - prePreviousStochRSI;

        boolean isStochRSIGapPositive = stochRsiGap > 0;
        boolean isPreviousStochRSIGapPositive = previousStochRSIGap > 0;

        String commonRemark = "";
        String specialRemark = "";

        commonRemark += "Stochastic RSI(" + currentStochRSI + "[" + stochRsiGap + "]) ";

        int stochRsiSignal = 0;
        if (isStochRSIGapPositive == isPreviousStochRSIGapPositive) {
        // 추세 유지
        } else {
            if (stochRsiGap > 0 && previousStochRSI == 0) {
                specialRemark += CONSOLE_COLORS.BRIGHT_BLUE+"[StochasticRSI시그널]"+"Stochastic RSI 감소 >>> Stochastic RSI 증가 : " + previousStochRSI + " >>> " + currentStochRSI + "(" + previousStochRSIGap + "/" + stochRsiGap + ") "+CONSOLE_COLORS.RESET;
                stochRsiSignal = 1;
            } else if (stochRsiGap < 0 && previousStochRSI == 1) {
                specialRemark += CONSOLE_COLORS.BRIGHT_RED+"[StochasticRSI시그널]"+"Stochastic RSI 증가 >>> Stochastic RSI 감소 : " + previousStochRSI + " >>> " + currentStochRSI + "(" + previousStochRSIGap + "/" + stochRsiGap + ") "+CONSOLE_COLORS.RESET;
                stochRsiSignal = -1;
            }
        }

        resultMap.put("stochRsi", currentStochRSI);
        resultMap.put("stochRsiSignal", stochRsiSignal);
        resultMap.put("commonRemark", commonRemark);
        resultMap.put("specialRemark", specialRemark);
        return resultMap;
    }

    public static BigDecimal calculateROI(TradingEntity tradingEntity) {
        BigDecimal openPrice = tradingEntity.getOpenPrice();
        BigDecimal closePrice = tradingEntity.getClosePrice();
        int leverage = tradingEntity.getLeverage();
        String positionSide = tradingEntity.getPositionSide();

        return calculateROI(openPrice, closePrice, leverage, positionSide);
    }

    public static BigDecimal calculateROI(BigDecimal openPrice, BigDecimal closePrice, int leverage, String positionSide) {
        BigDecimal roi;
        if ("LONG".equalsIgnoreCase(positionSide)) {
            roi = closePrice.subtract(openPrice)
                    .divide(openPrice, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(leverage))
                    .multiply(BigDecimal.valueOf(100));
        } else if ("SHORT".equalsIgnoreCase(positionSide)) {
            roi = openPrice.subtract(closePrice)
                    .divide(openPrice, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(leverage))
                    .multiply(BigDecimal.valueOf(100));
        } else {
            throw new IllegalArgumentException("Invalid position side. Must be 'LONG' or 'SHORT'");
        }
        return roi.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal calculatePnL(TradingEntity tradingEntity) {
        BigDecimal openPrice = tradingEntity.getOpenPrice();
        BigDecimal closePrice = tradingEntity.getClosePrice();
        BigDecimal collateral = tradingEntity.getCollateral();
        int leverage = tradingEntity.getLeverage();
        String positionSide = tradingEntity.getPositionSide();

        return calculatePnL(openPrice, closePrice, leverage, positionSide, collateral);
    }

    public static BigDecimal calculatePnL(BigDecimal openPrice, BigDecimal closePrice, int leverage, String positionSide, BigDecimal collateral) {
        BigDecimal pnl;
        if ("LONG".equalsIgnoreCase(positionSide)) {
            pnl = closePrice.subtract(openPrice)
                    .divide(openPrice, 10, RoundingMode.HALF_UP)
                    .multiply(collateral)
                    .multiply(BigDecimal.valueOf(leverage));
        } else if ("SHORT".equalsIgnoreCase(positionSide)) {
            pnl = openPrice.subtract(closePrice)
                    .divide(openPrice, 10, RoundingMode.HALF_UP)
                    .multiply(collateral)
                    .multiply(BigDecimal.valueOf(leverage));
        } else {
            throw new IllegalArgumentException("Invalid position side. Must be 'LONG' or 'SHORT'");
        }
        return pnl.setScale(2, RoundingMode.HALF_UP);
    }

}