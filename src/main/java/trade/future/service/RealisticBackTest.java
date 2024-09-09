package trade.future.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.*;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.AndRule;
import org.ta4j.core.rules.OrRule;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
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

    private int longTrades = 0;
    private int longWins = 0;
    private double longWinRate = 0;
    private int shortTrades = 0;
    private int shortWins = 0;
    private double shortWinRate = 0;

    private double longTotalReturn = 0;
    private double shortTotalReturn = 0;
    private double longExpectedReturn = 0;
    private double shortExpectedReturn = 0;

    private static final int TREND_PERIOD = 5;
    private final int leverage;
    private final double feeRate;
    @Getter
    private TradingRecord tradingRecord;

    public enum BarEvent {
        LONG_ENTRY,
        LONG_EXIT,
        SHORT_ENTRY,
        SHORT_EXIT,
        NO_EVENT
    }

    public enum SignalType {
        LONG_ENTRY,
        LONG_EXIT,
        SHORT_ENTRY,
        SHORT_EXIT,
        NO_SIGNAL
    }

    public RealisticBackTest(BarSeries series, Strategy longStrategy, Strategy shortStrategy,
                             Duration executionDelay, double slippagePercent) {
        this.series = series;
        this.longStrategy = longStrategy;
        this.shortStrategy = shortStrategy;
        this.executionDelay = executionDelay;
        this.slippagePercent = slippagePercent;
        this.random = new Random();
        this.leverage = 5;
        this.feeRate = 0.0004;
        this.entryPrice = series.numOf(0);
        this.lastEntryType = null;
        this.tradingRecord = new BaseTradingRecord();

        log.info("======= 백테스트 =======");
        //log.info("longStrategy Entry Rule: {}", getRuleDescription(longStrategy.getEntryRule()));
        //log.info("longStrategy Exit Rule: {}", getRuleDescription(longStrategy.getExitRule()));
        //log.info("shortStrategy Entry Rule: {}", getRuleDescription(shortStrategy.getEntryRule()));
        //log.info("shortStrategy Exit Rule: {}", getRuleDescription(shortStrategy.getExitRule()));
    }

    private BarEvent processBar(int i) throws Exception {
        Bar currentBar = series.getBar(i);
        String currentTrend = getTrend(i);

        Position currentPosition = tradingRecord.getCurrentPosition();
        if (currentPosition != null && currentPosition.isOpened()) {
            Trade.TradeType currentType = currentPosition.getEntry().getAmount().doubleValue() > 0 ?
                    Trade.TradeType.BUY : Trade.TradeType.SELL;
            boolean shouldExit = false;

            if (currentType.equals(Trade.TradeType.BUY)) {
                shouldExit = longStrategy.shouldExit(i) || shortStrategy.shouldEnter(i);
                if (shouldExit) {
                    String exitRule = getRuleDescription(longStrategy.getExitRule());
                    simulateTrade(tradingRecord, currentType, currentBar, i, true, exitRule);
                    return BarEvent.LONG_EXIT;
                }
            } else if (currentType.equals(Trade.TradeType.SELL)) {
                shouldExit = shortStrategy.shouldExit(i) || longStrategy.shouldEnter(i);
                if (shouldExit) {
                    String exitRule = getRuleDescription(shortStrategy.getExitRule());
                    simulateTrade(tradingRecord, currentType, currentBar, i, true, exitRule);
                    return BarEvent.SHORT_EXIT;
                }
            }
        } else {
            boolean shouldEnterLong = currentTrend.equals("UP") && longStrategy.shouldEnter(i);
            boolean shouldEnterShort = currentTrend.equals("DOWN") && shortStrategy.shouldEnter(i);

            if (shouldEnterLong) {
                String entryRule = getRuleDescription(longStrategy.getEntryRule());
                simulateTrade(tradingRecord, Trade.TradeType.BUY, currentBar, i, false, entryRule);
                return BarEvent.LONG_ENTRY;
            } else if (shouldEnterShort) {
                String entryRule = getRuleDescription(shortStrategy.getEntryRule());
                simulateTrade(tradingRecord, Trade.TradeType.SELL, currentBar, i, false, entryRule);
                return BarEvent.SHORT_ENTRY;
            }
        }

        return BarEvent.NO_EVENT;
    }

    public TradingRecord run() {
        try {
            for (int i = 0; i < series.getBarCount(); i++) {
                BarEvent barEvent = processBar(i);
                //System.out.println("index("+i+") : "+barEvent);
            }
        } catch (Exception e){
            log.error("run Error : "+e.getMessage());
        }
        printWinRates();
        return tradingRecord;
    }

    public BarEvent addBar(Bar newBar) {
        BarEvent barEvent = BarEvent.NO_EVENT;
        try {
            int lastIndex = series.getEndIndex();
            if (lastIndex >= 0) {
                Bar lastBar = series.getBar(lastIndex);
                System.out.println("lastBar : "+lastBar.getEndTime().format(timeFormatter)+" / newBar : "+newBar.getEndTime().format(timeFormatter));
                if (lastBar.getEndTime().equals(newBar.getEndTime())) {
                    // 시간이 같으면 마지막 캔들을 대체
                    series.addBar(newBar, true);  // true는 기존 바를 대체한다는 의미
                    barEvent = processBar(lastIndex);
                } else {
                    // 시간이 다르면 새로운 캔들로 추가
                    series.addBar(newBar);
                    int newIndex = series.getEndIndex();
                    barEvent = processBar(newIndex);
                }
            } else {
                // 시리즈가 비어있는 경우
                series.addBar(newBar);
                barEvent = processBar(0);
            }
        } catch (Exception e) {
            log.error("addBar Error : " + e.getMessage(), e);
        }
        return barEvent;
    }

    public SignalType checkSignal(int index) {
        if (index < 0 || index >= series.getBarCount()) {
            return SignalType.NO_SIGNAL;
        }

        String currentTrend = getTrend(index);

        Position currentPosition = tradingRecord.getCurrentPosition();
        boolean isInPosition = (currentPosition != null && currentPosition.isOpened());
        Trade.TradeType currentType = isInPosition ?
                (currentPosition.getEntry().getAmount().doubleValue() > 0 ? Trade.TradeType.BUY : Trade.TradeType.SELL)
                : null;

        if (isInPosition) {
            if (currentType == Trade.TradeType.BUY) {
                if (longStrategy.shouldExit(index) || shortStrategy.shouldEnter(index)) {
                    return SignalType.LONG_EXIT;
                }
            } else if (currentType == Trade.TradeType.SELL) {
                if (shortStrategy.shouldExit(index) || longStrategy.shouldEnter(index)) {
                    return SignalType.SHORT_EXIT;
                }
            }
        } else {
            if (currentTrend.equals("UP") && longStrategy.shouldEnter(index)) {
                return SignalType.LONG_ENTRY;
            } else if (currentTrend.equals("DOWN") && shortStrategy.shouldEnter(index)) {
                return SignalType.SHORT_ENTRY;
            }
        }

        return SignalType.NO_SIGNAL;
    }

    private String getTrend(int currentIndex) {
        if (currentIndex < TREND_PERIOD) {
            return "NEUTRAL";
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
            entryRule = rule + " | Trend: " + trend;
        } else {
            Num roi = calculateROI(executionPrice);
            String tradeColor = (lastEntryType == Trade.TradeType.BUY) ? ANSI_GREEN : ANSI_RED;
            String roiColor = roi.isPositive() ? ANSI_GREEN : ANSI_RED;

            //log.info("{}ENTER {}[{}/{}] => EXIT {}[{}/{}]{} | ROI: {}{}%{} | Entry: {} | Exit: {}",
            //        tradeColor, lastEntryType, entryIndex, entryPrice.doubleValue(),
            //        lastEntryType, executionIndex, executionPrice.doubleValue(), ANSI_RESET,
            //        roiColor, roi.multipliedBy(series.numOf(100)).doubleValue(), ANSI_RESET,
            //        entryRule, rule);

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
        if (entryPrice == null || exitPrice == null) {
            log.error("Entry price or exit price is null. Entry price: {}, Exit price: {}", entryPrice, exitPrice);
            return series.numOf(0);
        }

        try {
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
        } catch (Exception e) {
            log.error("Error calculating ROI: {}", e.getMessage(), e);
            return series.numOf(0);
        }
    }

    private void printWinRates() {
        longWinRate = longTrades > 0 ? (double) longWins / longTrades * 100 : 0;
        shortWinRate = shortTrades > 0 ? (double) shortWins / shortTrades * 100 : 0;

        longExpectedReturn = longTrades > 0 ? longTotalReturn / longTrades * 100 : 0;
        shortExpectedReturn = shortTrades > 0 ? shortTotalReturn / shortTrades * 100 : 0;
        log.info("레버리지: {}x, 수수료율: {}%", leverage, feeRate * 100);
        log.info("롱 포지션 승률: {}% ({}/{})", String.format("%.2f", longWinRate), longWins, longTrades);
        log.info("롱 포지션 기대수익: {}%", String.format("%.2f", longExpectedReturn));
        log.info("숏 포지션 승률: {}% ({}/{})", String.format("%.2f", shortWinRate), shortWins, shortTrades);
        log.info("숏 포지션 기대수익: {}%", String.format("%.2f", shortExpectedReturn));
        log.info("=======================");
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
            return rule.toString();
        }
    }

    // Getter methods...

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