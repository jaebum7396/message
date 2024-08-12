package trade.future.model.Rule;

import org.ta4j.core.Rule;
import org.ta4j.core.Trade;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

public class MinimumProfitRule implements Rule {
    private final ClosePriceIndicator closePrice;
    private final boolean isLong;
    private final double minimumProfitPercentage;

    public MinimumProfitRule(ClosePriceIndicator closePrice, boolean isLong, double minimumProfitPercentage) {
        this.closePrice = closePrice;
        this.isLong = isLong;
        this.minimumProfitPercentage = minimumProfitPercentage;
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        if (tradingRecord.getCurrentPosition().isOpened()) {
            Trade entryTrade = tradingRecord.getCurrentPosition().getEntry();
            Num entryPrice = entryTrade.getNetPrice();
            Num currentPrice = closePrice.getValue(index);

            double profitPercentage;
            if (isLong) {
                profitPercentage = (currentPrice.doubleValue() / entryPrice.doubleValue() - 1) * 100;
            } else {
                profitPercentage = (1 - currentPrice.doubleValue() / entryPrice.doubleValue()) * 100;
            }

            return profitPercentage >= minimumProfitPercentage;
        }
        return false;
    }
}
