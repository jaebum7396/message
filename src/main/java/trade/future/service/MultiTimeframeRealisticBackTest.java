package trade.future.service;

import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.*;
import org.ta4j.core.num.Num;
import trade.future.ml.MLModel;
import trade.future.model.entity.TradingEntity;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static trade.common.캔들유틸.*;

@Slf4j
public class MultiTimeframeRealisticBackTest extends RealisticBackTest {
    private final ConcurrentHashMap<String, BaseBarSeries> seriesMap;
    private final ConcurrentHashMap<String, MLModel> mlModelMap;
    private final ConcurrentHashMap<String, Strategy> strategyMap;
    private final String symbol;
    private final List<String> timeframes;
    private final String primaryTimeframe;

    public MultiTimeframeRealisticBackTest(
            String symbol,
            List<String> timeframes,
            String primaryTimeframe,
            TradingEntity tradingEntity,
            Duration executionDelay,
            double slippagePercent,
            ConcurrentHashMap<String, BaseBarSeries> seriesMap,
            ConcurrentHashMap<String, MLModel> mlModelMap,
            ConcurrentHashMap<String, Strategy> strategyMap) {
        super(seriesMap.get(symbol + "_" + primaryTimeframe), tradingEntity,
                mlModelMap.get(symbol + "_" + primaryTimeframe + "_LONG"),
                mlModelMap.get(symbol + "_" + primaryTimeframe + "_SHORT"),
                strategyMap.get(symbol + "_" + primaryTimeframe + "_LONG"),
                strategyMap.get(symbol + "_" + primaryTimeframe + "_SHORT"),
                executionDelay, slippagePercent);

        this.symbol = symbol;
        this.timeframes = timeframes;
        this.primaryTimeframe = primaryTimeframe;
        this.seriesMap = seriesMap;
        this.mlModelMap = mlModelMap;
        this.strategyMap = strategyMap;
    }

    @Override
    public TradingRecord run() {
        try {
            BaseBarSeries primarySeries = seriesMap.get(symbol + "_" + primaryTimeframe);
            for (int i = 0; i < primarySeries.getBarCount(); i++) {
                BarEvent barEvent = processBar(i);
                // 필요한 경우 barEvent를 사용하여 추가 로직 수행
            }
        } catch (Exception e) {
            log.error("run Error : " + e.getMessage());
        }
        printWinRates();
        return getTradingRecord();
    }

    protected BarEvent processBar(int i) throws Exception {
        BaseBarSeries primarySeries = seriesMap.get(symbol + "_" + primaryTimeframe);
        Bar currentBar = primarySeries.getBar(i);
        Map<String, Integer> timeframeIndices = synchronizeTimeframes(currentBar);

        String currentTrend = getTrend(i);

        boolean longEntrySignal = checkLongEntrySignal(timeframeIndices);
        boolean shortEntrySignal = checkShortEntrySignal(timeframeIndices);
        boolean longExitSignal = checkLongExitSignal(timeframeIndices);
        boolean shortExitSignal = checkShortExitSignal(timeframeIndices);

        Position currentPosition = getTradingRecord().getCurrentPosition();
        if (currentPosition != null && currentPosition.isOpened()) {
            Trade.TradeType currentType = currentPosition.getEntry().getAmount().doubleValue() > 0 ?
                    Trade.TradeType.BUY : Trade.TradeType.SELL;

            if (currentType.equals(Trade.TradeType.BUY) && longExitSignal) {
                String exitRule = getRuleDescription(strategyMap.get(symbol + "_" + primaryTimeframe + "_LONG").getExitRule());
                simulateTrade(getTradingRecord(), currentType, currentBar, i, true, exitRule);
                return BarEvent.LONG_EXIT;
            } else if (currentType.equals(Trade.TradeType.SELL) && shortExitSignal) {
                String exitRule = getRuleDescription(strategyMap.get(symbol + "_" + primaryTimeframe + "_SHORT").getExitRule());
                simulateTrade(getTradingRecord(), currentType, currentBar, i, true, exitRule);
                return BarEvent.SHORT_EXIT;
            }
        } else {
            if (longEntrySignal) {
                String entryRule = getRuleDescription(strategyMap.get(symbol + "_" + primaryTimeframe + "_LONG").getEntryRule());
                simulateTrade(getTradingRecord(), Trade.TradeType.BUY, currentBar, i, false, entryRule);
                return BarEvent.LONG_ENTRY;
            } else if (shortEntrySignal) {
                String entryRule = getRuleDescription(strategyMap.get(symbol + "_" + primaryTimeframe + "_SHORT").getEntryRule());
                simulateTrade(getTradingRecord(), Trade.TradeType.SELL, currentBar, i, false, entryRule);
                return BarEvent.SHORT_ENTRY;
            }
        }

        return BarEvent.NO_EVENT;
    }

    private Map<String, Integer> synchronizeTimeframes(Bar currentBar) {
        Map<String, Integer> indices = new HashMap<>();
        for (String timeframe : timeframes) {
            BaseBarSeries series = seriesMap.get(symbol + "_" + timeframe);
            int index = findIndexForTime(series, currentBar.getEndTime());
            indices.put(timeframe, index);
        }
        return indices;
    }

    private boolean checkLongEntrySignal(Map<String, Integer> timeframeIndices) {
        boolean signal = true;
        for (String timeframe : timeframes) {
            int index = timeframeIndices.get(timeframe);
            BaseBarSeries series = seriesMap.get(symbol + "_" + timeframe);
            MLModel model = mlModelMap.get(symbol + "_" + timeframe + "_LONG");
            Strategy strategy = strategyMap.get(symbol + "_" + timeframe + "_LONG");

            List<Indicator<Num>> indicators = initializeLongIndicators(series, tradingEntity.getShortMovingPeriod(), tradingEntity.getLongMovingPeriod());
            double[] predict = model.predictProbabilities(indicators, index);

            signal &= predict[2] > 0.4 && strategy.shouldEnter(index);
        }
        return signal;
    }

    private boolean checkShortEntrySignal(Map<String, Integer> timeframeIndices) {
        boolean signal = true;
        for (String timeframe : timeframes) {
            int index = timeframeIndices.get(timeframe);
            BaseBarSeries series = seriesMap.get(symbol + "_" + timeframe);
            MLModel model = mlModelMap.get(symbol + "_" + timeframe + "_SHORT");
            Strategy strategy = strategyMap.get(symbol + "_" + timeframe + "_SHORT");

            List<Indicator<Num>> indicators = initializeShortIndicators(series, tradingEntity.getShortMovingPeriod(), tradingEntity.getLongMovingPeriod());
            double[] predict = model.predictProbabilities(indicators, index);

            signal &= predict[0] > 0.5 && strategy.shouldEnter(index);
        }
        return signal;
    }

    private boolean checkLongExitSignal(Map<String, Integer> timeframeIndices) {
        boolean signal = false;
        for (String timeframe : timeframes) {
            int index = timeframeIndices.get(timeframe);
            BaseBarSeries series = seriesMap.get(symbol + "_" + timeframe);
            MLModel longModel = mlModelMap.get(symbol + "_" + timeframe + "_LONG");
            MLModel shortModel = mlModelMap.get(symbol + "_" + timeframe + "_SHORT");
            Strategy strategy = strategyMap.get(symbol + "_" + timeframe + "_LONG");

            List<Indicator<Num>> indicators = initializeLongIndicators(series, tradingEntity.getShortMovingPeriod(), tradingEntity.getLongMovingPeriod());
            double[] longPredict = longModel.predictProbabilities(indicators, index);
            double[] shortPredict = shortModel.predictProbabilities(indicators, index);

            signal |= (longPredict[0] > 0.4 || shortPredict[0] > 0.5) && strategy.shouldExit(index);
        }
        return signal;
    }

    private boolean checkShortExitSignal(Map<String, Integer> timeframeIndices) {
        boolean signal = false;
        for (String timeframe : timeframes) {
            int index = timeframeIndices.get(timeframe);
            BaseBarSeries series = seriesMap.get(symbol + "_" + timeframe);
            MLModel longModel = mlModelMap.get(symbol + "_" + timeframe + "_LONG");
            MLModel shortModel = mlModelMap.get(symbol + "_" + timeframe + "_SHORT");
            Strategy strategy = strategyMap.get(symbol + "_" + timeframe + "_SHORT");

            List<Indicator<Num>> indicators = initializeShortIndicators(series, tradingEntity.getShortMovingPeriod(), tradingEntity.getLongMovingPeriod());
            double[] longPredict = longModel.predictProbabilities(indicators, index);
            double[] shortPredict = shortModel.predictProbabilities(indicators, index);

            signal |= (shortPredict[2] > 0.5 || longPredict[2] > 0.4) && strategy.shouldExit(index);
        }
        return signal;
    }
}