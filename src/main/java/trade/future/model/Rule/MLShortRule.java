package trade.future.model.Rule;

import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.Indicator;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.num.Num;
import trade.future.ml.MLModel;

import java.util.Arrays;
import java.util.List;

@Slf4j
public class MLShortRule implements Rule {
    private MLModel model;
    private List<Indicator<Num>> indicators;
    private double baseThreshold;
    private double volatilityThreshold;
    private int relativeATRIndex;
    private int adxIndex;
    private int smaShortIndex;
    private int smaLongIndex;

    public MLShortRule(MLModel model, List<Indicator<Num>> indicators, double baseThreshold,
                       double volatilityThreshold) {
        this.model = model;
        this.indicators = indicators;
        this.baseThreshold = baseThreshold;
        this.volatilityThreshold = volatilityThreshold;

        // Find the index of required indicators in the indicators list
        this.relativeATRIndex = findIndicatorIndex(RelativeATRIndicator.class);
        this.adxIndex = findIndicatorIndex(ADXIndicator.class);
        this.smaShortIndex = findIndicatorIndex(SMAIndicator.class, 0);  // Assuming short SMA is added first
        this.smaLongIndex = findIndicatorIndex(SMAIndicator.class, 1);   // Assuming long SMA is added second

        if (relativeATRIndex == -1 || adxIndex == -1 || smaShortIndex == -1 || smaLongIndex == -1) {
            throw new IllegalArgumentException("Required indicators not found in the indicators list");
        }
    }

    private int findIndicatorIndex(Class<?> indicatorClass) {
        return findIndicatorIndex(indicatorClass, 0);
    }

    private int findIndicatorIndex(Class<?> indicatorClass, int occurrence) {
        int count = 0;
        for (int i = 0; i < indicators.size(); i++) {
            if (indicatorClass.isInstance(indicators.get(i))) {
                if (count == occurrence) {
                    return i;
                }
                count++;
            }
        }
        return -1;
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        double[] probabilities = model.predictProbabilities(indicators, index);
        double currentThreshold = calculateDynamicThreshold(index);

        if (probabilities[0] > currentThreshold) {
            log.info("하락시그널 - Index: {}, Probabilities: {}, Threshold: {}, RelativeATR: {}, ADX: {}, IsDowntrend: {}",
                    index, Arrays.toString(probabilities), currentThreshold,
                    indicators.get(relativeATRIndex).getValue(index).doubleValue(),
                    indicators.get(adxIndex).getValue(index).doubleValue(),
                    isDowntrend(index));
            return true;
        }
        return false;
    }

    private double calculateDynamicThreshold(int index) {
        double relativeATR = indicators.get(relativeATRIndex).getValue(index).doubleValue();
        double adx = indicators.get(adxIndex).getValue(index).doubleValue();

        double threshold = baseThreshold;

        //if (relativeATR > volatilityThreshold || adx > 30) {  // High volatility or strong trend
        //    threshold += 0.05;  // Increase threshold slightly
        //}

        //if (isDowntrend(index)) {
        //    threshold += 0.2;  // Decrease threshold (equivalent to adding weight) in downtrend
        //}

        return threshold;
    }

    private boolean isDowntrend(int index) {
        Num shortSMA = indicators.get(smaShortIndex).getValue(index);
        Num longSMA = indicators.get(smaLongIndex).getValue(index);
        return shortSMA.isLessThan(longSMA);
    }

    @Override
    public String toString() {
        return String.format("MLShortRule(model=%s, indicators=%d, baseThreshold=%.2f, volatilityThreshold=%.2f)",
                model.getClass().getSimpleName(),
                indicators.size(),
                baseThreshold,
                volatilityThreshold);
    }
}