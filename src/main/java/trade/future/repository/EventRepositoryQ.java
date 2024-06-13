package trade.future.repository;

import trade.future.model.entity.EventEntity;

import java.math.BigDecimal;
import java.util.List;

public interface EventRepositoryQ {
    List<EventEntity> findKlineEventsWithPlusGoalPriceLessThanCurrentPrice(String symbol, BigDecimal currentPrice);
    List<EventEntity> findKlineEventsWithMinusGoalPriceGreaterThanCurrentPrice(String symbol, BigDecimal currentPrice);
}
