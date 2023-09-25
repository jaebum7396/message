package trade.future.repository;

import trade.future.model.entity.KlineEventEntity;

import java.math.BigDecimal;
import java.util.List;

public interface KlineEventRepositoryQ {
    List<KlineEventEntity> findKlineEventsWithPlusGoalPriceLessThanCurrentPrice(String symbol, BigDecimal currentPrice);
    List<KlineEventEntity> findKlineEventsWithMinusGoalPriceGreaterThanCurrentPrice(String symbol, BigDecimal currentPrice);
}
