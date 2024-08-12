package trade.future.model.Rule;

import org.ta4j.core.Rule;
import org.ta4j.core.Trade;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

public class ProfitableRule implements Rule {
    private final ClosePriceIndicator closePrice;
    private final boolean isLong;

    public ProfitableRule(ClosePriceIndicator closePrice, boolean isLong) {
        this.closePrice = closePrice;
        this.isLong = isLong;
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        if (tradingRecord.getCurrentPosition().isOpened()) {
            Trade lastTrade = tradingRecord.getCurrentPosition().getEntry();
            Num entryPrice = lastTrade.getNetPrice();
            Num currentPrice = closePrice.getValue(index);

            if (isLong) {
                return currentPrice.isGreaterThan(entryPrice);
            } else {
                return currentPrice.isLessThan(entryPrice);
            }
        }
        return false;
    }
}