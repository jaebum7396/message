package trade.future.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;
import trade.future.model.entity.PositionEntity;
import trade.future.model.entity.TradingEntity;

import java.util.Optional;

@Repository
public interface PositionRepository extends JpaRepository<PositionEntity, String>, QuerydslPredicateExecutor<PositionEntity>, PositionRepositoryQ {

}