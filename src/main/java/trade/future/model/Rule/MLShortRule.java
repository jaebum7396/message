package trade.future.model.Rule;

import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.Indicator;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
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

    public MLShortRule(MLModel model, List<Indicator<Num>> indicators, double baseThreshold,
                      double volatilityThreshold) {
        this.model = model;
        this.indicators = indicators;
        this.baseThreshold = baseThreshold;
        this.volatilityThreshold = volatilityThreshold;

        // Find the index of RelativeATRIndicator and ADXIndicator in the indicators list
        this.relativeATRIndex = findIndicatorIndex(RelativeATRIndicator.class);
        this.adxIndex = findIndicatorIndex(ADXIndicator.class);

        if (relativeATRIndex == -1 || adxIndex == -1) {
            throw new IllegalArgumentException("Required indicators (RelativeATR or ADX) not found in the indicators list");
        }
    }

    private int findIndicatorIndex(Class<?> indicatorClass) {
        for (int i = 0; i < indicators.size(); i++) {
            if (indicatorClass.isInstance(indicators.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        double[] probabilities = model.predictProbabilities(indicators, index);
        double currentThreshold = calculateDynamicThreshold(index);

        if (probabilities[0] > currentThreshold) {
            log.info("하락시그널 - Index: {}, Probabilities: {}, Threshold: {}, RelativeATR: {}, ADX: {}",
                    index, Arrays.toString(probabilities), currentThreshold,
                    indicators.get(relativeATRIndex).getValue(index).doubleValue(),
                    indicators.get(adxIndex).getValue(index).doubleValue());
            return true;
        }
        return false;
    }

    private double calculateDynamicThreshold(int index) {
        double relativeATR = indicators.get(relativeATRIndex).getValue(index).doubleValue();
        double adx = indicators.get(adxIndex).getValue(index).doubleValue();

        if (relativeATR > volatilityThreshold && adx > 25) {  // ADX > 25 indicates a strong trend
            return baseThreshold + 0.1;  // Increase threshold in high volatility and strong trend
        } else if (relativeATR > volatilityThreshold) {
            return baseThreshold + 0.05;  // Slightly increase threshold in high volatility
        } else {
            return baseThreshold;
        }
    }

    @Override
    public String toString() {
        return String.format("MLLongRule(model=%s, indicators=%d, baseThreshold=%.2f, volatilityThreshold=%.2f)",
                model.getClass().getSimpleName(),
                indicators.size(),
                baseThreshold,
                volatilityThreshold);
    }
}