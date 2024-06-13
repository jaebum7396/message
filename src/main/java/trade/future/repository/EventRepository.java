package trade.future.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;
import trade.future.model.entity.EventEntity;

@Repository
public interface EventRepository extends JpaRepository<EventEntity, String>, QuerydslPredicateExecutor<EventEntity>, EventRepositoryQ {

}