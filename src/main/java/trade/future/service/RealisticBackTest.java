package trade.future.service;

import org.ta4j.core.*;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.AndRule;
import org.ta4j.core.rules.OrRule;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Random;

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
    public int getLongWins() {
        return longWins;
    }
    public double getLongWinRate() {
        return longWinRate;
    }

    private int shortTrades = 0;
    private int shortWins = 0;
    private double shortWinRate = 0;
    public int getShortWins() {
        return shortTrades;
    }
    public double getShortWinRate() {
        return shortWinRate;
    }
    private static final int TREND_PERIOD = 5;  // 트렌드를 판단할 기간

    public RealisticBackTest(BarSeries series, Strategy longStrategy, Strategy shortStrategy,
                             Duration executionDelay, double slippagePercent) {
        this.series = series;
        this.longStrategy = longStrategy;
        this.shortStrategy = shortStrategy;
        this.executionDelay = executionDelay;
        this.slippagePercent = slippagePercent;
        this.random = new Random();
    }

    public TradingRecord run() {
        TradingRecord tradingRecord = new BaseTradingRecord();

        for (int i = 0; i < series.getBarCount(); i++) {
            Bar currentBar = series.getBar(i);
            String currentTrend = getTrend(i);

            // Check for exit signals first
            Position currentPosition = tradingRecord.getCurrentPosition();
            if (currentPosition != null && currentPosition.isOpened()) {
                Trade.TradeType currentType = currentPosition.getEntry().getType();
                boolean shouldExit = (currentType.equals(Trade.TradeType.BUY) && longStrategy.shouldExit(i)) ||
                        (currentType.equals(Trade.TradeType.SELL) && shortStrategy.shouldExit(i));
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
                if (currentTrend.equals("UP") && longStrategy.shouldEnter(i)) {
                    String entryRule = getRuleDescription(longStrategy.getEntryRule());
                    simulateTrade(tradingRecord, Trade.TradeType.BUY, currentBar, i, false, entryRule);
                } else if (currentTrend.equals("DOWN") && shortStrategy.shouldEnter(i)) {
                    String entryRule = getRuleDescription(shortStrategy.getEntryRule());
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

        if (!isExit) {
            entryPrice = executionPrice;
            lastEntryType = tradeType;
            entryIndex = executionIndex;
            entryTime = timeStr;
            String trend = getTrend(index);
            entryRule = rule + " | Trend: " + trend;  // 트렌드 정보 추가
        } else {
            Num roi = calculateROI(executionPrice);
            String tradeColor = (lastEntryType == Trade.TradeType.BUY) ? ANSI_GREEN : ANSI_RED;
            String roiColor = roi.isPositive() ? ANSI_GREEN : ANSI_RED;

            //System.out.printf("%sENTER %s[%d/%.5f/%s] => EXIT %s[%d/%.5f/%s]%s | ROI: %s%.2f%%%s | Entry: %s | Exit: %s%n",
            //        tradeColor, lastEntryType, entryIndex, entryPrice.doubleValue(), entryTime,
            //        lastEntryType, executionIndex, executionPrice.doubleValue(), timeStr, ANSI_RESET,
            //        roiColor, roi.multipliedBy(series.numOf(100)).doubleValue(), ANSI_RESET,
            //        entryRule, rule);

            // 승률 계산을 위한 정보 업데이트
            if (lastEntryType == Trade.TradeType.BUY) {
                longTrades++;
                if (roi.isPositive()) longWins++;
            } else {
                shortTrades++;
                if (roi.isPositive()) shortWins++;
            }
        }
    }

    private Num calculateROI(Num exitPrice) {
        if (lastEntryType == Trade.TradeType.BUY) {
            return exitPrice.minus(entryPrice).dividedBy(entryPrice);
        } else {
            return entryPrice.minus(exitPrice).dividedBy(entryPrice);
        }
    }

    private void printWinRates() {
        longWinRate = longTrades > 0 ? (double) longWins / longTrades * 100 : 0;
        shortWinRate = shortTrades > 0 ? (double) shortWins / shortTrades * 100 : 0;

        System.out.println("\n===== 백테스트 결과 =====");
        System.out.printf("롱 포지션 승률: %.2f%% (%d/%d)%n", longWinRate, longWins, longTrades);
        System.out.printf("숏 포지션 승률: %.2f%% (%d/%d)%n", shortWinRate, shortWins, shortTrades);
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
}