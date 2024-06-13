package trade.future.repository;

import trade.future.model.entity.EventEntity;
import trade.future.model.entity.TradingEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface EventRepositoryQ {
    List<EventEntity> findKlineEventsWithPlusGoalPriceLessThanCurrentPrice(String symbol, BigDecimal currentPrice);
    List<EventEntity> findKlineEventsWithMinusGoalPriceGreaterThanCurrentPrice(String symbol, BigDecimal currentPrice);
    Optional<EventEntity> findEventBySymbolAndPositionStatus(String symbol, String positionStatus);
}
