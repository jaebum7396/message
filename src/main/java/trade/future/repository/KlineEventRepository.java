package trade.future.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;
import trade.future.model.entity.KlineEventEntity;

@Repository
public interface KlineEventRepository extends JpaRepository<KlineEventEntity, String>, QuerydslPredicateExecutor<KlineEventEntity>, KlineEventRepositoryQ {

}