package trade.future.repository;

import trade.future.model.entity.PositionEntity;

import java.time.LocalDateTime;
import java.util.List;

public interface PositionRepositoryQ {
    List<PositionEntity> getPositionByKlineEndTime(String symbol, LocalDateTime endTime, String positionStatus);
}
