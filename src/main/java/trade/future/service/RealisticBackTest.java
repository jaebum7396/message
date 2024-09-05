package trade.future.service;

import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.*;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.AndRule;
import org.ta4j.core.rules.OrRule;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Random;

@Slf4j
public class RealisticBackTest {
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_GREEN = "\u001B[32m";

    private final BarSeries series;
    private final Strategy longStrategy;
    private final Strategy shortStrategy;
    private final Duration executionDelay;
    private final double slippagePercent;
    private final Random random;
    private Num entryPrice;
    private Trade.TradeType lastEntryType;
    private int entryIndex;
    private String entryTime;
    private String entryRule;
    private DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    // 승률 계산을 위한 변수들
    private int longTrades = 0;
    private int longWins = 0;
    private double longWinRate = 0;
    private int shortTrades = 0;
    private int shortWins = 0;
    private double shortWinRate = 0;

    // 기대수익 계산을 위한 변수들
    private double longTotalReturn = 0;
    private double shortTotalReturn = 0;
    private double longExpectedReturn = 0;
    private double shortExpectedReturn = 0;
    private static final int TREND_PERIOD = 5;  // 트렌드를 판단할 기간
    private final int leverage = 1;
    private final double feeRate = 0.0004; // 바이낸스 선물 거래 수수료율 (예: 0.0004 for 0.04%)

    public RealisticBackTest(BarSeries series, Strategy longStrategy, Strategy shortStrategy,
                             Duration executionDelay, double slippagePercent) {
        this.series = series;
        this.longStrategy = longStrategy;
        this.shortStrategy = shortStrategy;
        this.executionDelay = executionDelay;
        this.slippagePercent = slippagePercent;
        this.random = new Random();


        System.out.println("===== 백테스트 시작 =====");
        System.out.println("longStrategy Entry Rule: " + getRuleDescription(longStrategy.getEntryRule()));
        System.out.println("longStrategy Exit Rule: " + getRuleDescription(longStrategy.getExitRule()));
        System.out.println("shortStrategy Entry Rule: " + getRuleDescription(shortStrategy.getEntryRule()));
        System.out.println("shortStrategy Exit Rule: " + getRuleDescription(shortStrategy.getExitRule()));
    }

    public TradingRecord run() {
        TradingRecord tradingRecord = new BaseTradingRecord();

        for (int i = 0; i < series.getBarCount(); i++) {
            Bar currentBar = series.getBar(i);
            String currentTrend = getTrend(i);

            // Check for exit signals first
            Position currentPosition = tradingRecord.getCurrentPosition();
            if (currentPosition != null && currentPosition.isOpened()) {
                Trade.TradeType currentType = currentPosition.getEntry().getAmount().doubleValue() > 0 ?
                        Trade.TradeType.BUY : Trade.TradeType.SELL;
                boolean shouldExit = false;

                if (currentType.equals(Trade.TradeType.BUY)) {
                    shouldExit = longStrategy.shouldExit(i) || shortStrategy.shouldEnter(i);
                    if (shouldExit) {
                        //log.info("롱 포지션 청산 시그널 - Index: {}", i);
                    }
                } else if (currentType.equals(Trade.TradeType.SELL)) {
                    shouldExit = shortStrategy.shouldExit(i) || longStrategy.shouldEnter(i);
                    if (shouldExit) {
                        //log.info("숏 포지션 청산 시그널 - Index: {}", i);
                    }
                }

                if (shouldExit) {
                    String exitRule = currentType.equals(Trade.TradeType.BUY) ?
                            getRuleDescription(longStrategy.getExitRule()) :
                            getRuleDescription(shortStrategy.getExitRule());
                    simulateTrade(tradingRecord, currentType, currentBar, i, true, exitRule);
                    continue;
                }
            }

            // Check for entry signals
            if (tradingRecord.getCurrentPosition() == null || !tradingRecord.getCurrentPosition().isOpened()) {
                boolean shouldEnterLong = currentTrend.equals("UP") && longStrategy.shouldEnter(i);
                boolean shouldEnterShort = currentTrend.equals("DOWN") && shortStrategy.shouldEnter(i);

                if (shouldEnterLong) {
                    String entryRule = getRuleDescription(longStrategy.getEntryRule());
                    //log.info("롱 포지션 진입 시그널 - Index: {}", i);
                    simulateTrade(tradingRecord, Trade.TradeType.BUY, currentBar, i, false, entryRule);
                } else if (shouldEnterShort) {
                    String entryRule = getRuleDescription(shortStrategy.getEntryRule());
                    //log.info("숏 포지션 진입 시그널 - Index: {}", i);
                    simulateTrade(tradingRecord, Trade.TradeType.SELL, currentBar, i, false, entryRule);
                }
            }
        }

        printWinRates();
        return tradingRecord;
    }

    private String getTrend(int currentIndex) {
        if (currentIndex < TREND_PERIOD) {
            return "NEUTRAL";  // 데이터가 충분하지 않으면 중립 반환
        }

        double sumClose = 0;
        double firstClose = series.getBar(currentIndex - TREND_PERIOD + 1).getClosePrice().doubleValue();
        double lastClose = series.getBar(currentIndex).getClosePrice().doubleValue();

        for (int i = currentIndex - TREND_PERIOD + 1; i <= currentIndex; i++) {
            sumClose += series.getBar(i).getClosePrice().doubleValue();
        }

        double avgClose = sumClose / TREND_PERIOD;

        if (lastClose > avgClose && lastClose > firstClose) {
            return "UP";
        } else if (lastClose < avgClose && lastClose < firstClose) {
            return "DOWN";
        } else {
            return "NEUTRAL";
        }
    }

    private void simulateTrade(TradingRecord tradingRecord, Trade.TradeType tradeType, Bar bar, int index, boolean isExit, String rule) {
        int delayBars = (int) (executionDelay.toMillis() / bar.getTimePeriod().toMillis());
        int executionIndex = Math.min(index + delayBars, series.getBarCount() - 1);
        Bar executionBar = series.getBar(executionIndex);

        Num executionPrice = executionBar.getClosePrice();
        double slippageFactor = 1 + (slippagePercent / 100) * (random.nextDouble() * 2 - 1);
        executionPrice = executionPrice.multipliedBy(series.numOf(slippageFactor));

        Num amount = series.numOf(1);
        if (tradeType == Trade.TradeType.SELL) {
            amount = amount.negate();
        }

        tradingRecord.operate(executionIndex, executionPrice, amount);

        String timeStr = executionBar.getEndTime().format(timeFormatter);

        if (isExit) {
            Num roi = calculateROI(executionPrice);
            String tradeColor = (lastEntryType == Trade.TradeType.BUY) ? ANSI_GREEN : ANSI_RED;
            String roiColor = roi.isPositive() ? ANSI_GREEN : ANSI_RED;

            //System.out.printf("%sENTER %s[%d/%.5f/%s] => EXIT %s[%d/%.5f/%s]%s | ROI: %s%.2f%%%s | Entry: %s | Exit: %s%n",
            //        tradeColor, lastEntryType, entryIndex, entryPrice.doubleValue(), entryTime,
            //        lastEntryType, executionIndex, executionPrice.doubleValue(), timeStr, ANSI_RESET,
            //        roiColor, roi.multipliedBy(series.numOf(100)).doubleValue(), ANSI_RESET,
            //        entryRule, rule);

            // 승률과 기대수익 계산을 위한 정보 업데이트
            double roiValue = roi.doubleValue();
            if (lastEntryType == Trade.TradeType.BUY) {
                longTrades++;
                if (roi.isPositive()) longWins++;
                longTotalReturn += roiValue;
            } else {
                shortTrades++;
                if (roi.isPositive()) shortWins++;
                shortTotalReturn += roiValue;
            }
        }
    }

    private Num calculateROI(Num exitPrice) {
        double entryFee = entryPrice.doubleValue() * feeRate;
        double exitFee = exitPrice.doubleValue() * feeRate;
        double totalFee = entryFee + exitFee;

        if (lastEntryType == Trade.TradeType.BUY) {
            double profit = (exitPrice.doubleValue() - entryPrice.doubleValue()) * leverage;
            return series.numOf((profit - totalFee) / entryPrice.doubleValue());
        } else {
            double profit = (entryPrice.doubleValue() - exitPrice.doubleValue()) * leverage;
            return series.numOf((profit - totalFee) / entryPrice.doubleValue());
        }
    }

    private void printWinRates() {
        longWinRate = longTrades > 0 ? (double) longWins / longTrades * 100 : 0;
        shortWinRate = shortTrades > 0 ? (double) shortWins / shortTrades * 100 : 0;

        // 기대수익 계산 (이미 레버리지와 수수료가 반영된 값)
        longExpectedReturn = longTrades > 0 ? longTotalReturn / longTrades * 100 : 0;
        shortExpectedReturn = shortTrades > 0 ? shortTotalReturn / shortTrades * 100 : 0;

        System.out.println("\n===== 백테스트 결과 =====");
        System.out.printf("레버리지: %dx, 수수료율: %.2f%%%n", leverage, feeRate * 100);
        System.out.printf("롱 포지션 승률: %.2f%% (%d/%d)%n", longWinRate, longWins, longTrades);
        System.out.printf("롱 포지션 기대수익: %.2f%%%n", longExpectedReturn);
        System.out.printf("숏 포지션 승률: %.2f%% (%d/%d)%n", shortWinRate, shortWins, shortTrades);
        System.out.printf("숏 포지션 기대수익: %.2f%%%n", shortExpectedReturn);
        System.out.println("=======================");
    }

    private String getRuleDescription(Rule rule) {
        if (rule instanceof AndRule) {
            AndRule andRule = (AndRule) rule;
            return "AND(" + getRuleDescription(andRule.getRule1()) + "," +
                    getRuleDescription(andRule.getRule2()) + ")";
        } else if (rule instanceof OrRule) {
            OrRule orRule = (OrRule) rule;
            return "OR(" + getRuleDescription(orRule.getRule1()) + "," +
                    getRuleDescription(orRule.getRule2()) + ")";
        } else {
            // Rule의 toString() 메서드 호출
            return rule.toString();
        }
    }

    public int getLongWins() {
        return longWins;
    }

    public double getLongWinRate() {
        return longWinRate;
    }

    public int getShortWins() {
        return shortWins;
    }

    public double getShortWinRate() {
        return shortWinRate;
    }

    public double getLongExpectedReturn() {
        return longExpectedReturn;
    }

    public double getShortExpectedReturn() {
        return shortExpectedReturn;
    }

    public String getBestPosition() {
        double longScore = longWinRate * longExpectedReturn;
        double shortScore = shortWinRate * shortExpectedReturn;

        if (longScore > shortScore) {
            return "LONG";
        } else if (shortScore > longScore) {
            return "SHORT";
        } else {
            return "NEUTRAL";
        }
    }
}