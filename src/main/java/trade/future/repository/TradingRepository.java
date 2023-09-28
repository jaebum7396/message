package trade.future.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;
import trade.future.model.entity.PositionEntity;
import trade.future.model.entity.TradingEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface TradingRepository extends JpaRepository<TradingEntity, String>, QuerydslPredicateExecutor<TradingEntity>, TradingRepositoryQ {
    Optional<TradingEntity> findBySymbolAndTradingStatus(String symbol, String tradingStatus);
    Optional<TradingEntity> findByStreamId(int streamId);
}