package trade.future.repository;

import trade.future.model.entity.PositionEntity;
import trade.future.model.entity.TradingEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PositionRepositoryQ {
    List<PositionEntity> getPositionByKlineEndTime(String symbol, LocalDateTime endTime, String positionStatus);
}
