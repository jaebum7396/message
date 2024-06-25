package trade.future.repository;

import trade.future.model.entity.TradingEntity;

import java.util.List;

public interface TradingRepositoryQ {
    List<TradingEntity> findByUserCd(String userCd);
}
