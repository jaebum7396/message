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
public class MLRule implements Rule {
    private MLModel model;
    private List<Indicator<Num>> indicators;
    private double upThreshold;
    private double downThreshold;

    public MLRule(MLModel model, List<Indicator<Num>> indicators, double upThreshold, double downThreshold) {
        this.model = model;
        this.indicators = indicators;
        this.upThreshold = upThreshold;
        this.downThreshold = downThreshold;
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        double[] probabilities = model.predictProbabilities(indicators, index);
        // probabilities[0]: 하락 확률 (-1)
        // probabilities[1]: 유지 확률 (0)
        // probabilities[2]: 상승 확률 (1)

        if (probabilities[2] > upThreshold) {
            // 상승 신호
            log.info("상승시그널 Index: " + index + ", Probabilities: " + Arrays.toString(probabilities));
            return true;
        } else if (probabilities[0] > downThreshold) {
            // 하락 신호
            log.info("하락시그널 Index: " + index + ", Probabilities: " + Arrays.toString(probabilities));
            return false;
        } else {
            // 상승도 하락도 아닌 경우, 확률이 더 높은 쪽으로 결정
            return probabilities[2] >= probabilities[0];
        }
    }
}