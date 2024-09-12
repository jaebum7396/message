package trade.future.model.Rule;

import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.Indicator;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.num.Num;
import trade.future.ml.MLModel;

import java.util.Arrays;
import java.util.List;

@Slf4j
public class MLShortRule implements Rule {
    private MLModel model;
    private List<Indicator<Num>> indicators;
    private double threshold;

    public MLShortRule(MLModel model, List<Indicator<Num>> indicators, double threshold) {
        this.model = model;
        this.indicators = indicators;
        this.threshold = threshold;
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        double[] probabilities = model.predictProbabilities(indicators, index);
        if (probabilities[0] > threshold) {
            //System.out.println(model.explainPrediction(indicators, index));
            //log.info("하락시그널 - Index: " + index + ", Probabilities: " + Arrays.toString(probabilities));
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return String.format("MLShortRule(model=%s, indicators=%d, threshold=%.2f)",
                model.getClass().getSimpleName(),
                indicators.size(),
                threshold);
    }
}