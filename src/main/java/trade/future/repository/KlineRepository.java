package trade.future.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;
import trade.future.model.entity.EventEntity;
import trade.future.model.entity.KlineEntity;

@Repository
public interface KlineRepository extends JpaRepository<KlineEntity, String> {

}