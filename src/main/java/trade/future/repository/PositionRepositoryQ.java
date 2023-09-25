package trade.future.repository;

import trade.future.model.entity.KlineEventEntity;
import trade.future.model.entity.PositionEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface PositionRepositoryQ {
    List<PositionEntity> getPositionByKlineEndTime(LocalDateTime endTime, String positionStatus);
}
